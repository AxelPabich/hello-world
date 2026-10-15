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

/** Klangereignisse im Spiel. Wo ein Ton erklingt, legt die App fest (siehe Spezifikation). */
enum class SoundEvent(val label: String, val defaultOn: Boolean = true) {
    JOIN("Spieler tritt bei – Ding"),
    HUM("Buzzer scharf – elektrisches Summen"),
    BUZZ("Eigener Buzz – Buzzer"),
    FIRST("Du bist dran – Fanfare"),
    RED("Nicht Erster – Bonk"),
    FALSE_START("Frühstart – Tröte"),
    SUDDEN_DEATH("Gleichstand – Trommelwirbel"),
    TICK("Antwortzeit – tickende Uhr"),
    TIMEOUT("Zeit abgelaufen – Gong"),
    RIGHT("Richtig – Ding Ding + Applaus"),
    WRONG("Falsch – Wah-wah-wah"),
    TUSCH("Quiz richtig – Tusch"),
    GO("Alle bereit – Go!"),
    ROUND_END("Rundenende – Trommelwirbel + Tusch"),
    EMOTE("Emoticon – Plopp", defaultOn = false),
    CHAT("Kurznachricht – Pling"),
    CHASE("Spielverlauf – Verfolgungsjagd"),
}

/** Klänge der Start-Animationen; sie folgen dem Lautsprecher-Schalter der App, nicht dem Profil. */
enum class Jingle { SPLASH_HOP, SPLASH_CURTAIN, SPLASH_SLAP, RACE_FABLE, RACE_CHASE, RACE_START }

val DEFAULT_SOUNDS: Set<SoundEvent> = SoundEvent.entries.filter { it.defaultOn }.toSet()
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
    /** Eingeschaltete Klänge dieses Profils. */
    val sounds: Set<SoundEvent> = DEFAULT_SOUNDS,
    /** Quiz: gewählte Kategorien, leer = alle. */
    val quizCategories: Set<String> = emptySet(),
    /** Quiz: gewählte Stufen (nach gemessener Schwierigkeit). */
    val quizLevels: Set<QuizLevel> = QuizLevel.entries.toSet(),
    val quizCount: Int = 12,
    /** Quiz: Antwortzeit je Frage. */
    val quizAnswerMs: Long = 20_000,
    val quizScoring: QuizScoring = QuizScoring.FASTEST,
    /** Quiz, Wertung „Countdown“: so lange läuft die Zeit nach der ersten richtigen Antwort noch. */
    val quizCountdownMs: Long = 5_000,
    /** Bereit-Abfrage automatisch so lange nach der Auflösung; 0 = aus. */
    val quizAutoReadyMs: Long = 0,
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
            quizLevels = quizLevels.ifEmpty { QuizLevel.entries.toSet() },
            quizCount = quizCount.coerceIn(1, 50),
            quizAnswerMs = quizAnswerMs.coerceIn(5_000, 120_000),
            // Höchstens die halbe Antwortzeit – sonst greift der Countdown nie.
            quizCountdownMs = quizCountdownMs.coerceIn(1_000, maxCountdownMs(quizAnswerMs.coerceIn(5_000, 120_000))),
            quizAutoReadyMs = if (quizAutoReadyMs <= 0) 0 else quizAutoReadyMs.coerceIn(3_000, 120_000),
        )
    }
}

/** Längster sinnvoller Countdown: die halbe Antwortzeit (auf volle Sekunden). */
fun maxCountdownMs(answerMs: Long): Long = (answerMs / 2 / 1_000 * 1_000).coerceIn(1_000, 30_000)

fun formatScore(score: Long, scoring: ScoringMode): String = when (scoring) {
    ScoringMode.CORRECT_ANSWERS -> score.toString()
    ScoringMode.REMAINING_TIME -> String.format(Locale.GERMANY, "%.2f s", score / 1000.0)
}
