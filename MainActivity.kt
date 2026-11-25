package ro.alex.minuta

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var banner: TextView
    private lateinit var empty: TextView
    private val adapter = MeetingAdapter { m -> startActivity(MinuteActivity.intent(this, m.id)) }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importAudio(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        banner = findViewById(R.id.banner)
        empty = findViewById(R.id.empty)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MeetingRepo.changes.collect { refresh() } }
                launch { RecordingService.state.collect { refreshBanner() } }
            }
        }
    }

    private fun refresh() {
        val recId = RecordingService.state.value.meetingId
        val items = MeetingRepo.all(this)
        // A recording interrupted by a crash or reboot: mark it so it can be processed.
        for (m in items) {
            if (m.status == Status.RECORDING && m.id != recId) {
                MeetingRepo.update(this, m.id) { it.status = Status.PENDING }
            }
        }
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        refreshBanner()
    }

    private fun refreshBanner() {
        val rec = RecordingService.state.value.meetingId
        when {
            rec != null -> {
                banner.text = "● Înregistrare în curs – atinge pentru a reveni"
                banner.setOnClickListener { startActivity(Intent(this, RecordActivity::class.java)) }
                banner.visibility = View.VISIBLE
            }
            !Prefs.hasKeys(this) -> {
                banner.text = "Adaugă cheile API în Setări ca să poți genera minute. Atinge aici."
                banner.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
                banner.visibility = View.VISIBLE
            }
            else -> banner.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBanner()
    }

    private fun importAudio(uri: Uri) {
        Toast.makeText(this, "Se importă fișierul…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Importer.import(this@MainActivity, uri) } }
            result.onSuccess { id ->
                RecordingService.send(this@MainActivity, RecordingService.ACTION_PROCESS, id)
                startActivity(MinuteActivity.intent(this@MainActivity, id))
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, e.message ?: "Importul a eșuat.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import -> {
            pickAudio.launch(arrayOf("audio/*"))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

class MeetingAdapter(private val onClick: (Meeting) -> Unit) : RecyclerView.Adapter<MeetingAdapter.VH>() {
    private var items: List<Meeting> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Meeting>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_meeting, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.title.text = m.displayTitle()
        val parts = mutableListOf(Format.date(m.createdAt))
        if (m.durationMs > 0) parts.add(Format.duration(m.durationMs))
        parts.add(Format.statusLabel(m))
        holder.subtitle.text = parts.joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(m) }
    }
}
