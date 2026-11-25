package ro.alex.minuta

import org.json.JSONArray
import org.json.JSONObject

data class ActionItem(val task: String, val owner: String, val due: String)

enum class Status { RECORDING, PENDING, TRANSCRIBING, SUMMARIZING, DONE, ERROR }

class Meeting(
    val id: String,
    var title: String,
    var participants: String,
    val createdAt: Long,
) {
    var durationMs: Long = 0
    var status: Status = Status.RECORDING
    var error: String? = null

    /** Audio file names inside the meeting folder, in order. */
    val segments: MutableList<String> = mutableListOf()

    /** Transcript per audio file, so a retry does not pay twice. */
    val segmentTranscripts: MutableMap<String, String> = mutableMapOf()

    var transcript: String = ""
    var summary: String = ""
    val decisions: MutableList<String> = mutableListOf()
    val actions: MutableList<ActionItem> = mutableListOf()

    fun displayTitle(): String = title.ifBlank { "Ședință fără titlu" }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("participants", participants)
        o.put("createdAt", createdAt)
        o.put("durationMs", durationMs)
        o.put("status", status.name)
        o.put("error", error ?: JSONObject.NULL)
        o.put("segments", JSONArray(segments))
        val st = JSONObject()
        for ((k, v) in segmentTranscripts) st.put(k, v)
        o.put("segmentTranscripts", st)
        o.put("transcript", transcript)
        o.put("summary", summary)
        o.put("decisions", JSONArray(decisions))
        val acts = JSONArray()
        for (a in actions) {
            acts.put(JSONObject().put("task", a.task).put("owner", a.owner).put("due", a.due))
        }
        o.put("actions", acts)
        return o
    }

    /** Plain-text minute, for sharing or copying. */
    fun toShareText(): String {
        val sb = StringBuilder()
        sb.append("MINUTA: ").append(displayTitle()).append('\n')
        sb.append("Data: ").append(Format.date(createdAt)).append('\n')
        if (durationMs > 0) sb.append("Durată: ").append(Format.duration(durationMs)).append('\n')
        if (participants.isNotBlank()) sb.append("Participanți: ").append(participants).append('\n')
        sb.append("\nREZUMAT\n").append(summary.ifBlank { "-" }).append('\n')
        sb.append("\nDECIZII\n")
        if (decisions.isEmpty()) sb.append("- Nicio decizie consemnată\n")
        for (d in decisions) sb.append("- ").append(d).append('\n')
        sb.append("\nACȚIUNI\n")
        if (actions.isEmpty()) sb.append("- Nicio acțiune consemnată\n")
        for (a in actions) {
            sb.append("- ").append(a.task)
                .append(" | Responsabil: ").append(a.owner)
                .append(" | Termen: ").append(a.due).append('\n')
        }
        sb.append("\nTRANSCRIERE\n").append(transcript.ifBlank { "-" }).append('\n')
        return sb.toString()
    }

    companion object {
        fun fromJson(o: JSONObject): Meeting {
            val m = Meeting(
                o.getString("id"),
                o.optString("title", ""),
                o.optString("participants", ""),
                o.optLong("createdAt", 0L),
            )
            m.durationMs = o.optLong("durationMs", 0L)
            m.status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.PENDING)
            m.error = if (o.isNull("error")) null else o.optString("error")
            o.optJSONArray("segments")?.let { a ->
                for (i in 0 until a.length()) m.segments.add(a.getString(i))
            }
            o.optJSONObject("segmentTranscripts")?.let { t ->
                val keys = t.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m.segmentTranscripts[k] = t.optString(k, "")
                }
            }
            m.transcript = o.optString("transcript", "")
            m.summary = o.optString("summary", "")
            o.optJSONArray("decisions")?.let { a ->
                for (i in 0 until a.length()) m.decisions.add(a.optString(i))
            }
            o.optJSONArray("actions")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    m.actions.add(ActionItem(x.optString("task"), x.optString("owner"), x.optString("due")))
                }
            }
            return m
        }
    }
}
