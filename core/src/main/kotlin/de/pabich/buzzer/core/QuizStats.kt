package de.pabich.buzzer.core

import kotlin.random.Random

/** Spielstatistik einer Frage – lokal auf dem Master-Gerät, getrennt vom Fragenpaket. */
data class QuestionStats(
    /** Version der Frage, zu der die Antwortzahlen gehören. */
    val version: Int = 1,
    val played: Int = 0,
    /** Zuletzt gespielt (Epoch-ms), 0 = nie. */
    val lastPlayed: Long = 0,
    /** Namen der Spieler, die die Frage schon gesehen haben. */
    val seenBy: Set<String> = emptySet(),
    /** Teilnehmer insgesamt (richtig + falsch + keine Antwort). */
    val answers: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val none: Int = 0,
    /** Summe (Antwortzeit / Timer) der richtigen Antworten – Mittelwert = Summe / [correct]. */
    val correctTimeShare: Double = 0.0,
    val challenges: Int = 0,
    val annulled: Int = 0,
    val blocked: Boolean = false,
    /** Vom Master korrigierte richtige Antwort (Index in der Paket-Reihenfolge). */
    val correctOverride: Int? = null,
    /** Challenges und Entscheidungen im Klartext – für die Auswertung durch Claude. */
    val notes: List<String> = emptyList(),
)

/** Ergebnis eines Teilnehmers für die Statistik: `true`/`false` = richtig/falsch, `null` = keine Antwort. */
data class ParticipantResult(val name: String, val correct: Boolean?, val timeShare: Double)

enum class ChallengeDecision(val label: String) {
    KEEP("Wertung bleibt"),
    ANNUL("Frage annullieren"),
    CORRECT("Antwort korrigieren"),
    BLOCK("Frage sperren"),
    /** Frage annullieren und sofort durch eine andere ersetzen. */
    REPLACE("Ersatzfrage"),
}

object Difficulty {
    /** Ab so vielen Antworten ersetzt die gemessene Stufe die des Autors. */
    const val MIN_ANSWERS = 6

    fun startValue(level: QuizLevel) = when (level) {
        QuizLevel.EASY -> 0.8
        QuizLevel.MEDIUM -> 0.55
        QuizLevel.HARD -> 0.3
    }

    /** Geglätteter Anteil richtiger Antworten. */
    fun quote(stats: QuestionStats, author: QuizLevel): Double =
        (stats.correct + 2 * startValue(author)) / (stats.answers + 2)

    fun measured(stats: QuestionStats, author: QuizLevel): QuizLevel? {
        if (stats.answers < MIN_ANSWERS) return null
        val q = quote(stats, author)
        var level = when {
            q >= 0.7 -> QuizLevel.EASY
            q >= 0.4 -> QuizLevel.MEDIUM
            else -> QuizLevel.HARD
        }
        // Brauchten auch die Richtigen lange, ist die Frage eine Stufe schwerer.
        if (stats.correct > 0 && stats.correctTimeShare / stats.correct > 0.6 && level != QuizLevel.HARD) {
            level = QuizLevel.entries[level.ordinal + 1]
        }
        return level
    }

    fun effective(stats: QuestionStats?, author: QuizLevel): QuizLevel =
        stats?.let { measured(it, author) } ?: author
}

/** Alle Statistiken; unveränderlich, jede Änderung liefert ein neues Buch. */
data class StatsBook(val entries: Map<String, QuestionStats> = emptyMap()) {
    /** Statistik passend zur Version der Frage: neue Version → Antwortzahlen beginnen neu. */
    fun of(q: Question): QuestionStats {
        val s = entries[q.id] ?: return QuestionStats(version = q.version)
        if (s.version == q.version) return s
        return s.copy(
            version = q.version, answers = 0, correct = 0, wrong = 0, none = 0, correctTimeShare = 0.0,
            blocked = false, correctOverride = null,
        )
    }

    private fun with(q: Question, change: (QuestionStats) -> QuestionStats) = copy(entries = entries + (q.id to change(of(q))))

    fun recordResult(q: Question, results: List<ParticipantResult>, now: Long) = with(q) { s ->
        s.copy(
            played = s.played + 1,
            lastPlayed = now,
            seenBy = s.seenBy + results.map { it.name },
            answers = s.answers + results.size,
            correct = s.correct + results.count { it.correct == true },
            wrong = s.wrong + results.count { it.correct == false },
            none = s.none + results.count { it.correct == null },
            correctTimeShare = s.correctTimeShare + results.filter { it.correct == true }.sumOf { it.timeShare.coerceIn(0.0, 1.0) },
        )
    }

