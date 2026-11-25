package ro.alex.minuta

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String, val retryable: Boolean) : Exception(message)

/** Plain HttpURLConnection calls, so the app needs no networking library. */
object Api {

    /** Speech to text with OpenAI. Returns the transcript of one audio file. */
    fun transcribe(file: File, apiKey: String, model: String, prompt: String): String {
        val boundary = "----minuta" + System.currentTimeMillis()
        val conn = URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 600_000
        conn.setChunkedStreamingMode(64 * 1024)
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(
                        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                            .toByteArray(Charsets.UTF_8)
                    )
                }
                field("model", model)
                field("language", "ro")
                field("response_format", "json")
                if (prompt.isNotBlank()) field("prompt", prompt)
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
                )
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val body = readBody(conn, "OpenAI")
            return JSONObject(body).optString("text", "").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** One message to Claude. Returns the text of the answer. */
    fun claude(apiKey: String, model: String, system: String, user: String, maxTokens: Int = 4000): String {
        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.setRequestProperty("content-type", "application/json")
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val body = readBody(conn, "Claude")
            val content = JSONObject(body).optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, who: String): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code in 200..299) return body
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: body.take(300)
        val hint = when (code) {
            401 -> " (cheia API $who nu e validă)"
            404 -> " (verifică numele modelului în Setări)"
            429 -> " (limită de utilizare sau credit epuizat)"
            else -> ""
        }
        throw ApiException("$who HTTP $code$hint: $msg", code == 429 || code == 408 || code >= 500)
    }

    /** Retries network failures and temporary server errors, with growing pauses. */
    fun <T> withRetry(times: Int = 3, block: () -> T): T {
        var wait = 4_000L
        var last: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: ApiException) {
                if (!e.retryable) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < times - 1) {
                Thread.sleep(wait)
                wait *= 3
            }
        }
        throw last ?: IOException("Eroare necunoscută")
    }
}
