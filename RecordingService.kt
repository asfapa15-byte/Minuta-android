package ro.alex.minuta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Records the meeting in the background (screen off is fine) and then
 * runs the transcription + minute, so nothing is lost if the app is closed.
 * Audio is cut into 10-minute parts so each one fits the transcription limit.
 */
class RecordingService : Service() {

    data class RecState(val meetingId: String? = null, val paused: Boolean = false, val elapsedMs: Long = 0)

    companion object {
        const val ACTION_START = "ro.alex.minuta.START"
        const val ACTION_PAUSE = "ro.alex.minuta.PAUSE"
        const val ACTION_RESUME = "ro.alex.minuta.RESUME"
        const val ACTION_STOP = "ro.alex.minuta.STOP"
        const val ACTION_PROCESS = "ro.alex.minuta.PROCESS"
        const val EXTRA_ID = "id"

        private const val CH_REC = "recording"
        private const val CH_DONE = "done"
        private const val NID = 1
        private const val SEGMENT_MS = 10 * 60 * 1000L

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        fun send(ctx: Context, action: String, id: String? = null) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action)
            if (id != null) i.putExtra(EXTRA_ID, id)
            if (action == ACTION_START || action == ACTION_PROCESS) {
                ContextCompat.startForegroundService(ctx, i)
            } else {
                ctx.startService(i)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var meetingId: String? = null
    private var paused = false
    private var accumulated = 0L
    private var runStart = 0L
    private var jobs = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, 1000)
        }
    }

    private val rotator = object : Runnable {
        override fun run() {
            if (recorder != null && !paused) {
                closeRecorder()
                try {
                    openSegment()
                } catch (e: Exception) {
                    failRecording("Înregistrarea s-a oprit: ${e.message}")
                    return
                }
            }
            handler.postDelayed(this, SEGMENT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> startRecording(id)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PROCESS -> {
                updateForeground()
                if (id != null) startProcessing(id)
            }
        }
        if (meetingId == null && jobs == 0) stopEverything()
        return START_NOT_STICKY
    }

    // ---- recording ----

    private fun startRecording(id: String?) {
        if (meetingId != null || id == null) {
            updateForeground()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateForeground()
            MeetingRepo.update(this, id) { it.status = Status.ERROR; it.error = "Lipsește permisiunea pentru microfon." }
            return
        }
        meetingId = id
        paused = false
        accumulated = 0
        runStart = SystemClock.elapsedRealtime()
        updateForeground()
        try {
            openSegment()
        } catch (e: Exception) {
            failRecording("Nu am putut porni microfonul: ${e.message}")
            return
        }
        acquireWakeLock()
        handler.post(ticker)
        handler.postDelayed(rotator, SEGMENT_MS)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun openSegment() {
        val id = meetingId ?: return
        val m = MeetingRepo.get(this, id) ?: throw IllegalStateException("ședința nu mai există")
        val name = String.format(Locale.US, "seg_%03d.m4a", m.segments.size)
        val file = File(MeetingRepo.dir(this, id), name)
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        MeetingRepo.update(this, id) { it.segments.add(name) }
    }

    private fun closeRecorder() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // Happens when a part has no audio yet; the processor skips tiny files.
        }
        r.release()
    }

    private fun pauseRecording() {
        val r = recorder ?: return
        if (paused) return
        try {
            r.pause()
        } catch (e: Exception) {
            return
        }
        accumulated += SystemClock.elapsedRealtime() - runStart
        paused = true
        updateForeground()
        publish()
    }

    private fun resumeRecording() {
        val r = recorder ?: return
        if (!paused) return
        try {
            r.resume()
        } catch (e: Exception) {
            return
        }
        runStart = SystemClock.elapsedRealtime()
        paused = false
        updateForeground()
        publish()
    }

    private fun elapsed(): Long =
        accumulated + if (meetingId != null && !paused) SystemClock.elapsedRealtime() - runStart else 0L

    private fun stopRecording() {
        val id = meetingId ?: return
        val duration = elapsed()
        handler.removeCallbacks(rotator)
        handler.removeCallbacks(ticker)
        closeRecorder()
        meetingId = null
        paused = false
        releaseWakeLock()
        _state.value = RecState()
        MeetingRepo.update(this, id) { it.durationMs = duration; it.status = Status.PENDING }
        startProcessing(id)
    }

    private fun failRecording(message: String) {
        val id = meetingId
        handler.removeCallbacks(rotator)
        handler.removeCallbacks(ticker)
        closeRecorder()
        meetingId = null
        paused = false
        releaseWakeLock()
        _state.value = RecState()
        if (id != null) {
            MeetingRepo.update(this, id) { it.status = Status.ERROR; it.error = message }
        }
        if (jobs == 0) stopEverything() else updateForeground()
    }

    private fun publish() {
        _state.value = RecState(meetingId, paused, elapsed())
    }

    // ---- processing ----

    private fun startProcessing(id: String) {
        jobs++
        updateForeground()
        scope.launch {
            Processor.process(applicationContext, id)
            jobs--
            notifyResult(id)
            if (meetingId == null && jobs == 0) stopEverything() else updateForeground()
        }
    }

    // ---- foreground + notifications ----

    private fun updateForeground() {
        var type = 0
        if (Build.VERSION.SDK_INT >= 30 && meetingId != null) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= 29 && (jobs > 0 || type == 0)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NID, buildNotification(), type)
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REC, "Înregistrare", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Minute gata", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val target = if (meetingId != null) RecordActivity::class.java else MainActivity::class.java
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CH_REC)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (meetingId != null) {
            if (paused) {
                b.setContentTitle("Înregistrare în pauză")
                    .setContentText("Durată: " + Format.duration(elapsed()))
                    .addAction(0, "Continuă", actionIntent(ACTION_RESUME, 1))
            } else {
                b.setContentTitle("Se înregistrează ședința")
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis() - elapsed())
                    .addAction(0, "Pauză", actionIntent(ACTION_PAUSE, 1))
            }
            b.addAction(0, "Oprește", actionIntent(ACTION_STOP, 2))
        } else {
            b.setContentTitle("Se pregătește minuta…")
                .setContentText("Transcriere și redactare")
                .setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun notifyResult(id: String) {
        val m = MeetingRepo.get(this, id) ?: return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(
            this, id.hashCode(), MinuteActivity.intent(this, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ok = m.status == Status.DONE
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (ok) "Minuta e gata" else "Minuta nu a putut fi generată")
            .setContentText(if (ok) m.displayTitle() else (m.error ?: "Deschide pentru a reîncerca"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(1000 + (id.hashCode() and 0xFFFF), n)
        } catch (e: SecurityException) {
            // Notifications not allowed; the result is still in the app.
        }
    }

    // ---- wake lock ----

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minuta:recording").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val id = meetingId
        if (id != null) {
            val duration = elapsed()
            closeRecorder()
            meetingId = null
            MeetingRepo.update(this, id) { it.durationMs = duration; it.status = Status.PENDING }
        }
        releaseWakeLock()
        _state.value = RecState()
        scope.cancel()
        super.onDestroy()
    }
}
