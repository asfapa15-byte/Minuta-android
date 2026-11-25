package ro.alex.minuta

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val anthropic = findViewById<TextInputEditText>(R.id.anthropic_key)
        val openai = findViewById<TextInputEditText>(R.id.openai_key)
        val claudeModel = findViewById<TextInputEditText>(R.id.claude_model)
        val sttModel = findViewById<TextInputEditText>(R.id.stt_model)
        val context = findViewById<TextInputEditText>(R.id.context)

        anthropic.setText(Prefs.anthropicKey(this))
        openai.setText(Prefs.openAiKey(this))
        claudeModel.setText(Prefs.claudeModel(this))
        sttModel.setText(Prefs.sttModel(this))
        context.setText(Prefs.context(this))

        findViewById<MaterialButton>(R.id.save).setOnClickListener {
            Prefs.save(
                this,
                anthropic.text?.toString().orEmpty(),
                openai.text?.toString().orEmpty(),
                claudeModel.text?.toString().orEmpty(),
                sttModel.text?.toString().orEmpty(),
                context.text?.toString().orEmpty(),
            )
            Toast.makeText(this, "Setările au fost salvate", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
