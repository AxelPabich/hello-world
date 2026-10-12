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

val DEFAULT_EMOJIS = listOf("👍", "😂", "😮", "🤔", "😅", "😎", "🙈")
const val EMOJI_COUNT = 7

data class Settings(
    val startMode: StartMode = StartMode.TAP,
    /** Maximale Reaktionszeit; wirkt nur bei [reactionLimitEnabled]. */
    val timeLimitMs: Long = 10_000,
    val reactionLimitEnabled: Boolean = true,
    val answerLimitEnabled: Boolean = false,
    val answerLimitMs: Long = 15_000,
    /** Nach abgelaufener Antwortzeit automatisch zum Nächsten, sonst wartet der Master („Weiter“). */
    val autoAdvanceOnTimeout: Boolean = true,
    val delayMinMs: Long = 1_000,
    val delayMaxMs: Long = 4_000,
    val suddenDeathDelayMinMs: Long = 1_000,
    val suddenDeathDelayMaxMs: Long = 6_000,
    val tieThresholdMs: Long = 20,
    val scoring: ScoringMode = ScoringMode.CORRECT_ANSWERS,
    val masterPlays: Boolean = false,
    /** Namen in der Rangliste der Spieler anzeigen. */
    val boardShowNames: Boolean = true,
    /** Wie viele Plätze (ab Platz 1) die Spieler sehen; 0 = alle. Den eigenen Platz sieht jeder. */
    val boardPlaces: Int = 0,
    val messagesEnabled: Boolean = true,
    val emojisEnabled: Boolean = true,
    val emojis: List<String> = DEFAULT_EMOJIS,
) {
    /** Reaktionszeit-Limit oder `null`, wenn ausgeschaltet. */
    val reactionLimitMs: Long? get() = if (reactionLimitEnabled) timeLimitMs else null

    /** Antwortzeit-Limit oder `null`, wenn ausgeschaltet. */
    val answerLimit: Long? get() = if (answerLimitEnabled) answerLimitMs else null

    fun normalized(): Settings {
        val dMin = delayMinMs.coerceIn(0, 30_000)
        val sdMin = suddenDeathDelayMinMs.coerceIn(0, 30_000)
        val cleanEmojis = emojis.map { it.trim() }.filter { it.isNotEmpty() }.take(EMOJI_COUNT)
        return copy(
            timeLimitMs = timeLimitMs.coerceIn(1_000, 120_000),
            // Die Restzeit-Wertung braucht ein Reaktionszeit-Limit.
            reactionLimitEnabled = reactionLimitEnabled || scoring == ScoringMode.REMAINING_TIME,
            answerLimitMs = answerLimitMs.coerceIn(3_000, 300_000),
            delayMinMs = dMin,
            delayMaxMs = delayMaxMs.coerceIn(dMin, 30_000),
            suddenDeathDelayMinMs = sdMin,
            suddenDeathDelayMaxMs = suddenDeathDelayMaxMs.coerceIn(sdMin, 30_000),
            tieThresholdMs = tieThresholdMs.coerceIn(0, 1_000),
            boardPlaces = boardPlaces.coerceIn(0, 99),
            emojis = cleanEmojis + DEFAULT_EMOJIS.drop(cleanEmojis.size),
        )
    }
}

fun formatScore(score: Long, scoring: ScoringMode): String = when (scoring) {
    ScoringMode.CORRECT_ANSWERS -> score.toString()
    ScoringMode.REMAINING_TIME -> String.format(Locale.GERMANY, "%.2f s", score / 1000.0)
}
