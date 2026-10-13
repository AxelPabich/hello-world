package de.pabich.buzzer.core

/**
 * Version der Verständigung zwischen den Geräten. Steigt nur, wenn sich Nachrichten inkompatibel
 * ändern; unterschiedliche Builds mit gleicher Protokoll-Version spielen zusammen.
 * Builds bis 11 kannten noch keine Versionsangabe und gelten als Protokoll 1.
 */
const val PROTOCOL_VERSION = 2

/**
 * Name und Version eines Geräts, wie sie bei Suche und Beitritt übertragen werden:
 * `Name [Build/Protokoll]`, beim Beitritt nur zum Aktualisieren `Name [Build/Protokoll/u]`.
 * Dieses Format bleibt in allen künftigen Versionen gleich.
 */
data class PeerInfo(val name: String, val build: Int?, val protocol: Int, val updateOnly: Boolean = false) {
    val compatible: Boolean get() = protocol == PROTOCOL_VERSION

    /** Kannte den Versionsabgleich noch nicht (Build 11 oder älter). */
    val legacy: Boolean get() = build == null

    companion object {
        private val PATTERN = Regex("""^(.*) \[(\d+)/(\d+)(/u)?]$""")

        fun format(name: String, build: Int, updateOnly: Boolean = false): String =
            "$name [$build/$PROTOCOL_VERSION${if (updateOnly) "/u" else ""}]"

        fun parse(text: String): PeerInfo {
            val m = PATTERN.matchEntire(text) ?: return PeerInfo(text, null, 1)
            return PeerInfo(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].isNotEmpty())
        }
    }
}
