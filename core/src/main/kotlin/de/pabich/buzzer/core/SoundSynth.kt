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
        SoundEvent.WRONG -> mix(3.1) {
            // Gestopfte Posaune: drei absteigende „Wah“, dann ein langes, wackelndes „Waaah“.
            listOf(293.7, 277.2, 261.6).forEachIndexed { i, f -> trombone(i * 0.5, f, 0.44, 0.32) }
            trombone(1.5, 246.9, 1.5, 0.34, wobble = true, glideTo = 220.0)
        }
        SoundEvent.TUSCH -> mix(1.6) {
            // Kurzer Schlag, dann ein Akkord-Stoß „Ta-daaa“ wie die Showband.
            noise(0.0, 0.18, 0.7, 160.0, 0.7)
            tone(0.0, 70.0, 0.25, Wave.SINE, 0.5, freqEnd = 45.0)
            for (f in listOf(523.0, 659.0, 784.0)) tone(0.02, f, 0.14, Wave.SAW, 0.09)
            for (f in listOf(523.0, 659.0, 784.0, 1047.0)) tone(0.2, f, 1.3, Wave.SAW, 0.08)
            noise(0.2, 1.2, 0.25, 6000.0, 1.2)
        }
        SoundEvent.GO -> mix(1.2) {
            // Zwei kurze Signale und ein hoher, langer Ton – wie ein Startsignal.
            tone(0.0, 880.0, 0.18, Wave.SQUARE, 0.12)
            tone(0.3, 880.0, 0.18, Wave.SQUARE, 0.12)
            tone(0.6, 1760.0, 0.55, Wave.SQUARE, 0.14)
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

    /** Klänge der Start-Animationen (Splash-Screen und Spielstart) und der Schluss des Spielverlaufs. */
    fun render(jingle: Jingle): ShortArray = when (jingle) {
        // Splash A „Hüpfer“: Maskottchen landet (0,48 s) und federt nach (0,87 / 1,15 s), dann Fanfare und Jubel.
        Jingle.SPLASH_HOP -> mix(4.2) {
            boing(0.46, 0.5, 0.5)
            boing(0.85, 0.32, 0.3)
            boing(1.13, 0.22, 0.2)
            kick(0.46, 0.6)
            showFanfare(1.4)
            cheer(1.5, 2.5)
            reverb()
        }
        // Splash B „Vorhang auf“: Trommelwirbel, Vorhang rauscht, Beckenschlag mit Glocken und Bläserakkord.
        Jingle.SPLASH_CURTAIN -> mix(4.2) {
            snareRoll(0.0, 1.05, 0.05, 0.5)
            swoosh(0.35, 0.9, 0.35, 500.0, 3500.0)
            cymbal(1.1, 2.4, 0.5)
            timpani(1.1, 0.7)
            for ((i, f) in listOf(784.0, 1047.0, 1319.0).withIndex()) bell(1.15 + i * 0.13, f, 1.8, 0.2)
            brassChord(1.15, listOf(60, 64, 67, 72), 1.6, 0.16)
            cheer(1.3, 2.7)
            reverb()
        }
        // Splash C „Hau drauf“: Luftzug, Klatsch der Hand, satter Quizshow-Buzzer, Beckenschlag und Jubel.
        Jingle.SPLASH_SLAP -> mix(4.2) {
            swoosh(0.5, 0.42, 0.4, 900.0, 5000.0)
            noise(0.9, 0.09, 1.0, 1800.0, 0.6)
            kick(0.9, 0.8)
            showBuzzer(0.92, 0.75, 0.32)
            cymbal(0.95, 1.8, 0.35)
            showFanfare(1.75)
            cheer(1.2, 2.8)
            reverb()
        }
        // Spielstart 1 „Die Fabel“: Pfiff, Hase hoppelt, schläft ein (Schnarchen), Fanfare zum „LOS!“ (2,2 s).
        Jingle.RACE_FABLE -> mix(4.2) {
            whistle(0.0, 0.5, 0.22)
            for (k in 0 until 6) pluck(0.15 + k * 0.17, midi(72 + listOf(0, 4, 7, 12, 7, 4)[k]), 0.18, 0.22)
            snore(1.3, 0.8)
            showFanfare(2.15)
            cymbal(2.6, 1.5, 0.3)
            reverb()
        }
        // Spielstart 2 „Verfolgungsjagd“: rasante Jagdmusik mit zwei Wuuusch, Bläserstoß zum „QUIZ!“ (2,4 s).
        Jingle.RACE_CHASE -> mix(4.2) {
            chaseRun(0.0, 2.35)
            swoosh(0.2, 0.9, 0.3, 400.0, 4000.0)
            swoosh(1.35, 0.9, 0.3, 4000.0, 400.0)
            brassChord(2.4, listOf(60, 64, 67, 72), 0.25, 0.18)
            brassChord(2.7, listOf(62, 65, 69, 74), 1.2, 0.18)
            cymbal(2.4, 1.6, 0.35)
            timpani(2.4, 0.6)
            reverb()
        }
        // Spielstart 3 „Startschuss“: „Auf die Plätze“ (0,1 s), „Fertig“ (0,85 s), Knall (1,6 s), Fanfare und Jubel.
        Jingle.RACE_START -> mix(4.2) {
            timpani(0.1, 0.45)
            timpani(0.85, 0.55)
            snareRoll(0.9, 0.7, 0.08, 0.35)
            gunshot(1.6)
            showFanfare(1.75)
            for (k in 0 until 8) noise(1.7 + k * 0.11, 0.05, 0.25, 300.0, 1.0)
            cheer(1.8, 2.3)
            reverb()
        }
        // Krönender Abschluss des Spielverlaufs: Wirbel, Beckenschlag und große Siegerfanfare.
        Jingle.FINALE -> mix(4.5) {
            snareRoll(0.0, 0.9, 0.05, 0.55)
            timpani(0.9, 0.9)
            cymbal(0.9, 3.0, 0.55)
            val notes = listOf(67, 72, 76, 79)
            notes.forEachIndexed { i, n -> brass(0.9 + i * 0.14, midi(n), if (i == 3) 0.35 else 0.12, 0.2) }
            brassChord(1.55, listOf(60, 64, 67, 72, 76), 2.3, 0.15)
            for ((i, f) in listOf(1047.0, 1319.0, 1568.0, 2093.0).withIndex()) bell(1.55 + i * 0.09, f, 2.2, 0.12)
            timpani(1.55, 0.8)
            cheer(1.6, 2.8)
            reverb(0.28)
        }
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

        /**
         * Posaune mit Wah-Dämpfer: zwei leicht verstimmte Sägezähne durch einen Tiefpass, dessen
         * Grenzfrequenz sich öffnet und wieder schließt („wa-ah“). [wobble]: langer Schlusston mit
         * Vibrato und mehrfachem Wah, am Ende nach [glideTo] abrutschend.
         */
        fun trombone(t: Double, freq: Double, dur: Double, vol: Double, wobble: Boolean = false, glideTo: Double? = null) {
            val start = (t * SAMPLE_RATE).toInt()
            val n = (dur * SAMPLE_RATE).toInt()
            var p1 = 0.0
            var p2 = 0.0
            var low = 0.0
            var band = 0.0
            for (k in 0 until n) {
                val idx = start + k
                if (idx >= buf.size) break
                val x = k.toDouble() / SAMPLE_RATE
                val rel = x / dur
                var f = freq
                if (glideTo != null && rel > 0.7) f = freq * (glideTo / freq).pow((rel - 0.7) / 0.3)
                if (wobble && x > 0.25) f *= 1 + 0.018 * sin(2 * PI * 5.5 * x)
                p1 += f / SAMPLE_RATE
                p2 += f * 1.006 / SAMPLE_RATE
                val raw = wave(Wave.SAW, p1) + 0.7 * wave(Wave.SAW, p2)
                // Wah: Tiefpass öffnet sich schnell und schließt langsam; beim Schlusston mehrfach.
                val wah = if (wobble) {
                    0.45 + 0.55 * (0.5 - 0.5 * cos(2 * PI * 2.4 * x)) * (1 - rel * 0.6)
                } else {
                    if (x < 0.07) x / 0.07 else (1 - (x - 0.07) / (dur - 0.07)).coerceAtLeast(0.0)
                }
                val cutoff = 220 + 1700 * wah
                val fc = 2 * sin(PI * cutoff / SAMPLE_RATE)
                low += fc * band
                val high = raw - low - 0.9 * band
                band += fc * high
                val env = when {
                    x < 0.025 -> x / 0.025
                    x > dur - 0.12 -> ((dur - x) / 0.12).coerceAtLeast(0.0)
                    else -> 1.0
                }
                buf[idx] += low * vol * env * 0.6
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

        // ------------------------------------------------------------ Instrumente der Jingles

        /** Blechbläser: Sägezähne leicht verstimmt, Tiefpass öffnet beim Anblasen, sanftes Ausklingen. */
        fun brass(t: Double, freq: Double, dur: Double, vol: Double) {
            val start = (t * SAMPLE_RATE).toInt()
            val n = ((dur + 0.15) * SAMPLE_RATE).toInt()
            var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
            var low = 0.0; var band = 0.0
            for (k in 0 until n) {
                val idx = start + k
                if (idx >= buf.size) break
                val x = k.toDouble() / SAMPLE_RATE
                val vib = 1 + if (x > 0.15) 0.004 * sin(2 * PI * 5.0 * x) else 0.0
                p1 += freq * vib / SAMPLE_RATE
                p2 += freq * vib * 1.004 / SAMPLE_RATE
                p3 += freq * vib * 0.996 / SAMPLE_RATE
                val raw = wave(Wave.SAW, p1) + wave(Wave.SAW, p2) * 0.8 + wave(Wave.SAW, p3) * 0.8
                val open = if (x < 0.06) x / 0.06 else 1.0 - 0.35 * ((x - 0.06) / dur).coerceAtMost(1.0)
                val cutoff = 300 + freq * 5 * open
                val fc = 2 * sin(PI * cutoff.coerceAtMost(8000.0) / SAMPLE_RATE)
                low += fc * band
                val high = raw - low - 1.1 * band
                band += fc * high
                val env = when {
                    x < 0.03 -> x / 0.03
                    x > dur -> ((dur + 0.15 - x) / 0.15).coerceAtLeast(0.0)
                    else -> 1.0 - 0.2 * (x / dur)
                }
                buf[idx] += low * vol * env * 0.5
            }
        }

        fun brassChord(t: Double, notes: List<Int>, dur: Double, vol: Double) =
            notes.forEach { brass(t, midi(it), dur, vol) }

        /** Glocke: unharmonische Teiltöne, die verschieden schnell abklingen. */
        fun bell(t: Double, freq: Double, dur: Double, vol: Double) {
            listOf(1.0 to 1.0, 2.76 to 0.5, 5.4 to 0.25, 8.93 to 0.12).forEach { (ratio, amp) ->
                tone(t, freq * ratio, dur / ratio.coerceAtMost(3.0), Wave.SINE, vol * amp)
            }
        }

        /** Gezupfter Ton (Marimba-artig) für hüpfende Melodien. */
        fun pluck(t: Double, freq: Double, dur: Double, vol: Double) {
            tone(t, freq, dur, Wave.SINE, vol)
            tone(t, freq * 4, dur * 0.3, Wave.SINE, vol * 0.25)
            tone(t, freq * 2, dur * 0.6, Wave.TRIANGLE, vol * 0.3)
        }

        /** Becken: helles Rauschen mit langem Ausklang. */
        fun cymbal(t: Double, dur: Double, vol: Double) {
            noise(t, dur, vol, 7500.0, 0.6)
            noise(t, dur * 0.6, vol * 0.6, 4200.0, 0.8)
            noise(t, 0.08, vol, 2500.0, 0.5)
        }

        /** Pauke: tiefer Ton, der leicht absinkt, mit Fellanschlag. */
        fun timpani(t: Double, vol: Double) {
            tone(t, 98.0, 1.2, Wave.SINE, vol * 0.7, freqEnd = 88.0)
            tone(t, 147.0, 0.6, Wave.SINE, vol * 0.25, freqEnd = 135.0)
            noise(t, 0.05, vol * 0.5, 400.0, 0.8)
        }

        fun kick(t: Double, vol: Double) {
            tone(t, 140.0, 0.35, Wave.SINE, vol, freqEnd = 45.0)
            noise(t, 0.02, vol * 0.5, 3000.0, 0.7)
        }

        /** Trommelwirbel auf der kleinen Trommel, anschwellend. */
        fun snareRoll(t: Double, dur: Double, step: Double, vol: Double) {
            var x = 0.0
            while (x < dur) {
                val v = vol * (0.35 + 0.65 * x / dur)
                noise(t + x, 0.05, v, 2200.0, 0.6)
                noise(t + x, 0.03, v * 0.6, 220.0, 1.0)
                x += step
            }
        }

        /** Rauschen, dessen Tonhöhe gleitet: Luftzug, Vorhang, „Wuuusch“. */
        fun swoosh(t: Double, dur: Double, vol: Double, from: Double, to: Double) {
            val slices = 24
            for (i in 0 until slices) {
                val r = i.toDouble() / slices
                val f = from * (to / from).pow(r)
                val v = vol * sin(PI * (r + 0.5 / slices))
                noise(t + r * dur, dur / slices * 2.2, v.coerceAtLeast(0.01), f, 1.2)
            }
        }

        /** Schiedsrichterpfiff: hoher Ton, schnell „trillernd“ (Kugel in der Pfeife). */
        fun whistle(t: Double, dur: Double, vol: Double) {
            val start = (t * SAMPLE_RATE).toInt()
            var phase = 0.0
            for (k in 0 until (dur * SAMPLE_RATE).toInt()) {
                val idx = start + k
                if (idx >= buf.size) break
                val x = k.toDouble() / SAMPLE_RATE
                phase += (2850 + 60 * sin(2 * PI * 28 * x)) / SAMPLE_RATE
                val trill = 0.55 + 0.45 * sin(2 * PI * 28 * x)
                val env = when {
                    x < 0.02 -> x / 0.02
                    x > dur - 0.05 -> ((dur - x) / 0.05).coerceAtLeast(0.0)
                    else -> 1.0
                }
                buf[idx] += sin(2 * PI * phase) * vol * trill * env
            }
            noise(t, dur, vol * 0.15, 2850.0, 3.0)
        }

        /** „Boing“ einer Feder: Ton springt hoch und zittert. */
        fun boing(t: Double, dur: Double, vol: Double) {
            val start = (t * SAMPLE_RATE).toInt()
            var phase = 0.0
            for (k in 0 until (dur * SAMPLE_RATE).toInt()) {
                val idx = start + k
                if (idx >= buf.size) break
                val x = k.toDouble() / SAMPLE_RATE
                val f = 180 + 420 * (1 - (1 - x / dur).pow(3)) * (1 + 0.12 * sin(2 * PI * 18 * x))
                phase += f / SAMPLE_RATE
                buf[idx] += (wave(Wave.SINE, phase) + 0.3 * wave(Wave.TRIANGLE, phase * 2)) * vol * (1 - x / dur)
            }
        }

        /** Quizshow-Buzzer: zwei verstimmte Rechtecke, gedämpft. */
        fun showBuzzer(t: Double, dur: Double, vol: Double) {
            tone(t, 98.0, dur, Wave.SQUARE, vol * 0.6)
            tone(t, 147.5, dur, Wave.SAW, vol * 0.5)
            tone(t, 196.8, dur, Wave.SQUARE, vol * 0.25)
        }

        /** Kurze Showfanfare „Ta-ta-taaa“ mit Schlussakkord. */
        fun showFanfare(t: Double) {
            brass(t, midi(67), 0.1, 0.22)
            brass(t + 0.13, midi(67), 0.1, 0.22)
            brassChord(t + 0.26, listOf(60, 64, 67, 72), 1.1, 0.16)
            timpani(t + 0.26, 0.5)
        }

        /** Jubelnde Menge: Klatschen plus „Uuuh“-Rauschen in Stimmlage. */
        fun cheer(t: Double, dur: Double) {
            applause(t, dur)
            noise(t, dur, 0.18, 700.0, 1.5)
            noise(t + 0.1, dur * 0.8, 0.12, 1100.0, 2.0)
        }

        fun snore(t: Double, dur: Double) {
            noise(t, dur, 0.18, 260.0, 4.0)
            tone(t, 90.0, dur, Wave.SAW, 0.06, freqEnd = 70.0)
        }

        fun gunshot(t: Double) {
            noise(t, 0.04, 1.0, 3000.0, 0.4)
            noise(t, 0.6, 0.8, 500.0, 0.5)
            tone(t, 90.0, 0.6, Wave.SINE, 0.7, freqEnd = 40.0)
        }

        /** Kurzer Jagd-Lauf (wie [chase], aber für die Rennszene). */
        fun chaseRun(t: Double, dur: Double) {
            val step = 60.0 / 176 / 4
            val run = intArrayOf(0, 2, 4, 5, 7, 5, 4, 2)
            var x = 0.0
            var i = 0
            while (x < dur) {
                if (i % 2 == 0) tone(t + x, midi(45 + if ((i / 4) % 2 == 0) 0 else 7), step * 1.8, Wave.SQUARE, 0.14)
                pluck(t + x, midi(72 + run[i % run.size] + (i / 16) * 2), step * 1.2, 0.14)
                if (i % 2 == 1) noise(t + x, 0.03, 0.2, 7000.0, 1.0)
                i++
                x += step
            }
        }

        /** Einfacher Hall (vier parallele Kammfilter), macht die Jingles räumlicher. */
        fun reverb(amount: Double = 0.22) {
            val dry = buf.copyOf()
            for ((delayMs, fb) in listOf(29.7 to 0.72, 37.1 to 0.70, 41.1 to 0.68, 43.7 to 0.66)) {
                val d = (delayMs / 1000 * SAMPLE_RATE).toInt()
                val comb = DoubleArray(buf.size)
                for (k in d until buf.size) comb[k] = dry[k - d] + fb * comb[k - d]
                for (k in buf.indices) buf[k] += comb[k] * amount / 4
            }
        }

        fun toPcm(): ShortArray {
            val peak = buf.maxOfOrNull { abs(it) } ?: 0.0
            val scale = if (peak > 0.9) 0.9 / peak else 1.0
            return ShortArray(buf.size) { (buf[it] * scale * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
        }
    }
}
