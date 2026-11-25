package ro.alex.minuta

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Brings an existing recording (e.g. a call recording) into the app. */
object Importer {
    /** OpenAI accepts files up to 25 MB; stay under it. */
    private const val LIMIT = 24L * 1024 * 1024
    private val SUPPORTED = setOf("m4a", "mp3", "mp4", "mpeg", "mpga", "wav", "webm", "ogg", "flac")

    fun import(ctx: Context, uri: Uri): String {
        var name = "inregistrare"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        var ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED) {
            ext = when (ctx.contentResolver.getType(uri)) {
                "audio/mpeg" -> "mp3"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/ogg" -> "ogg"
                "audio/webm" -> "webm"
                "audio/flac" -> "flac"
                else -> "m4a"
            }
        }

        val id = MeetingRepo.newId()
        val m = Meeting(id, name.substringBeforeLast('.'), "", System.currentTimeMillis())
        val dir = MeetingRepo.dir(ctx, id)
        val src = File(dir, "import.$ext")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Nu pot citi fișierul.")
            input.use { inp -> src.outputStream().use { inp.copyTo(it) } }
            m.durationMs = durationOf(src)

            // Transcription accepts at most 25 MB and limited duration per request,
            // so long or large recordings are cut into 10-minute parts.
            val tooBig = src.length() > LIMIT
            val tooLong = m.durationMs > 20L * 60 * 1000
            val parts = if (tooBig || tooLong) runCatching { split(src, dir) }.getOrNull() else null
            if (parts != null) {
                src.delete()
                parts.forEach { m.segments.add(it.name) }
            } else if (tooBig) {
                throw IOException("Fișierul are peste 24 MB și nu poate fi împărțit automat. Încearcă un fișier .m4a.")
            } else {
                m.segments.add(src.name)
            }
        } catch (e: Exception) {
            MeetingRepo.delete(ctx, id)
            throw e
        }
        m.status = Status.PENDING
        MeetingRepo.save(ctx, m)
        return id
    }

    private fun durationOf(f: File): Long = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        0L
    }

    /** Cuts an AAC (m4a) file into 10-minute parts without re-encoding. */
    private fun split(src: File, outDir: File, chunkUs: Long = 10L * 60 * 1_000_000): List<File> {
        val ex = MediaExtractor()
        val out = mutableListOf<File>()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw IOException("Fișierul nu conține audio.")
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) {
                throw IOException("Format audio nesuportat pentru împărțire.")
            }
            ex.selectTrack(track)
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var muxTrack = -1
            var chunkStart = 0L
            var index = 0
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (muxer == null || t - chunkStart >= chunkUs) {
                    muxer?.let { it.stop(); it.release() }
                    val f = File(outDir, String.format(java.util.Locale.US, "part_%03d.m4a", index++))
                    out.add(f)
                    val mx = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxTrack = mx.addTrack(format)
                    mx.start()
                    muxer = mx
                    chunkStart = t
                }
                info.set(0, size, t - chunkStart, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer!!.writeSampleData(muxTrack, buf, info)
                ex.advance()
            }
            muxer?.let { it.stop(); it.release() }
            muxer = null
            if (out.isEmpty()) throw IOException("Fișierul audio e gol.")
            return out
        } catch (e: Exception) {
            runCatching { muxer?.release() }
            out.forEach { it.delete() }
            throw e
        } finally {
            ex.release()
        }
    }
}
