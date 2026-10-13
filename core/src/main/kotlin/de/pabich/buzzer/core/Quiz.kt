package de.pabich.buzzer.core

/** Spielart, die der Master beim Start wählt. */
enum class GameKind(val label: String) {
    /** Fragen stellt der Master mündlich, die Spieler buzzern. */
    BUZZER("Buzzer"),

    /** Fragen mit vier Antworten aus den Fragenpaketen, die App wertet selbst aus. */
    QUIZ("Quiz"),
}

enum class QuizLevel(val key: String, val label: String) {
    EASY("leicht", "Leicht"),
    MEDIUM("mittel", "Mittel"),
    HARD("schwer", "Schwer"),
    ;

    companion object {
        fun byKey(key: String): QuizLevel? = entries.firstOrNull { it.key == key.trim().lowercase() || it.name == key }
    }
}

/** Wertung im Quiz. Falsche Antworten kosten nie etwas. */
enum class QuizScoring(val label: String, val description: String, val usesTime: Boolean) {
    FASTEST("Schnellster punktet", "Nur die erste richtige Antwort zählt", false),
    EVERYONE("Jeder punktet", "Jede richtige Antwort gibt einen Punkt", false),
    COUNTDOWN("Countdown nach erster richtiger", "Alle Richtigen bekommen ihre Restzeit", true),
    BATTLE("Battle", "Nur der Schnellste bekommt seine Restzeit", true),
}

/** Eine Frage aus einem Fragenpaket. Die erste Antwort ist dort immer die richtige. */
data class Question(
    val id: String,
    val version: Int,
    val category: String,
    val level: QuizLevel,
    val text: String,
    val answers: List<String>,
    val explanation: String,
    val source: String,
    val created: String,
    /** Fragen zu aktuellen Themen laufen ab (ISO-Datum `JJJJ-MM-TT`), sonst `null`. */
    val validUntil: String?,
) {
    /** Für das Spiel; [correctOverride] = vom Master korrigierte richtige Antwort. */
    fun toQuiz(correctOverride: Int? = null) = QuizQuestion(
        id = id,
        category = category,
        level = level,
        text = text,
        answers = answers,
        correctIndex = correctOverride?.takeIf { it in answers.indices } ?: 0,
        explanation = explanation,
        source = source,
    )
}

/** Eine Frage im laufenden Spiel; [correctIndex] zeigt in [answers] (Paket-Reihenfolge). */
data class QuizQuestion(
    val id: String,
    val category: String,
    val level: QuizLevel,
    val text: String,
    val answers: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val source: String,
)

data class QuestionPack(val name: String, val title: String, val version: Int, val questions: List<Question>)

object QuestionPacks {
    const val ANSWER_COUNT = 4

    /** Liest ein Paket. Fehlerhafte Fragen werden übersprungen und in [problems] gemeldet. */
    fun parse(text: String, problems: MutableList<String> = mutableListOf()): QuestionPack {
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw MiniJson.ParseException("Paket ist kein Objekt")
        val name = root["paket"] as? String ?: throw MiniJson.ParseException("„paket“ fehlt")
        val questions = (root["fragen"] as? List<*>).orEmpty().mapIndexedNotNull { i, raw ->
            runCatching { question(raw as Map<*, *>) }
                .onFailure { problems += "$name, Frage ${i + 1}: ${it.message}" }
                .getOrNull()
        }
        val ids = questions.groupBy { it.id }.filter { it.value.size > 1 }.keys
        ids.forEach { problems += "$name: doppelte ID $it" }
        return QuestionPack(
            name = name,
            title = root["titel"] as? String ?: name,
            version = (root["version"] as? Long)?.toInt() ?: 1,
            questions = questions.distinctBy { it.id },
        )
    }

    private fun question(m: Map<*, *>): Question {
        fun text(key: String): String = (m[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("„$key“ fehlt")
        val answers = (m["antworten"] as? List<*>)?.map { (it as? String)?.trim().orEmpty() }
            ?: throw IllegalArgumentException("„antworten“ fehlt")
        require(answers.size == ANSWER_COUNT) { "genau $ANSWER_COUNT Antworten nötig, nicht ${answers.size}" }
        require(answers.none { it.isEmpty() }) { "leere Antwort" }
        require(answers.toSet().size == ANSWER_COUNT) { "doppelte Antwort" }
        val levelText = text("stufe")
        return Question(
            id = text("id"),
            version = (m["version"] as? Long)?.toInt() ?: 1,
            category = text("kategorie"),
            level = QuizLevel.byKey(levelText) ?: throw IllegalArgumentException("Stufe „$levelText“ unbekannt"),
            text = text("frage"),
            answers = answers,
            explanation = (m["erklaerung"] as? String).orEmpty().trim(),
            source = (m["quelle"] as? String).orEmpty().trim(),
            created = (m["erstellt"] as? String).orEmpty(),
            validUntil = (m["gueltigBis"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    /** Index der Pakete im Repository: Dateiname → Version. */
    fun parseIndex(text: String): Map<String, Int> {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return emptyMap()
        return (root["pakete"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val file = m["datei"] as? String ?: return@mapNotNull null
            file to ((m["version"] as? Long)?.toInt() ?: 1)
        }.toMap()
    }

    /** Pakete zusammenführen: gleiche Namen → höhere Version gewinnt. */
    fun merge(packs: List<QuestionPack>): List<QuestionPack> =
        packs.groupBy { it.name }.map { (_, list) -> list.maxBy { it.version } }.sortedBy { it.title }
}
