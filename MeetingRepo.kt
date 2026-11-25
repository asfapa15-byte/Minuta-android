package ro.alex.minuta

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Each meeting lives in files/meetings/<id>/ : meeting.json plus its audio files. */
object MeetingRepo {
    private val lock = Any()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    private fun root(ctx: Context): File = File(ctx.filesDir, "meetings").apply { mkdirs() }

    fun dir(ctx: Context, id: String): File = File(root(ctx), id).apply { mkdirs() }

    fun newId(): String = System.currentTimeMillis().toString()

    fun get(ctx: Context, id: String): Meeting? {
        synchronized(lock) {
            val f = File(File(root(ctx), id), "meeting.json")
            if (!f.exists()) return null
            return runCatching { Meeting.fromJson(JSONObject(f.readText())) }.getOrNull()
        }
    }

    fun save(ctx: Context, m: Meeting) {
        synchronized(lock) {
            val f = File(dir(ctx, m.id), "meeting.json")
            val tmp = File(f.path + ".tmp")
            tmp.writeText(m.toJson().toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
        _changes.value = System.nanoTime()
    }

    /** Read-modify-write in one step, so two writers don't overwrite each other. */
    fun update(ctx: Context, id: String, block: (Meeting) -> Unit): Meeting? {
        synchronized(lock) {
            val m = get(ctx, id) ?: return null
            block(m)
            save(ctx, m)
            return m
        }
    }

    fun all(ctx: Context): List<Meeting> = synchronized(lock) {
        root(ctx).listFiles()?.mapNotNull { d -> if (d.isDirectory) get(ctx, d.name) else null }
            ?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    fun delete(ctx: Context, id: String) {
        synchronized(lock) { File(root(ctx), id).deleteRecursively() }
        _changes.value = System.nanoTime()
    }
}