    /**
     * Nach einer Challenge-Entscheidung: die bei der Auflösung gezählte Bewertung [before] wieder
     * herausnehmen und – falls die Frage noch zählt – die neue [after] eintragen. Annulliert,
     * gesperrt oder ersetzt: [after] = `null`, die Antworten zählen dann nicht.
     */
    fun revise(q: Question, before: List<ParticipantResult>?, after: List<ParticipantResult>?) = with(q) { s ->
        fun List<ParticipantResult>?.n(p: (ParticipantResult) -> Boolean): Int = this?.count(p) ?: 0
        fun List<ParticipantResult>?.share(): Double = this?.filter { it.correct == true }?.sumOf { it.timeShare.coerceIn(0.0, 1.0) } ?: 0.0
        s.copy(
            answers = (s.answers - (before?.size ?: 0) + (after?.size ?: 0)).coerceAtLeast(0),
            correct = (s.correct - before.n { it.correct == true } + after.n { it.correct == true }).coerceAtLeast(0),
            wrong = (s.wrong - before.n { it.correct == false } + after.n { it.correct == false }).coerceAtLeast(0),
            none = (s.none - before.n { it.correct == null } + after.n { it.correct == null }).coerceAtLeast(0),
            correctTimeShare = (s.correctTimeShare - before.share() + after.share()).coerceAtLeast(0.0),
        )
    }

    /** Übersprungen: gesehen, aber nicht gewertet. */
    fun recordSeen(q: Question, names: Collection<String>, now: Long) = with(q) { s ->
        s.copy(lastPlayed = now, seenBy = s.seenBy + names)
    }

    fun recordChallenge(q: Question, from: String, reason: String) = with(q) { s ->
        s.copy(challenges = s.challenges + 1, notes = (s.notes + "Challenge von $from: ${reason.ifBlank { "(ohne Begründung)" }}").takeLast(MAX_NOTES))
    }

    fun recordDecision(q: Question, decision: ChallengeDecision, newCorrect: Int?) = with(q) { s ->
        val note = "Entscheidung: ${decision.label}" + if (newCorrect != null) " → „${q.answers.getOrNull(newCorrect)}“" else ""
        when (decision) {
            ChallengeDecision.KEEP -> s.copy(notes = (s.notes + note).takeLast(MAX_NOTES))
            ChallengeDecision.ANNUL, ChallengeDecision.REPLACE -> s.copy(annulled = s.annulled + 1, notes = (s.notes + note).takeLast(MAX_NOTES))
            ChallengeDecision.BLOCK -> s.copy(annulled = s.annulled + 1, blocked = true, notes = (s.notes + note).takeLast(MAX_NOTES))
            ChallengeDecision.CORRECT -> s.copy(correctOverride = newCorrect, notes = (s.notes + note).takeLast(MAX_NOTES))
        }
    }

    // ------------------------------------------------------------------ Datei (.buzzstats)

    /** [test]: Statistik aus Testspielen – wird beim Auswerten getrennt gehalten. */
    fun toJson(questions: Map<String, Question> = emptyMap(), test: Boolean = false): String = MiniJson.write(
        linkedMapOf(
            "format" to "buzzstats",
            "version" to 1L,
            "test" to test,
            "fragen" to entries.toSortedMap().map { (id, s) ->
                val q = questions[id]
                linkedMapOf<String, Any?>(
                    "id" to id,
                    "version" to s.version.toLong(),
                    "gespielt" to s.played.toLong(),
                    "zuletzt" to s.lastPlayed,
                    "gesehenVon" to s.seenBy.sorted(),
                    "antworten" to s.answers.toLong(),
                    "richtig" to s.correct.toLong(),
                    "falsch" to s.wrong.toLong(),
                    "keine" to s.none.toLong(),
                    "zeitanteilRichtig" to s.correctTimeShare,
                    "challenges" to s.challenges.toLong(),
                    "annulliert" to s.annulled.toLong(),
                    "gesperrt" to s.blocked,
                    "richtigeAntwort" to s.correctOverride?.toLong(),
                    "notizen" to s.notes,
                ).also { m ->
                    // Für die Auswertung lesbar: Frage und Stufen mitschreiben.
                    if (q != null) {
                        m["frage"] = q.text
                        m["stufeAutor"] = q.level.key
                        m["stufeGemessen"] = Difficulty.measured(s, q.level)?.key
                    }
                }
            },
        ),
    )

