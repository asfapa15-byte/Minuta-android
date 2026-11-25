package ro.alex.minuta

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class MinuteActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(ctx: Context, id: String): Intent =
            Intent(ctx, MinuteActivity::class.java).putExtra(EXTRA_ID, id)
    }

    private lateinit var id: String
    private lateinit var content: LinearLayout
    private var showTranscript = false
    private var current: Meeting? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        setContentView(R.layout.activity_minute)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        content = findViewById(R.id.content)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MeetingRepo.changes.collect { render() }
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, style: Int, top: Int = 0, color: Int? = null): TextView =
        TextView(this).apply {
            setTextAppearance(style)
            text = s
            setTextIsSelectable(true)
            color?.let { setTextColor(it) }
            setPadding(0, dp(top), 0, 0)
        }

    private fun heading(s: String) = text(s, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, 24)
        .also { it.setTextColor(ContextCompat.getColor(this, R.color.teal)) }

    private fun body(s: String, top: Int = 6) = text(s, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, top)

    private fun muted(s: String, top: Int = 2) =
        text(s, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, top, ContextCompat.getColor(this, R.color.muted))

    private fun render() {
        val m = MeetingRepo.get(this, id) ?: run { finish(); return }
        current = m
        invalidateOptionsMenu()
        content.removeAllViews()

        content.addView(text(m.displayTitle(), com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall))
        val meta = mutableListOf(Format.date(m.createdAt))
        if (m.durationMs > 0) meta.add(Format.duration(m.durationMs))
        content.addView(muted(meta.joinToString(" · "), 4))
        if (m.participants.isNotBlank()) content.addView(muted("Participanți: ${m.participants}"))

        val busy = (m.status == Status.TRANSCRIBING || m.status == Status.SUMMARIZING) && Processor.isRunning(m.id)
        val stuck = m.status == Status.PENDING || m.status == Status.ERROR ||
            ((m.status == Status.TRANSCRIBING || m.status == Status.SUMMARIZING) && !Processor.isRunning(m.id))

        if (m.status == Status.RECORDING) {
            content.addView(body("Ședința se înregistrează încă.", 20))
            return
        }
        if (busy || (m.status == Status.PENDING && Processor.isRunning(m.id))) {
            val label = if (m.status == Status.SUMMARIZING) "Claude scrie minuta…" else {
                val done = m.segmentTranscripts.size
                val total = m.segments.size
                if (total > 1) "Se transcrie audio ($done din $total părți)…" else "Se transcrie audio…"
            }
            content.addView(body(label, 24))
            content.addView(LinearProgressIndicator(this).apply {
                isIndeterminate = true
                setPadding(0, dp(10), 0, 0)
            })
            content.addView(muted("Poți închide aplicația; primești o notificare când e gata.", 10))
        } else if (stuck) {
            if (m.status == Status.ERROR && m.error != null) {
                content.addView(text(m.error!!, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, 20,
                    ContextCompat.getColor(this, R.color.error)))
            } else {
                content.addView(body("Minuta nu a fost generată încă.", 20))
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            row.addView(MaterialButton(this).apply {
                text = "Reîncearcă"
                setOnClickListener { RecordingService.send(this@MinuteActivity, RecordingService.ACTION_PROCESS, m.id) }
            })
            if (!Prefs.hasKeys(this)) {
                val settings = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Setări"
                    setOnClickListener { startActivity(Intent(this@MinuteActivity, SettingsActivity::class.java)) }
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(12)
                row.addView(settings, lp)
            }
            content.addView(row)
        }

        if (m.status == Status.DONE || m.summary.isNotBlank()) {
            content.addView(heading("Rezumat"))
            content.addView(body(m.summary.ifBlank { "—" }))

            content.addView(heading("Decizii"))
            if (m.decisions.isEmpty()) content.addView(muted("Nicio decizie consemnată.", 6))
            for (d in m.decisions) content.addView(body("•  $d"))

            content.addView(heading("Acțiuni"))
            if (m.actions.isEmpty()) content.addView(muted("Nicio acțiune consemnată.", 6))
            for (a in m.actions) {
                content.addView(body(a.task, 12))
                content.addView(muted("Responsabil: ${a.owner}  ·  Termen: ${a.due}"))
            }
        }

        if (m.transcript.isNotBlank()) {
            content.addView(heading("Transcriere"))
            content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = if (showTranscript) "Ascunde transcrierea" else "Arată transcrierea completă"
                setOnClickListener { showTranscript = !showTranscript; render() }
            })
            if (showTranscript) content.addView(body(m.transcript, 4))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.minute, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val done = current?.status == Status.DONE
        menu.findItem(R.id.action_share)?.isVisible = done
        menu.findItem(R.id.action_copy)?.isVisible = done
        menu.findItem(R.id.action_regenerate)?.isVisible = done
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val m = current ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            R.id.action_share -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Minuta: ${m.displayTitle()}")
                    putExtra(Intent.EXTRA_TEXT, m.toShareText())
                }
                startActivity(Intent.createChooser(send, "Trimite minuta"))
            }
            R.id.action_copy -> {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Minuta", m.toShareText()))
                Toast.makeText(this, "Minuta a fost copiată", Toast.LENGTH_SHORT).show()
            }
            R.id.action_regenerate -> {
                MeetingRepo.update(this, m.id) { it.status = Status.PENDING }
                RecordingService.send(this, RecordingService.ACTION_PROCESS, m.id)
            }
            R.id.action_delete -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Ștergi ședința?")
                    .setMessage("Se șterg minuta, transcrierea și înregistrarea audio.")
                    .setNegativeButton("Renunță", null)
                    .setPositiveButton("Șterge") { _, _ ->
                        if (RecordingService.state.value.meetingId == m.id || Processor.isRunning(m.id)) {
                            Toast.makeText(this, "Așteaptă să se termine procesarea.", Toast.LENGTH_SHORT).show()
                        } else {
                            MeetingRepo.delete(this, m.id)
                            finish()
                        }
                    }
                    .show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
