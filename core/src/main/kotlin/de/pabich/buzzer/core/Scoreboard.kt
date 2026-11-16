package de.pabich.buzzer.core

data class ScoreEntry(
    val rank: Int,
    val label: String,
    val score: Long,
    val scoreText: String,
    val isSelf: Boolean,
    /** Tiebreaker-Wert, nur bei Punktgleichstand gesetzt: Zeitsumme bzw. Anzahl richtiger Antworten. */
    val tiebreakText: String? = null,
)

data class ScoreInput(
    val id: PlayerId,
    val name: String,
    val score: Long,
    val avatar: String = "",
    /** Summe der Lösungszeiten über das ganze Spiel (Tiebreaker ohne Zeitwertung). */
    val totalReactionMs: Long = 0,
    /** Anzahl richtiger Antworten über das ganze Spiel (Tiebreaker mit Zeitwertung). */
    val correctCount: Long = 0,
)

/**
 * Rangliste aus Sicht von [viewer]: gleiche Punkte = gleicher Rang, außer der Tiebreaker entscheidet
 * (ohne Zeitwertung die kleinere Zeitsumme, mit Zeitwertung die höhere Anzahl richtiger Antworten) – der
 * Tiebreaker-Wert erscheint dann auch als Text, sonst nicht. Sichtbar sind die ersten
 * [Settings.boardPlaces] Plätze (0 = alle) und immer der eigene Eintrag. Ohne
 * [Settings.boardShowNames] erscheinen fremde Namen als „???“.
 */
fun buildScoreboard(players: List<ScoreInput>, viewer: PlayerId?, settings: Settings): List<ScoreEntry> {
    val timeScoring = settings.scoring == ScoringMode.REMAINING_TIME
    // Kleinerer Wert ist jeweils besser: ohne Zeitwertung die Zeitsumme, mit Zeitwertung die negierte Anzahl Richtiger.
    fun tiebreak(p: ScoreInput): Long = if (timeScoring) -p.correctCount else p.totalReactionMs
    val sorted = players.sortedWith(
        compareByDescending<ScoreInput> { it.score }.thenBy { tiebreak(it) }.thenBy { it.name.lowercase() },
    )
    return sorted.mapNotNull { p ->
        val rank = 1 + sorted.count { it.score > p.score || (it.score == p.score && tiebreak(it) < tiebreak(p)) }
        val self = p.id == viewer
        val visible = settings.boardPlaces == 0 || rank <= settings.boardPlaces
        if (!visible && !self) return@mapNotNull null
        val label = when {
            self -> withAvatar(p.avatar, "Du")
            settings.boardShowNames -> withAvatar(p.avatar, p.name)
            else -> "???"
        }
        val tied = players.count { it.score == p.score } > 1
        val tiebreakText = if (!tied) null else if (timeScoring) {
            "${p.correctCount}× richtig"
        } else {
            formatScore(p.totalReactionMs, ScoringMode.REMAINING_TIME)
        }
        ScoreEntry(rank, label, p.score, formatScore(p.score, settings.scoring), self, tiebreakText)
    }
}

/** Name mit dem Emoticon, das der Spieler als Avatar gewählt hat (leer = ohne). */
fun withAvatar(avatar: String, name: String): String = if (avatar.isEmpty()) name else "$avatar $name"
