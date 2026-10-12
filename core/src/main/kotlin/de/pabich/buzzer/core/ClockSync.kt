package de.pabich.buzzer.core

/**
 * Schätzt die Abweichung einer Spieler-Uhr zur Master-Uhr nach dem NTP-Prinzip.
 * Spielerzeit ≈ Masterzeit + [offsetMs]. Verwendet wird die Messung mit der kürzesten Laufzeit.
 */
class ClockSync(private val maxSamples: Int = 16) {
    private data class Sample(val rttMs: Long, val offsetMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var fixed: Sample? = null

    val sampleCount: Int get() = if (fixed != null) maxSamples else samples.size

    /** Abweichung aus der Messung mit der kürzesten Laufzeit (0, solange keine Messung vorliegt). */
    val offsetMs: Long get() = (fixed ?: samples.minByOrNull { it.rttMs })?.offsetMs ?: 0

    /** Größte Laufzeit der letzten Messungen – Grundlage für Vorlauf und Sammelfenster. */
    val recentMaxRttMs: Long? get() = fixed?.rttMs ?: samples.maxOfOrNull { it.rttMs }

    /** Für den lokalen Spieler auf dem Master-Gerät: gleiche Uhr, keine Laufzeit. */
    fun setLocal() {
        fixed = Sample(0, 0)
    }

    fun addSample(masterSent: Long, playerTime: Long, masterReceived: Long) {
        val rtt = masterReceived - masterSent
        if (rtt < 0 || fixed != null) return
        // Annahme: Hin- und Rückweg dauern gleich lang.
        val offset = playerTime - (masterSent + masterReceived) / 2
        samples.addLast(Sample(rtt, offset))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    fun toPlayerTime(masterTime: Long): Long = masterTime + offsetMs
}
