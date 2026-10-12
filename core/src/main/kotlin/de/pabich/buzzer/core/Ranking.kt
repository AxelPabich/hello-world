package de.pabich.buzzer.core

/**
 * Sortiert nach Reaktionszeit und fasst Gleichstände zusammen: Ein Eintrag gehört zur Gruppe,
 * wenn er höchstens [tieThresholdMs] hinter dem ersten Eintrag der Gruppe liegt.
 */
fun <T> groupByReaction(entries: List<Pair<T, Long>>, tieThresholdMs: Long): List<List<T>> {
    val groups = mutableListOf<MutableList<Pair<T, Long>>>()
    for (entry in entries.sortedBy { it.second }) {
        val current = groups.lastOrNull()
        if (current != null && entry.second - current.first().second <= tieThresholdMs) {
            current += entry
        } else {
            groups += mutableListOf(entry)
        }
    }
    return groups.map { group -> group.map { it.first } }
}