    companion object {
        private const val MAX_NOTES = 20

        fun fromJson(text: String): StatsBook {
            val root = MiniJson.parse(text) as? Map<*, *> ?: return StatsBook()
            val list = root["fragen"] as? List<*> ?: return StatsBook()
            fun Map<*, *>.int(k: String) = (this[k] as? Number)?.toInt() ?: 0
            val entries = list.mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                id to QuestionStats(
                    version = m.int("version").coerceAtLeast(1),
                    played = m.int("gespielt"),
                    lastPlayed = (m["zuletzt"] as? Number)?.toLong() ?: 0,
                    seenBy = (m["gesehenVon"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
                    answers = m.int("antworten"),
                    correct = m.int("richtig"),
                    wrong = m.int("falsch"),
                    none = m.int("keine"),
                    correctTimeShare = (m["zeitanteilRichtig"] as? Number)?.toDouble() ?: 0.0,
                    challenges = m.int("challenges"),
                    annulled = m.int("annulliert"),
                    blocked = m["gesperrt"] == true,
                    correctOverride = (m["richtigeAntwort"] as? Number)?.toInt(),
                    notes = (m["notizen"] as? List<*>).orEmpty().filterIsInstance<String>(),
                )
            }.toMap()
            return StatsBook(entries)
        }
    }
}

/** Wählt die Fragen eines Quiz: ungespielte zuerst, Kategorien abwechselnd. */
object QuestionPicker {
    data class Request(
        /** Leer = alle Kategorien. */
        val categories: Set<String>,
        /** Leer = alle Stufen. */
        val levels: Set<QuizLevel>,
        val count: Int,
        /** Namen der aktuell verbundenen Spieler. */
        val players: Set<String>,
        /** Heute als `JJJJ-MM-TT` – abgelaufene Fragen fallen weg. */
        val today: String,
    )

    data class Result(
        val questions: List<Question>,
        /** Fragen, die zu Kategorien und Stufen passen. */
        val matching: Int,
        /** Davon hat noch keiner der aktuellen Spieler gesehen. */
        val fresh: Int,
    )

    fun pick(all: List<Question>, book: StatsBook, request: Request, random: Random = Random.Default): Result {
        val candidates = all.filter { q ->
            val s = book.of(q)
            !s.blocked &&
                (q.validUntil == null || q.validUntil >= request.today) &&
                (request.categories.isEmpty() || q.category in request.categories) &&
                (request.levels.isEmpty() || Difficulty.effective(s, q.level) in request.levels)
        }
        fun seen(q: Question) = book.of(q).seenBy.count { it in request.players }
        // Zufall zuerst, dann stabil sortieren: bei Gleichstand entscheidet der Zufall.
        val sorted = candidates.shuffled(random).sortedWith(compareBy({ seen(it) }, { book.of(it).lastPlayed }))
        val remaining = sorted.toMutableList()
        val used = mutableMapOf<String, Int>()
        val chosen = mutableListOf<Question>()
        var lastCategory: String? = null
        while (chosen.size < request.count && remaining.isNotEmpty()) {
            val first = remaining.first()
            val bestSeen = seen(first)
            // Unter den gleich „neuen“ Fragen: am wenigsten genutzte Kategorie, nicht zweimal hintereinander.
            // Bereits gesehene Fragen kommen dagegen streng nach Alter dran (am längsten nicht gespielt zuerst).
            val pool = remaining.filter { seen(it) == bestSeen && (bestSeen == 0 || book.of(it).lastPlayed == book.of(first).lastPlayed) }
            val next = pool.minWithOrNull(
                compareBy<Question>({ if (it.category == lastCategory && pool.any { p -> p.category != lastCategory }) 1 else 0 }, { used[it.category] ?: 0 }),
            ) ?: remaining.first()
            remaining.remove(next)
            chosen += next
            used[next.category] = (used[next.category] ?: 0) + 1
            lastCategory = next.category
        }
        return Result(chosen, candidates.size, candidates.count { seen(it) == 0 })
    }
}
