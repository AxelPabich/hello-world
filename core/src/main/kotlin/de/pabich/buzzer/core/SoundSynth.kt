package de.pabich.buzzer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Erzeugt alle Spielklänge synthetisch als 16-Bit-PCM (mono) – keine Sounddateien nötig.
 * Die Rezepte entsprechen dem Klang-Entwurf (Oszillatoren, Hüllkurven, gefiltertes Rauschen).
 */
object SoundSynth {
    const val SAMPLE_RATE = 22_050

    fun render(event: SoundEvent): ShortArray = when (event) {
        SoundEvent.JOIN -> mix(0.7) { tone(0.0, 1318.0, 0.6, Wave.SINE, 0.3) }
        SoundEvent.HUM -> mix(1.7) { hum() }
        SoundEvent.BUZZ -> mix(0.8) {
            tone(0.0, 110.0, 0.7, Wave.SAW, 0.25)
            tone(0.0, 166.0, 0.7, Wave.SQUARE, 0.12)
        }
        SoundEvent.FIRST -> mix(1.2) { fanfare(0.0) }
        SoundEvent.RED -> mix(0.45) { tone(0.0, 180.0, 0.35, Wave.SINE, 0.5, freqEnd = 55.0) }
        SoundEvent.FALSE_START -> mix(0.9) { vibrato(0.0, 233.0, 0.8, 18.0, 25.0, 0.2) }
        SoundEvent.SUDDEN_DEATH -> mix(2.2) { drumroll(0.0) }
        SoundEvent.TICK -> mix(0.08) { noise(0.0, 0.03, 0.6, 1800.0, 5.0) }
        SoundEvent.TIMEOUT -> mix(2.6) {
            listOf(196.0, 293.7, 419.0, 591.0).forEachIndexed { i, f -> tone(0.0, f, 2.5 - i * 0.4, Wave.SINE, 0.25 / (i + 1)) }
        }
        SoundEvent.RIGHT -> mix(3.1) {
            tone(0.0, 1318.0, 0.5, Wave.SINE, 0.3)
            tone(0.18, 1760.0, 0.9, Wave.SINE, 0.3)
            applause(0.3, 2.6)
        }
        SoundEvent.WRONG -> mix(2.4) {
            listOf(392.0, 370.0, 349.0).forEachIndexed { i, f -> tone(i * 0.42, f, 0.4, Wave.TRIANGLE, 0.3) }
            tone(1.26, 330.0, 1.0, Wave.TRIANGLE, 0.3, freqEnd = 300.0)
        }
        SoundEvent.ROUND_END -> mix(2.9) {
            drumroll(0.0)
            fanfare(2.1)
        }
        SoundEvent.EMOTE -> mix(0.15) { tone(0.0, 500.0, 0.12, Wave.SINE, 0.35, freqEnd = 1200.0) }
        SoundEvent.CHAT -> mix(0.55) {
            tone(0.0, 2093.0, 0.5, Wave.SINE, 0.18)
            tone(0.0, 3136.0, 0.3, Wave.SINE, 0.06)
        }
        SoundEvent.CHASE -> chase(MasterEngine.MAX_REPLAY_MS)
    }

