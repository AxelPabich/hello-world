package de.pabich.buzzer.core

import java.util.PriorityQueue
import kotlin.random.Random

/**
 * Selbsttest der Spiellogik auf dem Gerät (Punkt 75): simulierte Spieler mit eigenen Uhren und
 * Funk-Laufzeiten in virtueller Zeit – dieselben Abläufe wie in den Kern-Tests, aber auf der
 * echten Laufzeitumgebung des Handys.
 */
object SelfTest {
    data class Result(val name: String, val ok: Boolean, val detail: String = "")

    /** Alle Prüfungen ohne Funkverbindung. */
    fun runOffline(): List<Result> = listOf(
        check("Nachrichten über die Funkstrecke (Codec)") { codec() },
        check("Einstellungen speichern und laden") { settingsRoundtrip() },
        check("Buzzer: Reihenfolge nach Reaktionszeit") { buzzerOrder() },
        check("Buzzer: Frühstart scheidet aus") { falseStart() },
        check("Buzzer: falsch, richtig, Punkte") { wrongThenRight() },
        check("Quiz: Fragen, Antworten, Wertung") { quizScoring() },
        check("Quiz: Challenge annulliert die Frage") { quizAnnul() },
        check("Bereit-Abfrage mit Go") { readyCheck() },
        check("Übernahme der Spielleitung") { takeover() },
        check("Punkte beim Wiederbeitritt") { rejoin() },
        check("Klänge erzeugen") { sounds() },
    )

    fun summary(results: List<Result>): String =
        "${results.count { it.ok }} von ${results.size} bestanden"

    fun format(results: List<Result>): String = results.joinToString("\n") {
        (if (it.ok) "OK      " else "FEHLER  ") + it.name + if (it.detail.isNotEmpty()) " – ${it.detail}" else ""
    }

    private fun check(name: String, block: () -> String): Result = try {
        Result(name, true, block())
    } catch (e: Throwable) {
        Result(name, false, e.message ?: e.javaClass.simpleName)
    }

