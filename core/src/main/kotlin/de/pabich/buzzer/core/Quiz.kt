package de.pabich.buzzer.core

/** Spielart, die der Master beim Start wählt. */
enum class GameKind(val label: String) {
    /** Fragen stellt der Master mündlich, die Spieler buzzern. */
    BUZZER("Buzzer"),

    /** Fragen mit vier Antworten aus den Fragenpaketen, die App wertet selbst aus. */
    QUIZ("Quiz"),
}

/**
 * Eine Schwierigkeitsstufe. Welche Stufen es gibt, steht in der Fragendatenbank (`quiz/index.json`,
 * „stufen“) und gilt nach dem Laden für die ganze App ([QuizLevels]) – neue Stufen brauchen keinen
 * neuen Build. Die Reihenfolge der Liste ist die Reihenfolge von leicht nach schwer.
 */
class QuizLevel(
    val key: String,
    val label: String,
    /** Erwarteter Anteil richtiger Antworten, solange noch zu wenig gemessen wurde. */
    val startValue: Double = 0.3,
    /** Ab diesem Anteil richtiger Antworten gilt die Frage nach der Messung mindestens als diese Stufe. */
    val measuredFrom: Double = 0.0,
    /** Nur Fragen, deren Autor diese Stufe vergeben hat, behalten sie; die Messung vergibt sie nicht. */
    val authorOnly: Boolean = false,
    /** In der Grundauswahl des Quiz vorgewählt. */
    val standard: Boolean = true,
) {
    override fun equals(other: Any?) = other is QuizLevel && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = key

    /** Position in der Stufenliste (leichteste zuerst). */
    val ordinal: Int get() = entries.indexOf(this).let { if (it < 0) Int.MAX_VALUE else it }

    /** Name, den ältere Apps (ohne Stufenliste) in Nachrichten erwarten. */
    val legacyName: String
        get() = when (key) {
            "leicht" -> "EASY"
            "mittel" -> "MEDIUM"
            else -> "HARD"
        }

    companion object {
        val EASY = QuizLevel("leicht", "Leicht", 0.8, 0.7)
        val MEDIUM = QuizLevel("mittel", "Mittel", 0.55, 0.4)
        val HARD = QuizLevel("schwer", "Schwer", 0.3, 0.2)
        val INGOMANIA = QuizLevel("ingomania", "Ingomania", 0.12, 0.0, authorOnly = true, standard = false)

        /** Stufen, solange keine Fragendatenbank geladen ist (und für die Tests). */
        val DEFAULTS = listOf(EASY, MEDIUM, HARD, INGOMANIA)

        /** Alle bekannten Stufen, leichteste zuerst. */
        val entries: List<QuizLevel> get() = QuizLevels.all

        /** Grundauswahl im Quiz; Stufen mit `standard = false` muss der Spielleiter bewusst dazuwählen. */
        val STANDARD: Set<QuizLevel> get() = entries.filter { it.standard }.toSet()

        fun byKey(key: String): QuizLevel? {
            val k = key.trim().lowercase()
            return entries.firstOrNull { it.key == k } ?: when (k) {
                "easy" -> EASY
                "medium" -> MEDIUM
                "hard" -> HARD
                else -> null
            }
        }

        /** Stufe aus einer Nachricht: eigene Liste, sonst anhand von Schlüssel und Beschriftung nachgebaut. */
        fun fromWire(key: String?, label: String?, legacy: String): QuizLevel =
            key?.let { byKey(it) ?: QuizLevel(it, label?.takeIf { l -> l.isNotBlank() } ?: it, authorOnly = true, standard = false) }
                ?: byKey(legacy) ?: HARD
    }
}

/** Die in der Fragendatenbank verwalteten Stufen (siehe [QuizLevel]). */
object QuizLevels {
    @Volatile
    var all: List<QuizLevel> = QuizLevel.DEFAULTS
        private set

    fun update(levels: List<QuizLevel>) {
        if (levels.isNotEmpty()) all = levels.distinctBy { it.key }
    }

    fun reset() {
        all = QuizLevel.DEFAULTS
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

    /** Stufen aus dem Index („stufen“); leer, wenn der Index keine nennt. */
    fun parseLevels(text: String): List<QuizLevel> {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return emptyList()
        return (root["stufen"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val key = (m["schluessel"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            QuizLevel(
                key = key,
                label = (m["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: key.replaceFirstChar { it.uppercase() },
                startValue = (m["startwert"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.3,
                measuredFrom = (m["gemessenAb"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0,
                authorOnly = m["nurAutor"] as? Boolean ?: false,
                standard = m["vorgewaehlt"] as? Boolean ?: true,
            )
        }.distinctBy { it.key }
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