    /** Cartoon-Verfolgungsjagd: galoppierender Bass, chromatische Läufe, Hi-Hat, steigende Tonart. */
    fun chase(durationMs: Long): ShortArray {
        val seconds = durationMs / 1000.0
        return mix(seconds) {
            val step = 60.0 / 168 / 4
            val run = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3, 2, 1)
            var t = 0.0
            var i = 0
            var transpose = 0
            while (t < seconds - 0.6) {
                if (i % 2 == 0) {
                    val bass = 45 + transpose + if ((i / 2) % 2 == 0) 0 else 7
                    tone(t, midi(bass), step * 1.8, Wave.SQUARE, 0.16)
                }
                tone(t, midi(64 + transpose + run[i % run.size]), step * 0.9, Wave.TRIANGLE, 0.14)
                if (i % 2 == 1) noise(t, 0.03, 0.25, 7000.0, 1.0)
                i++
                if (i % 32 == 0) transpose += 2
                t += step
            }
            // Schlussakkord mit Paukenschlag.
            listOf(0, 4, 7).forEach { tone(t, midi(57 + transpose + it), 0.5, Wave.SQUARE, 0.12) }
            noise(t, 0.4, 0.8, 120.0, 0.6)
        }
    }

    private fun midi(note: Int) = 440.0 * 2.0.pow((note - 69) / 12.0)

    private fun mix(seconds: Double, build: Mix.() -> Unit): ShortArray = Mix(seconds).apply(build).toPcm()

    private enum class Wave { SINE, SQUARE, SAW, TRIANGLE }

    private class Mix(seconds: Double) {
        val buf = DoubleArray((seconds * SAMPLE_RATE).toInt().coerceAtLeast(1))
        val random = Random(42)

        private fun wave(w: Wave, phase: Double): Double {
            val p = phase - floor(phase)
            return when (w) {
                Wave.SINE -> sin(2 * PI * p)
                Wave.SQUARE -> if (p < 0.5) 1.0 else -1.0
                Wave.SAW -> 2 * p - 1
                Wave.TRIANGLE -> 1 - 4 * abs(p - 0.5)
            }
        }

        /** Exponentielle Hüllkurve wie in WebAudio: kurzer Anstieg, dann Abklingen auf ~0. */
        private fun envelope(x: Double, dur: Double, vol: Double, attack: Double = 0.01): Double {
            if (x < 0 || x > dur) return 0.0
            if (x < attack) return 0.0001 * (vol / 0.0001).pow(x / attack)
            return vol * (0.0001 / vol).pow((x - attack) / (dur - attack).coerceAtLeast(1e-6))
        }

        fun tone(t: Double, freq: Double, dur: Double, w: Wave, vol: Double, freqEnd: Double? = null) {
            val start = (t * SAMPLE_RATE).toInt()
            val n = (dur * SAMPLE_RATE).toInt()
            var phase = 0.0
            for (k in 0 until n) {
                val idx = start + k
                if (idx >= buf.size) break
                val x = k.toDouble() / SAMPLE_RATE
                val f = if (freqEnd != null) freq * (freqEnd / freq).pow(x / dur) else freq
                phase += f / SAMPLE_RATE
                buf[idx] += wave(w, phase) * envelope(x, dur, vol)
            }
        }

        fun vibrato(t: Double, freq: Double, dur: Double, rate: Double, depth: Double, vol: Double) {
            val start = (t * SAMPLE_RATE).toInt()
            var phase = 0.0
            for (k in 0 until (dur * SAMPLE_RATE).toInt()) {
                val idx = start + k
                if (idx >= buf.size) break
                val x = k.toDouble() / SAMPLE_RATE
                phase += (freq + depth * sin(2 * PI * rate * x)) / SAMPLE_RATE
                buf[idx] += wave(Wave.SAW, phase) * vol * (0.0001 / vol).pow(x / dur)
            }
        }

        /** Weißes Rauschen durch einen Bandpass (RBJ-Biquad), exponentiell abklingend. */
        fun noise(t: Double, dur: Double, vol: Double, freq: Double, q: Double) {
            val w0 = 2 * PI * freq.coerceAtMost(SAMPLE_RATE / 2.2) / SAMPLE_RATE
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val b0 = alpha / a0
            val b2 = -alpha / a0
            val a1 = -2 * cos(w0) / a0
            val a2 = (1 - alpha) / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            val start = (t * SAMPLE_RATE).toInt()
            for (k in 0 until (dur * SAMPLE_RATE).toInt()) {
                val idx = start + k
                if (idx >= buf.size) break
                val x0 = random.nextDouble() * 2 - 1
                val y0 = b0 * x0 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x0; y2 = y1; y1 = y0
                buf[idx] += y0 * vol * (0.0001 / vol).pow(k.toDouble() / SAMPLE_RATE / dur)
            }
        }

        fun fanfare(t: Double) {
            listOf(523.0, 659.0, 784.0, 1047.0).forEachIndexed { i, f ->
                tone(t + i * 0.12, f, if (i == 3) 0.7 else 0.16, Wave.SQUARE, 0.12)
            }
        }

        fun drumroll(t: Double) {
            for (i in 0 until 36) noise(t + i * 0.045, 0.06, 0.15 + i * 0.012, 180.0, 0.8)
            noise(t + 1.62, 0.5, 0.9, 120.0, 0.6)
            tone(t + 1.62, 80.0, 0.5, Wave.SINE, 0.6, freqEnd = 40.0)
        }

        fun applause(t: Double, dur: Double) {
            repeat(140) {
                noise(
                    t + random.nextDouble() * dur,
                    0.04 + random.nextDouble() * 0.04,
                    0.15 + random.nextDouble() * 0.25,
                    1000 + random.nextDouble() * 2500,
                    1.5,
                )
            }
        }

        /** Summen mit Tiefpass und zwei „Funken“; feste Länge, unabhängig von der Freigabe. */
        fun hum() {
            val n = (1.6 * SAMPLE_RATE).toInt()
            val w0 = 2 * PI * 600 / SAMPLE_RATE
            val alpha = sin(w0) / (2 * 0.707)
            val a0 = 1 + alpha
            val b0 = (1 - cos(w0)) / 2 / a0
            val b1 = (1 - cos(w0)) / a0
            val a1 = -2 * cos(w0) / a0
            val a2 = (1 - alpha) / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            var p1 = 0.0; var p2 = 0.0
            for (k in 0 until minOf(n, buf.size)) {
                val x = k.toDouble() / SAMPLE_RATE
                p1 += 50.0 / SAMPLE_RATE
                p2 += 100.6 / SAMPLE_RATE
                val x0 = wave(Wave.SAW, p1) + wave(Wave.SQUARE, p2)
                val y0 = b0 * x0 + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x0; y2 = y1; y1 = y0
                val gain = when {
                    x < 0.3 -> 0.12 * x / 0.3
                    x > 1.3 -> 0.12 * (1.6 - x) / 0.3
                    else -> 0.12
                }
                buf[k] += y0 * gain
            }
            noise(0.5, 0.05, 0.4, 4000.0, 2.0)
            noise(1.0, 0.04, 0.35, 5000.0, 2.0)
        }

        fun toPcm(): ShortArray {
            val peak = buf.maxOfOrNull { abs(it) } ?: 0.0
            val scale = if (peak > 0.9) 0.9 / peak else 1.0
            return ShortArray(buf.size) { (buf[it] * scale * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
        }
    }
}
