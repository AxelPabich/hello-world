package de.pabich.buzzer.core

import java.util.Locale

typealias PlayerId = String

enum class StartMode {
    /** Modus A: alle legen den Finger auf, es zählt das Loslassen. */
    HOLD_RELEASE,

    /** Modus B: es zählt das Antippen. */
    TAP,
}

enum class ScoringMode {
    CORRECT_ANSWERS,
    REMAINING_TIME,
}

/** Farbe des Spieler-Displays (Spezifikation, Abschnitt 2). */
enum class Display {
    BLACK,
    BLUE,
    YELLOW,

    /** Gedrückt, Auswertung läuft (gedimmtes Gelb). */
    PRESSED,
    GREEN,
    RED,
    PINK,
    GRAY,
}

data class Settings(
    val startMode: StartMode = StartMode.TAP,
    val timeLimitMs: Long = 10_000,
    val delayMinMs: Long = 1_000,
    val delayMaxMs: Long = 4_000,
    val suddenDeathDelayMinMs: Long = 1_000,
    val suddenDeathDelayMaxMs: Long = 6_000,
    val tieThresholdMs: Long = 20,
    val scoring: ScoringMode = ScoringMode.CORRECT_ANSWERS,
    val masterPlays: Boolean = false,
) {
    fun normalized(): Settings = copy(
        timeLimitMs = timeLimitMs.coerceIn(1_000, 120_000),
        delayMinMs = delayMinMs.coerceIn(0, 30_000),
        delayMaxMs = delayMaxMs.coerceIn(delayMinMs.coerceIn(0, 30_000), 30_000),
        suddenDeathDelayMinMs = suddenDeathDelayMinMs.coerceIn(0, 30_000),
        suddenDeathDelayMaxMs = suddenDeathDelayMaxMs.coerceIn(suddenDeathDelayMinMs.coerceIn(0, 30_000), 30_000),
        tieThresholdMs = tieThresholdMs.coerceIn(0, 1_000),
    )
}

fun formatScore(score: Long, scoring: ScoringMode): String = when (scoring) {
    ScoringMode.CORRECT_ANSWERS -> score.toString()
    ScoringMode.REMAINING_TIME -> String.format(Locale.GERMANY, "%.2f s", score / 1000.0)
}
