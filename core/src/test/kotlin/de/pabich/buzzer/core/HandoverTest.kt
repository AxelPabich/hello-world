package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Übergabe und Übernahme der Spielleitung (Punkt 59). */
class HandoverTest {
    private val ids = listOf("Axel", "Peter", "Clara")

    private fun question(n: Int) = QuizQuestion(
        id = "q$n", category = "Technik", level = QuizLevel.MEDIUM, text = "Frage $n?",
        answers = listOf("Richtig $n", "Falsch A$n", "Falsch B$n", "Falsch C$n"), correctIndex = 0,
        explanation = "", source = "",
    )

    private fun quizGame(): SimGame {
        val g = SimGame(Settings(quizAnswerMs = 10_000))
        ids.forEachIndexed { i, id -> g.addPlayer(id, clockOffsetMs = 300L * i, latencyMs = 40L) }
        g.syncClocks()
        assertTrue(g.master.chooseKind(GameKind.QUIZ))
        g.time.advanceBy(200)
        return g
    }

    private fun SimGame.lastSuccession(id: PlayerId) = received.getValue(id).filterIsInstance<Message.Succession>().last()

    private fun SimGame.answerRight(id: PlayerId, elapsedMs: Long) {
        val p = player(id)
        val ask = received.getValue(id).filterIsInstance<Message.QuizAsk>().last()
        val pos = ask.answers.indexOf(ask.answers.first { it.startsWith("Richtig") })
        time.schedule(ask.answersAtLocal - p.clockOffsetMs + elapsedMs - time.now) { p.engine.tapAnswer(pos, p.clock.now()) }
    }

    @Test
    fun `succession survives the codec`() {
        val s = Message.Succession(
            game = "abc23", master = "Axel|Ä", to = "Peter: 1", kind = GameKind.QUIZ, round = 3, profile = "Mein Quiz",
            settings = SettingsCodec.toMap(Settings(quizCount = 7)), scores = listOf("Peter: 1" to 2L, "Clara,x" to 0L),
            quiz = listOf("tech-0001", "erd-0002"), quizNext = 1, test = true,
        )
        assertEquals(s, MessageCodec.decode(MessageCodec.encode(s)))
        assertEquals(Message.MasterGone, MessageCodec.decode(MessageCodec.encode(Message.MasterGone)))
        assertEquals(Message.DiagnoseRequest, MessageCodec.decode(MessageCodec.encode(Message.DiagnoseRequest)))
    }

    @Test
    fun `players always know the state of the last pause`() {
        val g = quizGame()
        assertEquals(-1, g.lastSuccession("Clara").quizNext, "noch kein Quiz")
        assertTrue(g.master.startQuiz(listOf(question(1), question(2), question(3))))
        g.answerRight("Peter", 1_000)
        g.time.advanceBy(15_000)
        assertEquals(MasterEngine.Phase.QUIZ_REVEAL, g.master.snapshot().phase)
        val s = g.lastSuccession("Clara")
        assertEquals(g.master.gameId, s.game)
        assertEquals(listOf("q1", "q2", "q3"), s.quiz)
        assertEquals(1, s.quizNext, "weiter mit der zweiten Frage")
        assertEquals(1L, s.scores.toMap()["Peter"])
        // Während der nächsten Frage kommt kein neuer Stand – fällt der Spielleiter jetzt weg, wird sie wiederholt.
        val count = g.received.getValue("Clara").count { it is Message.Succession }
        assertTrue(g.master.nextQuestion())
        g.time.advanceBy(2_000)
        assertEquals(count, g.received.getValue("Clara").count { it is Message.Succession })
    }

    @Test
    fun `successor continues the quiz with the carried scores`() {
        val old = quizGame()
        assertTrue(old.master.startQuiz(listOf(question(1), question(2), question(3))))
        old.answerRight("Peter", 1_000)
        old.answerRight("Clara", 2_000)
        old.time.advanceBy(15_000)
        val s = old.lastSuccession("Peter")

        // Peter übernimmt: neue Gruppe, die anderen treten wieder bei.
        val g = SimGame()
        g.master.masterName = "Peter"
        g.master.takeOver(s, listOf(question(1), question(2), question(3)), s.quizNext)
        assertEquals(s.game, g.master.gameId)
        assertTrue(g.master.snapshot().settings.masterPlays, "wer übernimmt, spielt weiter mit")
        val quiz = assertNotNull(g.master.snapshot().quiz)
        assertTrue(quiz.resumed)
        assertTrue(quiz.hasNext)
        assertEquals(1, quiz.number)
        assertFalse(g.master.decideChallenge(ChallengeDecision.ANNUL), "keine Entscheidung über die Frage des Vorgängers")
        g.addPlayer("Peter${MasterEngine.MASTER_SUFFIX}", 0, 0)
        g.addPlayer("Clara", 200, 40)
        g.addPlayer("Axel", 100, 40)
        g.syncClocks()
        val scores = g.master.snapshot().players.associate { it.name to it.scoreValue }
        assertEquals(old.master.snapshot().players.associate { it.name to it.scoreValue }.mapKeys { (k, _) -> if (k == "Peter") "Peter (Master)" else k }, scores)
        assertTrue(g.master.nextQuestion())
        val ask = g.received.getValue("Clara").filterIsInstance<Message.QuizAsk>().last()
        assertEquals(2, ask.number)
        assertEquals("Frage 2?", ask.text)
        // Der Spielverlauf beginnt mit den übernommenen Punkten, nicht bei 0.
        g.time.advanceBy(20_000)
        assertTrue(g.master.showReplay())
        g.time.advanceBy(200)
        val replay = g.received.getValue("Clara").filterIsInstance<Message.Replay>().last()
        assertEquals(s.scores.sumOf { it.second }, replay.frames.first().sum())
        assertTrue(replay.frames.first().sum() > 0)
    }

