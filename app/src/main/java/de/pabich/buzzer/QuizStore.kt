package de.pabich.buzzer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import de.pabich.buzzer.core.ChallengeDecision
import de.pabich.buzzer.core.ParticipantResult
import de.pabich.buzzer.core.Question
import de.pabich.buzzer.core.QuestionPack
import de.pabich.buzzer.core.QuestionPacks
import de.pabich.buzzer.core.QuizQuestion
import de.pabich.buzzer.core.StatsBook
import java.io.File
import kotlin.concurrent.thread

/**
 * Fragenpakete (eingebaut und aus dem Repository geladen) und die Spielstatistik des
 * Master-Geräts (`quiz-stats.json`).
 */
class QuizStore(private val context: Context) {
    private val downloadDir = File(context.filesDir, "quiz").apply { mkdirs() }
    private val statsFile = File(context.filesDir, "quiz-stats.json")
    private val main = Handler(Looper.getMainLooper())

    var packs: List<QuestionPack> = emptyList()
        private set
    var stats: StatsBook = StatsBook()
        private set

    val questions: List<Question> get() = packs.flatMap { it.questions }
    val categories: List<String> get() = packs.map { it.title }.distinct()
    private val byId: Map<String, Question> get() = questions.associateBy { it.id }

    init {
        reload()
        stats = runCatching { StatsBook.fromJson(statsFile.readText()) }
            .onFailure { if (statsFile.exists()) Diagnostics.log("Quiz-Statistik nicht lesbar: ${it.message}") }
            .getOrDefault(StatsBook())
    }

    /** Eingebaute Pakete plus geladene; bei gleichem Namen gewinnt die höhere Version. */
    private fun reload() {
        val problems = mutableListOf<String>()
        val bundled = runCatching {
            QuestionPacks.parseIndex(context.assets.open("index.json").use { it.readBytes().toString(Charsets.UTF_8) }).keys.mapNotNull { name ->
                runCatching { QuestionPacks.parse(context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }, problems) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        val downloaded = downloadDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { QuestionPacks.parse(f.readText(), problems) }.getOrNull()
        }
        packs = QuestionPacks.merge(bundled + downloaded)
        problems.forEach { Diagnostics.log("Fragenpaket: $it") }
    }

    fun toQuiz(q: Question): QuizQuestion = q.toQuiz(stats.of(q).correctOverride)

    fun question(id: String): Question? = byId[id]

    // ------------------------------------------------------------------ Statistik

    private fun saveStats() {
        runCatching { statsFile.writeText(stats.toJson(byId)) }
            .onFailure { Diagnostics.log("Quiz-Statistik nicht speicherbar: ${it.message}") }
    }

    fun recordResult(id: String, results: List<ParticipantResult>) {
        val q = byId[id] ?: return
        stats = stats.recordResult(q, results, System.currentTimeMillis())
        saveStats()
    }

    fun recordSeen(id: String, names: List<String>) {
        val q = byId[id] ?: return
        stats = stats.recordSeen(q, names, System.currentTimeMillis())
        saveStats()
    }

    fun recordChallenge(id: String, from: String, reason: String) {
        val q = byId[id] ?: return
        stats = stats.recordChallenge(q, from, reason)
        saveStats()
    }

    fun recordDecision(id: String, decision: ChallengeDecision, newCorrect: Int?) {
        val q = byId[id] ?: return
        // Index in der Paket-Reihenfolge – das Spiel kennt die Frage mit derselben Antwortliste.
        stats = stats.recordDecision(q, decision, newCorrect)
        saveStats()
    }

    /** Teilt die Statistik als `.buzzstats` (z. B. an Claude zur Auswertung). */
    fun shareStats(activity: Context) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "buzzer-statistik.buzzstats")
        file.writeText(stats.toJson(byId))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Buzzer – Quiz-Statistik")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Statistik teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Diagnostics.log("Quiz-Statistik geteilt (${stats.entries.size} Fragen)")
    }

    // ------------------------------------------------------------------ Aktualisieren

    /** Lädt neuere Pakete aus dem Repository; [onDone] mit Meldung für den Nutzer. */
    fun refresh(onDone: (String) -> Unit) {
        thread(name = "buzzer-quiz-update", isDaemon = true) {
            val message = runCatching {
                val index = QuestionPacks.parseIndex(AppUpdate.fetchText("$REPO_URL/index.json"))
                val have = packs.associate { "${it.name}.json" to it.version }
                var loaded = 0
                for ((file, version) in index) {
                    if (version <= (have[file] ?: 0)) continue
                    val text = AppUpdate.fetchText("$REPO_URL/$file")
                    val problems = mutableListOf<String>()
                    QuestionPacks.parse(text, problems) // Prüfen, bevor die Datei ersetzt wird.
                    File(downloadDir, file).writeText(text)
                    problems.forEach { Diagnostics.log("Fragenpaket $file: $it") }
                    loaded++
                }
                if (loaded == 0) "Die Fragen sind schon aktuell." else "$loaded Fragenpaket(e) aktualisiert."
            }.getOrElse {
                Diagnostics.log("Fragen aktualisieren fehlgeschlagen: ${it.message}")
                if (it.message == "HTTP 404") {
                    "Im Repository liegen keine neueren Fragen."
                } else {
                    "Fragen konnten nicht geladen werden (Internet?)."
                }
            }
            main.post {
                reload()
                onDone(message)
            }
        }
    }

    companion object {
        /** Fragenpakete im Repository (Branch master). */
        const val REPO_URL = "https://raw.githubusercontent.com/AxelPabich/hello-world/master/quiz"
    }
}
