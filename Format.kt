package ro.alex.minuta

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    val RO: Locale = Locale.forLanguageTag("ro-RO")

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun date(ms: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", RO).format(Date(ms))

    fun longDate(ms: Long): String = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", RO).format(Date(ms))

    fun statusLabel(m: Meeting): String = when (m.status) {
        Status.RECORDING -> "Se înregistrează"
        Status.PENDING -> "În așteptare"
        Status.TRANSCRIBING -> "Se transcrie…"
        Status.SUMMARIZING -> "Se scrie minuta…"
        Status.ERROR -> "Eroare – deschide pentru a reîncerca"
        Status.DONE -> when (m.actions.size) {
            0 -> "Fără acțiuni"
            1 -> "1 acțiune"
            else -> "${m.actions.size} acțiuni"
        }
    }
}
