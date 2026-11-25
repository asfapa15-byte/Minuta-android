package ro.alex.minuta

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Turns a meeting's audio into a transcript, then into a minute. */
object Processor {
    private val running = mutableSetOf<String>()

    fun isRunning(id: String): Boolean = synchronized(running) { id in running }

    suspend fun process(ctx: Context, id: String) {
        withContext(Dispatchers.IO) { processBlocking(ctx, id) }
    }

    private fun processBlocking(ctx: Context, id: String) {
        val first = synchronized(running) { running.add(id) }
        if (first) {
            try {
                run(ctx, id)
            } catch (e: Exception) {
                val msg = when (e) {
                    is ApiException -> e.message
                    is IOException -> "Problemă de conexiune: ${e.message ?: "fără internet?"}"
                    else -> e.message ?: e.javaClass.simpleName
                }
                MeetingRepo.update(ctx, id) { it.status = Status.ERROR; it.error = msg }
            } finally {
                synchronized(running) { running.remove(id) }
            }
        }
    }

    private fun run(ctx: Context, id: String) {
        val openAi = Prefs.openAiKey(ctx)
        val anthropic = Prefs.anthropicKey(ctx)
        if (openAi.isEmpty() || anthropic.isEmpty()) {
            MeetingRepo.update(ctx, id) {
                it.status = Status.ERROR
                it.error = "Lipsesc cheile API. Completează-le în Setări, apoi apasă Reîncearcă."
            }
            return
        }

        // 1. Transcribe every audio part not transcribed yet.
        val start = MeetingRepo.update(ctx, id) { it.status = Status.TRANSCRIBING; it.error = null } ?: return
        val dir = MeetingRepo.dir(ctx, id)
        var previous = ""
        for (seg in start.segments) {
            val done = start.segmentTranscripts[seg]
            if (done != null) {
                previous = done
                continue
            }
            val f = File(dir, seg)
            val text = if (!f.exists() || f.length() < 2_000) "" else Api.withRetry {
                Api.transcribe(f, openAi, Prefs.sttModel(ctx), sttPrompt(start, previous))
            }
            MeetingRepo.update(ctx, id) { it.segmentTranscripts[seg] = text }
            if (text.isNotBlank()) previous = text
        }

        val m = MeetingRepo.update(ctx, id) { mm ->
            mm.transcript = mm.segments.mapNotNull { mm.segmentTranscripts[it] }
                .filter { it.isNotBlank() }.joinToString("\n\n")
            mm.status = Status.SUMMARIZING
        } ?: return
        if (m.transcript.isBlank()) {
            MeetingRepo.update(ctx, id) {
                it.status = Status.ERROR
                it.error = "Nu s-a detectat vorbire în înregistrare."
            }
            return
        }

        // 2. Ask Claude for the minute.
        val answer = Api.withRetry {
            Api.claude(anthropic, Prefs.claudeModel(ctx), systemPrompt(ctx), userPrompt(m))
        }
        val json = parseJson(answer)
        MeetingRepo.update(ctx, id) { mm ->
            if (mm.title.isBlank()) mm.title = json.optString("title", "").trim()
            mm.summary = json.optString("summary", "").trim()
            mm.decisions.clear()
            json.optJSONArray("decisions")?.let { a ->
                for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { mm.decisions.add(it) }
            }
            mm.actions.clear()
            json.optJSONArray("actions")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val task = x.optString("task").trim()
                    if (task.isEmpty()) continue
                    mm.actions.add(
                        ActionItem(
                            task,
                            x.optString("owner").trim().ifEmpty { "Nestabilit" },
                            x.optString("due").trim().ifEmpty { "Nestabilit" },
                        )
                    )
                }
            }
            mm.status = Status.DONE
            mm.error = null
        }
    }

    private fun sttPrompt(m: Meeting, previous: String): String {
        val sb = StringBuilder("Ședință de lucru în limba română.")
        if (m.title.isNotBlank()) sb.append(" Subiect: ").append(m.title).append('.')
        if (m.participants.isNotBlank()) sb.append(" Participanți: ").append(m.participants).append('.')
        if (previous.isNotBlank()) sb.append(' ').append(previous.takeLast(400))
        return sb.toString()
    }

    private fun systemPrompt(ctx: Context): String {
        val extra = Prefs.context(ctx)
        return buildString {
            append("Ești un secretar de ședință atent și concis. Redactezi minute de ședință în limba română, ")
            append("clare, factuale, fără să inventezi nimic ce nu reiese din discuție.")
            if (extra.isNotEmpty()) append("\nContextul echipei: ").append(extra)
        }
    }

    private fun userPrompt(m: Meeting): String = buildString {
        append("Data ședinței: ").append(Format.longDate(m.createdAt)).append('\n')
        append("Subiect declarat: ").append(m.title.ifBlank { "(nespecificat – propune unul scurt)" }).append('\n')
        append("Participanți declarați: ").append(m.participants.ifBlank { "(nespecificați)" }).append("\n\n")
        append(
            """
            Mai jos este transcrierea automată a ședinței. Poate conține greșeli de recunoaștere vocală și nu indică cine vorbește.
            Scrie minuta și răspunde DOAR cu un obiect JSON valid, fără alt text și fără ```, exact cu forma:
            {"title": "subiect scurt", "summary": "rezumat în 3-6 propoziții", "decisions": ["decizie"], "actions": [{"task": "ce trebuie făcut", "owner": "responsabil", "due": "termen"}]}
            Reguli:
            - Include doar decizii și acțiuni care reies clar din discuție; dacă nu există, folosește liste goale.
            - Dacă responsabilul sau termenul nu reies clar, scrie "Nestabilit".
            - Transformă termenele relative („vineri”, „săptămâna viitoare”) în date concrete, pornind de la data ședinței, de ex. „vineri, 28 noiembrie”.
            - Corectează numele și termenii tehnici evident greșit transcriși.
            """.trimIndent()
        )
        append("\n\nTRANSCRIERE:\n").append(m.transcript)
    }

    private fun parseJson(text: String): JSONObject {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) throw IOException("Răspuns neașteptat de la Claude. Apasă Reîncearcă.")
        return try {
            JSONObject(text.substring(s, e + 1))
        } catch (ex: Exception) {
            throw IOException("Răspuns neașteptat de la Claude. Apasă Reîncearcă.")
        }
    }
}
