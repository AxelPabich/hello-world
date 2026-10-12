package de.pabich.buzzer.core

data class ScoreEntry(
    val rank: Int,
    val label: String,
    val score: Long,
    val scoreText: String,
    val isSelf: Boolean,
)

data class ScoreInput(val id: PlayerId, val name: String, val score: Long)

/**
 * Rangliste aus Sicht von [viewer]: gleiche Punkte = gleicher Rang. Sichtbar sind die ersten
 * [Settings.boardPlaces] Plätze (0 = alle) und immer der eigene Eintrag. Ohne
 * [Settings.boardShowNames] erscheinen fremde Namen als „???“.
 */
fun buildScoreboard(players: List<ScoreInput>, viewer: PlayerId?, settings: Settings): List<ScoreEntry> {
    val sorted = players.sortedWith(compareByDescending<ScoreInput> { it.score }.thenBy { it.name.lowercase() })
    return sorted.mapNotNull { p ->
        val rank = 1 + sorted.count { it.score > p.score }
        val self = p.id == viewer
        val visible = settings.boardPlaces == 0 || rank <= settings.boardPlaces
        if (!visible && !self) return@mapNotNull null
        val label = when {
            self -> "Du"
            settings.boardShowNames -> p.name
            else -> "???"
        }
        ScoreEntry(rank, label, p.score, formatScore(p.score, settings.scoring), self)
    }
}
