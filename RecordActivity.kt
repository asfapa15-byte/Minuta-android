package ro.alex.minuta

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class RecordActivity : AppCompatActivity() {

    private lateinit var titleField: TextInputEditText
    private lateinit var peopleField: TextInputEditText
    private lateinit var timer: TextView
    private lateinit var stateText: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var controls: View
    private lateinit var pauseBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private var shownId: String? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMic()) begin()
        else Toast.makeText(this, "Fără acces la microfon nu pot înregistra.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        titleField = findViewById(R.id.title)
        peopleField = findViewById(R.id.participants)
        timer = findViewById(R.id.timer)
        stateText = findViewById(R.id.state)
        startBtn = findViewById(R.id.start)
        controls = findViewById(R.id.controls)
        pauseBtn = findViewById(R.id.pause)
        stopBtn = findViewById(R.id.stop)

        startBtn.setOnClickListener { requestAndStart() }
        pauseBtn.setOnClickListener {
            val action = if (RecordingService.state.value.paused) RecordingService.ACTION_RESUME
            else RecordingService.ACTION_PAUSE
            RecordingService.send(this, action)
        }
        stopBtn.setOnClickListener { stopAndOpen() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RecordingService.state.collect { render(it) } }
                launch { MeetingRepo.changes.collect { render(RecordingService.state.value) } }
            }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun requestAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) begin() else permissions.launch(missing.toTypedArray())
    }

    private fun begin() {
        if (RecordingService.state.value.meetingId != null) return
        val m = Meeting(
            MeetingRepo.newId(),
            titleField.text?.toString().orEmpty().trim(),
            peopleField.text?.toString().orEmpty().trim(),
            System.currentTimeMillis(),
        )
        m.status = Status.RECORDING
        MeetingRepo.save(this, m)
        shownId = m.id
        RecordingService.send(this, RecordingService.ACTION_START, m.id)
        startBtn.isEnabled = false
        stateText.text = "Pornesc microfonul…"
    }

    private fun stopAndOpen() {
        val id = RecordingService.state.value.meetingId ?: return
        MeetingRepo.update(this, id) {
            it.title = titleField.text?.toString().orEmpty().trim()
            it.participants = peopleField.text?.toString().orEmpty().trim()
        }
        RecordingService.send(this, RecordingService.ACTION_STOP)
        startActivity(MinuteActivity.intent(this, id))
        finish()
    }

    private fun render(s: RecordingService.RecState) {
        val recording = s.meetingId != null
        if (recording && shownId != s.meetingId) {
            // Came back to a recording already running: show its fields.
            shownId = s.meetingId
            MeetingRepo.get(this, s.meetingId!!)?.let {
                titleField.setText(it.title)
                peopleField.setText(it.participants)
            }
        }
        if (recording) startBtn.isEnabled = true
        startBtn.visibility = if (recording) View.GONE else View.VISIBLE
        controls.visibility = if (recording) View.VISIBLE else View.GONE
        timer.text = Format.duration(s.elapsedMs)
        pauseBtn.text = if (s.paused) "Continuă" else "Pauză"
        if (recording) {
            stateText.text = if (s.paused) "În pauză" else "Se înregistrează. Poți stinge ecranul."
        } else if (startBtn.isEnabled) {
            stateText.text = "Gata de înregistrare"
        } else if (shownId != null) {
            // The service refused to start (e.g. microphone busy).
            MeetingRepo.get(this, shownId!!)?.takeIf { it.status == Status.ERROR }?.let {
                stateText.text = it.error ?: "Nu am putut porni înregistrarea."
                startBtn.isEnabled = true
                shownId = null
            }
        }
    }
}