    @Test
    fun `buzzer game keeps round and scores, unknown names keep theirs for later`() {
        val g = SimGame()
        ids.forEach { g.addPlayer(it, 0, 30) }
        g.syncClocks()
        assertTrue(g.master.chooseKind(GameKind.BUZZER))
        assertTrue(g.master.release())
        g.scheduleTap("Clara", 200)
        g.scheduleTap("Axel", 300)
        g.scheduleTap("Peter", 400)
        g.time.advanceBy(10_000)
        assertTrue(g.master.markCorrect())
        g.time.advanceBy(500)
        g.master.nextRound()
        g.time.advanceBy(500)
        val s = g.lastSuccession("Axel")
        assertEquals(GameKind.BUZZER, s.kind)
        assertEquals(1, s.round)
        assertEquals(-1, s.quizNext)

        val n = SimGame()
        n.master.masterName = "Axel"
        n.master.takeOver(s, emptyList(), s.quizNext)
        assertEquals(MasterEngine.Phase.READY, n.master.snapshot().phase)
        assertEquals(1, n.master.snapshot().round)
        assertTrue(n.master.snapshot().scoringLocked)
        n.addPlayer("Clara", 0, 30)
        assertEquals(1L, n.row("Clara").scoreValue)
        // Peter kommt erst später zurück – seine Punkte warten und stehen weiter im Stand für Nachfolger.
        assertTrue(n.master.succession().scores.any { it.first == "Peter" })
    }

    @Test
    fun `heartbeat reaches players during a quiz question`() {
        val g = quizGame()
        assertTrue(g.master.startQuiz(listOf(question(1))))
        g.time.advanceBy(1_000)
        val before = g.received.getValue("Clara").count { it is Message.Ping }
        g.time.advanceBy(6_000)
        assertEquals(MasterEngine.Phase.QUIZ_ANSWERS, g.master.snapshot().phase)
        assertTrue(g.received.getValue("Clara").count { it is Message.Ping } - before >= 2, "etwa alle 2 s ein Ping")
    }

    @Test
    fun `succession carries the questions from the last asked one`() {
        val g = quizGame()
        val qs = (1..5).map { question(it).copy(explanation = "Weil, ä; ö|ü %", source = "https://x.org/?a=1&b=2") }
        assertTrue(g.master.startQuiz(qs))
        g.time.advanceBy(15_000)
        val s = g.lastSuccession("Clara")
        assertEquals(1, s.quizNext)
        assertEquals(qs, s.questions, "ab der zuletzt gestellten Frage, unverändert durch die Funkstrecke")
    }

    @Test
    fun `very many questions still fit into one message`() {
        val long = "Sehr lange Erklärung mit Umlauten äöü ".repeat(10)
        val qs = (1..60).map { question(it).copy(explanation = long, source = "https://de.wikipedia.org/wiki/$it") }
        val s = Message.Succession("g", "M", "", GameKind.QUIZ, 1, "", emptyMap(), emptyList(), qs.map { it.id }, 1, false, qs)
        val bytes = MessageCodec.encode(s)
        assertTrue(bytes.size < 32_000, "${bytes.size} Bytes")
        val back = MessageCodec.decode(bytes) as Message.Succession
        assertTrue(back.questions.isNotEmpty())
        assertEquals(qs.first().text, back.questions.first().text)
    }

    @Test
    fun `players who reconnect between rounds keep their points`() {
        val g = SimGame()
        ids.forEach { g.addPlayer(it, 0, 30) }
        g.syncClocks()
        assertTrue(g.master.chooseKind(GameKind.BUZZER))
        assertTrue(g.master.release())
        g.scheduleTap("Clara", 200)
        g.scheduleTap("Axel", 300)
        g.scheduleTap("Peter", 400)
        g.time.advanceBy(10_000)
        assertTrue(g.master.markCorrect())
        g.time.advanceBy(500)
        g.master.nextRound()
        g.master.removePlayer("Clara")
        assertTrue(g.master.snapshot().players.none { it.name == "Clara" })
        g.addPlayer("Clara-neu", 0, 30)
        // Der Sim-Spieler heißt wie seine ID – also unter dem alten Namen neu aufnehmen.
        g.master.removePlayer("Clara-neu")
        g.master.addPlayer("Clara-neu", "Clara")
        assertEquals(1L, g.master.snapshot().players.first { it.name == "Clara" }.scoreValue)
    }

    @Test
    fun `hand over names the successor and silences the old master`() {
        val g = quizGame()
        assertFalse(g.master.handOver("Niemand"))
        assertTrue(g.master.handOver("Clara"))
        g.time.advanceBy(200)
        ids.forEach { assertEquals("Clara", g.lastSuccession(it).to) }
        assertFalse(g.master.handOver("Peter"), "nur einmal")
    }
}