    private fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw IllegalStateException(message())
    }

    // ------------------------------------------------------------------ Prüfungen

    private fun codec(): String {
        val q = question(1)
        val samples = listOf(
            Message.Ping(3, 1234), Message.Pong(3, 1234, 99),
            Message.Arm(1, 0, false), Message.Hold(1, 0, true),
            Message.GoAt(1, 0, StartMode.TAP, 5000, 10_000, false), Message.Buzz(1, 0, 321, false),
            Message.State(Display.GREEN, 1, "3"), Message.Reset("2"), Message.Pause(true),
            Message.Emote("Äxel", "🎉"), Message.Chat("Peter", "Hallo | & = %", true),
            Message.Mode(GameKind.QUIZ), Message.Intro(2), Message.ReadyCheck(4, true), Message.Ready(4),
            Message.QuizAnswer(2, 1, 1500), Message.QuizChallenge(2, "Quelle: ä, ö; ü"),
            Message.Avatar("🦊"), Message.Rules("Regeln", listOf("Kategorien" to "Technik, Küche & Genuss")),
            Message.DiagnoseRequest, Message.MasterGone, Message.TestMode(true),
            Message.Succession("g1", "Axel", "", GameKind.QUIZ, 3, "Profil", SettingsCodec.toMap(Settings()),
                listOf("Peter" to 2L), listOf(q.id), 0, false, listOf(q)),
        )
        val broken = samples.filter { MessageCodec.decode(MessageCodec.encode(it)) != it }
        expect(broken.isEmpty()) { "falsch zurückgelesen: " + broken.joinToString { it.javaClass.simpleName } }
        return "${samples.size} Nachrichtenarten"
    }

    private fun settingsRoundtrip(): String {
        val s = Settings(quizCount = 7, quizCategories = setOf("Technik", "Küche & Genuss"), masterPlays = true)
        val back = SettingsCodec.fromMap(SettingsCodec.toMap(s)).settings
        expect(back == s.normalized()) { "Einstellungen weichen ab" }
        return ""
    }

    private fun buzzerOrder(): String {
        val g = Sim()
        g.add("A", 7_000, 300); g.add("B", -3_000, 20); g.add("C", 123_456, 100)
        g.sync()
        expect(g.master.chooseKind(GameKind.BUZZER) && g.master.release()) { "Freigabe nicht möglich" }
        g.tap("A", 250); g.tap("B", 300); g.tap("C", 200)
        g.time.advanceBy(30_000)
        val order = g.master.snapshot().players.filter { it.place > 0 }.sortedBy { it.place }.map { it.id }
        expect(order == listOf("C", "A", "B")) { "Reihenfolge $order statt C, A, B" }
        val a = g.master.snapshot().players.first { it.id == "A" }.reactionMs
        expect(a == 250L) { "Reaktionszeit A $a statt 250 ms" }
        return "trotz Uhrabweichung bis 123 s und Laufzeit bis 300 ms"
    }

    private fun falseStart(): String {
        val g = Sim()
        g.add("A", 0, 50); g.add("B", 0, 50)
        g.sync()
        g.master.chooseKind(GameKind.BUZZER)
        g.master.release()
        g.tap("A", -400)
        g.tap("B", 300)
        g.time.advanceBy(30_000)
        val a = g.player("A").view.display
        expect(a == Display.GRAY) { "Frühstarter zeigt $a statt GRAY" }
        return ""
    }

    private fun wrongThenRight(): String {
        val g = Sim()
        g.add("A", 0, 40); g.add("B", 0, 60)
        g.sync()
        g.master.chooseKind(GameKind.BUZZER)
        g.master.release()
        g.tap("A", 200); g.tap("B", 300)
        g.time.advanceBy(30_000)
        expect(g.master.markWrong()) { "„Falsch“ nicht möglich" }
        g.time.advanceBy(30_000)
        expect(g.master.markCorrect()) { "„Richtig“ nicht möglich" }
        g.time.advanceBy(30_000)
        val b = g.master.snapshot().players.first { it.id == "B" }.scoreValue
        expect(b == 1L) { "B hat $b Punkte statt 1" }
        return ""
    }

    private fun quizScoring(): String {
        val g = Sim(Settings(quizAnswerMs = 10_000))
        g.add("A", 500, 40); g.add("B", -800, 90)
        g.sync()
        expect(g.master.chooseKind(GameKind.QUIZ)) { "Quiz nicht wählbar" }
        expect(g.master.startQuiz(listOf(question(1), question(2)))) { "Quiz startet nicht" }
        g.answer("A", 1_000, right = true)
        g.answer("B", 2_000, right = false)
        g.time.advanceBy(20_000)
        val quiz = g.master.snapshot().quiz ?: throw IllegalStateException("kein Quiz")
        expect(quiz.revealed) { "nicht aufgelöst" }
        val a = g.master.snapshot().players.first { it.id == "A" }.scoreValue
        val b = g.master.snapshot().players.first { it.id == "B" }.scoreValue
        expect(a == 1L && b == 0L) { "Punkte A=$a, B=$b statt 1/0" }
        expect(g.master.nextQuestion()) { "zweite Frage nicht möglich" }
        return ""
    }

    private fun quizAnnul(): String {
        val g = Sim(Settings(quizAnswerMs = 10_000))
        g.add("A", 0, 40)
        g.sync()
        g.master.chooseKind(GameKind.QUIZ)
        g.master.startQuiz(listOf(question(1), question(2)))
        g.answer("A", 1_000, right = true)
        g.time.advanceBy(20_000)
        expect(g.master.decideChallenge(ChallengeDecision.ANNUL)) { "Annullieren nicht möglich" }
        val a = g.master.snapshot().players.first { it.id == "A" }.scoreValue
        expect(a == 0L) { "nach dem Annullieren noch $a Punkte" }
        return ""
    }

    private fun readyCheck(): String {
        val g = Sim()
        g.add("A", 0, 40); g.add("B", 0, 80)
        g.sync()
        g.master.chooseKind(GameKind.QUIZ)
        var go = false
        g.master.onAllReady = { go = true }
        expect(g.master.askReady()) { "Abfrage startet nicht" }
        g.time.advanceBy(500)
        g.player("A").confirmReady(); g.player("B").confirmReady()
        g.master.confirmMasterReady()
        g.time.advanceBy(1_000)
        expect(go) { "kein „Go!“ nach allen Bestätigungen" }
        return ""
    }

    private fun takeover(): String {
        val old = Sim(Settings(quizAnswerMs = 10_000))
        old.add("A", 0, 40); old.add("B", 0, 40)
        old.sync()
        old.master.chooseKind(GameKind.QUIZ)
        old.master.startQuiz(listOf(question(1), question(2), question(3)))
        old.answer("A", 1_000, right = true)
        old.time.advanceBy(20_000)
        val s = old.received.getValue("B").filterIsInstance<Message.Succession>().lastOrNull()
            ?: throw IllegalStateException("kein Spielstand bei den Spielern")
        expect(s.quizNext == 1 && s.questions.isNotEmpty()) { "Spielstand unvollständig (nächste Frage ${s.quizNext}, ${s.questions.size} Fragen)" }
        val n = Sim()
        n.master.masterName = "B"
        n.master.takeOver(s, listOf(question(1), question(2), question(3)), s.quizNext)
        n.add("A", 0, 40)
        n.sync()
        val a = n.master.snapshot().players.first { it.id == "A" }.scoreValue
        expect(a == 1L) { "Punkte von A nach Übernahme $a statt 1" }
        expect(n.master.nextQuestion()) { "Quiz geht nicht weiter" }
        return "Quiz läuft bei Frage 2 weiter"
    }

    private fun rejoin(): String {
        val g = Sim()
        g.add("A", 0, 40); g.add("B", 0, 40)
        g.sync()
        g.master.chooseKind(GameKind.BUZZER)
        g.master.release()
        g.tap("A", 200); g.tap("B", 300)
        g.time.advanceBy(30_000)
        g.master.markCorrect()
        g.time.advanceBy(500)
        g.master.nextRound()
        g.master.removePlayer("A")
        g.master.addPlayer("A2", "A")
        val a = g.master.snapshot().players.first { it.name == "A" }.scoreValue
        expect(a == 1L) { "nach Wiederbeitritt $a Punkte statt 1" }
        return ""
    }

    private fun sounds(): String {
        val events = SoundEvent.entries.filter { it != SoundEvent.CHASE }
        val silent = events.filter { e -> SoundSynth.render(e).none { kotlin.math.abs(it.toInt()) > 1000 } } +
            Jingle.entries.filter { j -> SoundSynth.render(j).none { kotlin.math.abs(it.toInt()) > 1000 } }.map { it.name }
        expect(silent.isEmpty()) { "stumm: $silent" }
        return "${events.size} Klänge, ${Jingle.entries.size} Jingles"
    }

    private fun question(n: Int) = QuizQuestion(
        "test-$n", "Test", QuizLevel.MEDIUM, "Testfrage $n?",
        listOf("Richtig $n", "Falsch A", "Falsch B", "Falsch C"), 0, "Weil.", "",
    )

    // ------------------------------------------------------------------ Simulation

    private class VirtualTime : Scheduler {
        private class Task(val at: Long, val seq: Long, val block: () -> Unit) { var cancelled = false }
        var now = 0L
            private set
        private var seq = 0L
        private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val task = Task(now + delayMs.coerceAtLeast(0), seq++, block)
            queue += task
            return Cancellable { task.cancelled = true }
        }
        fun advanceBy(ms: Long) {
            val target = now + ms
            while (true) {
                val next = queue.peek() ?: break
                if (next.at > target) break
                queue.poll()
                now = next.at
                if (!next.cancelled) next.block()
            }
            now = maxOf(now, target)
        }
    }

    private class Sim(settings: Settings = Settings()) {
        val time = VirtualTime()
        private val offsets = mutableMapOf<PlayerId, Long>()
        private val latency = mutableMapOf<PlayerId, Long>()
        private val engines = mutableMapOf<PlayerId, PlayerEngine>()
        val received = mutableMapOf<PlayerId, MutableList<Message>>()
        val master = MasterEngine(Clock { time.now }, time, Random(1)) { to, msg ->
            val bytes = MessageCodec.encode(msg)
            received.getOrPut(to) { mutableListOf() } += msg
            val engine = engines[to] ?: return@MasterEngine
            time.schedule(latency[to] ?: 0) { MessageCodec.decode(bytes)?.let { engine.onMessage(it) } }
        }

        init {
            master.updateSettings(settings)
        }

        fun add(id: PlayerId, offset: Long, delay: Long) {
            offsets[id] = offset
            latency[id] = delay
            engines[id] = PlayerEngine(Clock { time.now + offset }, time) { msg ->
                val bytes = MessageCodec.encode(msg)
                time.schedule(delay) { MessageCodec.decode(bytes)?.let { master.onMessage(id, it) } }
            }
            master.addPlayer(id, id)
        }

        fun player(id: PlayerId) = engines.getValue(id)

        fun sync() {
            master.start()
            time.advanceBy(5_000)
        }

        /** Tippen [reactionMs] nach der lokalen Freigabe (negativ = Frühstart). */
        fun tap(id: PlayerId, reactionMs: Long) {
            val goAt = received.getValue(id).filterIsInstance<Message.GoAt>().last().goAtLocal
            val off = offsets.getValue(id)
            time.schedule(goAt - off + reactionMs - time.now) {
                val e = player(id)
                e.onTouchDown(time.now + off)
                e.onTouchUp(time.now + off + 30)
            }
        }

        fun answer(id: PlayerId, elapsedMs: Long, right: Boolean) {
            val ask = received.getValue(id).filterIsInstance<Message.QuizAsk>().last()
            val pos = ask.answers.indexOfFirst { it.startsWith("Richtig") == right }
            val off = offsets.getValue(id)
            time.schedule(ask.answersAtLocal - off + elapsedMs - time.now) { player(id).tapAnswer(pos, time.now + off) }
        }
    }
}
