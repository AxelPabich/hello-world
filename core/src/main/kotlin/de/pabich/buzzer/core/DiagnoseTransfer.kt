package de.pabich.buzzer.core

import java.util.Locale

/**
 * Diagnoseberichte vom Spieler zum Master: in Teile zerlegt (Nearby-Nachrichten sind auf 32 KB
 * begrenzt) und beim Master wieder zusammengesetzt. Außerdem der Ablagepfad in GitHub.
 */
object DiagnoseTransfer {
    /** Zeichen je Teil – auch mit Umlauten und URL-Kodierung sicher unter der Nearby-Grenze. */
    const val CHUNK_CHARS = 3_000

    fun split(reportId: String, from: String, build: Int, text: String): List<Message.DiagnoseChunk> {
        val parts = text.chunked(CHUNK_CHARS).ifEmpty { listOf("") }
        return parts.mapIndexed { i, part -> Message.DiagnoseChunk(reportId, i, parts.size, from, build, part) }
    }

    /** Ein vollständig empfangener Bericht. */
    data class Report(val id: String, val from: String, val build: Int, val text: String)

    /** Sammelt Teile je Bericht; liefert den Bericht, sobald alle Teile da sind. */
    class Collector {
        private val pending = mutableMapOf<String, Array<String?>>()

        fun add(chunk: Message.DiagnoseChunk): Report? {
            if (chunk.count !in 1..1_000 || chunk.index !in 0 until chunk.count) return null
            val key = "${chunk.from}/${chunk.reportId}"
            val parts = pending.getOrPut(key) { arrayOfNulls(chunk.count) }
            if (parts.size != chunk.count) return null
            parts[chunk.index] = chunk.text
            if (parts.any { it == null }) return null
            pending.remove(key)
            return Report(chunk.reportId, chunk.from, chunk.build, parts.joinToString("") { it.orEmpty() })
        }
    }

    /**
     * Pfad im Diagnose-Repository: `diagnose/JJJJ-MM-TT/Build-N/Name-HH-MM-SS.txt`.
     * [date] als `JJJJ-MM-TT`, [time] als `HH-MM-SS`; der Name wird dateisicher gemacht.
     */
    fun path(date: String, time: String, build: Int, name: String): String {
        val clean = name.trim()
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "-")
            .trim('-')
            .ifEmpty { "Unbekannt" }
            .take(40)
        return String.format(Locale.ROOT, "diagnose/%s/Build-%d/%s-%s.txt", date, build, clean, time)
    }
}
