package de.pabich.buzzer.core

data class ScoreEntry(
    val rank: Int,
    val label: String,
    val score: Long,
    val scoreText: String,
    val isSelf: Boolean,
)

data class ScoreInput(val id: PlayerId, val name: String, val score: Long, val avatar: String = "")

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
            self -> withAvatar(p.avatar, "Du")
            settings.boardShowNames -> withAvatar(p.avatar, p.name)
            else -> "???"
        }
        ScoreEntry(rank, label, p.score, formatScore(p.score, settings.scoring), self)
    }
}

/** Name mit dem Emoticon, das der Spieler als Avatar gewählt hat (leer = ohne). */
fun withAvatar(avatar: String, name: String): String = if (avatar.isEmpty()) name else "$avatar $name"
