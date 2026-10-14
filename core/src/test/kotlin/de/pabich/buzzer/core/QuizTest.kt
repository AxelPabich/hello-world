package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizTest {
    private val ids = listOf("Axel", "Peter", "Clara")

    private fun question(n: Int, correct: String = "Richtig $n") = QuizQuestion(
        id = "q$n", category = if (n % 2 == 0) "Technik" else "Erdkunde", level = QuizLevel.MEDIUM,
        text = "Frage $n?", answers = listOf(correct, "Falsch A$n", "Falsch B$n", "Falsch C$n"), correctIndex = 0,
        explanation = "Weil $n.", source = "https://example.org/$n",
    )

    private fun game(settings: Settings = Settings(quizAnswerMs = 10_000)): SimGame {
        val g = SimGame(settings)
        ids.forEachIndexed { i, id -> g.addPlayer(id, clockOffsetMs = 1_000L * i - 500, latencyMs = 40L + 30 * i) }
        g.syncClocks()
        assertTrue(g.master.chooseKind(GameKind.QUIZ))
        g.time.advanceBy(200)
        return g
    }

    private fun SimGame.ask(id: PlayerId) = received.getValue(id).filterIsInstance<Message.QuizAsk>().last()

    /** Spieler [id] tippt die Antwort mit Text [text] nach [elapsedMs] ab dem Einblenden. */
    private fun SimGame.answer(id: PlayerId, text: String, elapsedMs: Long) {
        val p = player(id)
        val ask = ask(id)
        val pos = ask.answers.indexOf(text)
        val atVirtual = ask.answersAtLocal - p.clockOffsetMs + elapsedMs
        time.schedule(atVirtual - time.now) { p.engine.tapAnswer(pos, p.clock.now()) }
    }

    private fun SimGame.result(id: PlayerId) = view(id).quiz?.result
    private fun SimGame.scores() = master.snapshot().players.associate { it.id to it.scoreValue }

    @Test
    fun `players learn the game kind`() {
        val g = game()
        assertTrue(ids.all { g.view(it).kind == GameKind.QUIZ })
        assertEquals("Quiz – gleich kommt die erste Frage", g.view("Axel").hint)
        assertFalse(g.master.release(), "kein Buzzer im Quiz")
    }

    @Test
    fun `question appears everywhere at the same moment and answers three seconds later`() {
        val g = game()
        assertTrue(g.master.startQuiz(listOf(question(1), question(2))))
        val asks = ids.associateWith { g.ask(it) }
        // In Master-Zeit umgerechnet erscheint die Frage überall gleichzeitig.
        val questionAt = ids.map { asks.getValue(it).questionAtLocal - g.player(it).clockOffsetMs }.toSet()
        assertTrue(questionAt.max() - questionAt.min() <= 5, "gleichzeitig: $questionAt")
        val a = asks.getValue("Axel")
        assertEquals(MasterEngine.QUESTION_LEAD_MS, a.answersAtLocal - a.questionAtLocal)
        // Jeder hat alle vier Antworten, die Reihenfolge ist je Spieler gemischt.
        assertTrue(asks.values.all { it.answers.toSet() == question(1).answers.toSet() })

        g.time.advanceTo(questionAt.max() - 10)
        assertEquals(PlayerEngine.QuizStage.WAITING, g.view("Axel").quiz?.stage)
        g.time.advanceTo(questionAt.max() + 10)
        assertEquals(PlayerEngine.QuizStage.QUESTION, g.view("Axel").quiz?.stage)
        assertEquals(MasterEngine.Phase.QUIZ_QUESTION, g.master.snapshot().phase)
        assertFalse(g.player("Axel").engine.tapAnswer(0, g.player("Axel").clock.now()), "vor den Antworten nicht tippbar")
        g.time.advanceBy(MasterEngine.QUESTION_LEAD_MS)
        assertEquals(PlayerEngine.QuizStage.ANSWERS, g.view("Axel").quiz?.stage)
        assertEquals(MasterEngine.Phase.QUIZ_ANSWERS, g.master.snapshot().phase)
    }

    @Test
    fun `answer order differs between players`() {
        val g = game()
        g.master.startQuiz((1..6).map { question(it) })
        var differs = false
        repeat(6) {
            val orders = ids.map { g.ask(it).answers }.toSet()
            if (orders.size > 1) differs = true
            ids.forEach { g.answer(it, "Richtig ${g.ask(it).number}", 1_000) }
            g.time.advanceBy(5_000)
            g.master.nextQuestion()
            g.time.advanceBy(100)
        }
        assertTrue(differs)
    }

    @Test
    fun `fastest correct answer scores and reveal waits for everyone`() {
        val g = game()
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Falsch A1", 800) // am schnellsten, aber falsch
        g.answer("Peter", "Richtig 1", 2_000)
        g.answer("Clara", "Richtig 1", 3_500)
        // Frage 3 s, dann antwortet Clara als Letzte nach 3,5 s – weit vor Ablauf der 10 s.
        g.time.advanceBy(8_000)
        assertEquals(MasterEngine.Phase.QUIZ_REVEAL, g.master.snapshot().phase, "alle haben geantwortet → sofort auflösen")
        g.time.advanceBy(300)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 1L, "Clara" to 0L), g.scores())
        assertEquals(QuizOutcome.WRONG, g.result("Axel")?.outcome)
        assertEquals(QuizOutcome.RIGHT, g.result("Peter")?.outcome)
        assertEquals("+1", g.result("Peter")?.gained)
        assertEquals(QuizOutcome.RIGHT, g.result("Clara")?.outcome)
        assertEquals("", g.result("Clara")?.gained)
        // Die richtige Position zeigt in der Reihenfolge des Spielers auf den richtigen Text.
        for (id in ids) {
            val r = g.result(id)!!
            assertEquals("Richtig 1", g.ask(id).answers[r.correctPosition])
        }
        assertEquals("Falsch A1", g.ask("Axel").answers[g.result("Axel")!!.chosenPosition])
        assertEquals("Weil 1.", g.result("Peter")?.explanation)
        // Klänge: Tusch für Richtige, Wah-wah für Falsche.
        assertTrue(g.received.getValue("Peter").contains(Message.Sound(SoundEvent.TUSCH)))
        assertTrue(g.received.getValue("Axel").contains(Message.Sound(SoundEvent.WRONG)))
        assertTrue(g.master.canChat(), "Auflösung ist eine Pause")
    }

    @Test
    fun `no answer counts as none after the deadline`() {
        val g = game()
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Richtig 1", 1_000)
        g.answer("Peter", "Falsch B1", 1_500)
        g.time.advanceBy(3_500 + 9_000)
        assertEquals(MasterEngine.Phase.QUIZ_ANSWERS, g.master.snapshot().phase, "Clara hat noch Zeit")
        g.time.advanceBy(2_500)
        assertEquals(PlayerEngine.QuizStage.REVEALED, g.view("Clara").quiz?.stage)
        assertEquals(QuizOutcome.NONE, g.result("Clara")?.outcome)
        assertEquals(-1, g.result("Clara")?.chosenPosition)
        assertTrue(g.received.getValue("Clara").contains(Message.Sound(SoundEvent.WRONG)))
        val rows = g.master.snapshot().quiz!!.rows.associateBy { it.id }
        assertEquals(QuizOutcome.NONE, rows.getValue("Clara").outcome)
        assertEquals(0, rows.getValue("Axel").chosen)
    }

    @Test
    fun `late taps after the deadline are ignored`() {
        val g = game()
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Richtig 1", 10_500)
        g.time.advanceBy(20_000)
        assertEquals(QuizOutcome.NONE, g.result("Axel")?.outcome)
    }

    @Test
    fun `everyone scores mode`() {
        val g = game(Settings(quizAnswerMs = 10_000, quizScoring = QuizScoring.EVERYONE))
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Richtig 1", 1_000)
        g.answer("Peter", "Richtig 1", 2_000)
        g.answer("Clara", "Falsch C1", 3_000)
        g.time.advanceBy(10_000)
        assertEquals(mapOf("Axel" to 1L, "Peter" to 1L, "Clara" to 0L), g.scores())
    }

    @Test
    fun `countdown mode shortens the time after the first correct answer`() {
        val g = game(Settings(quizAnswerMs = 20_000, quizScoring = QuizScoring.COUNTDOWN, quizCountdownMs = 5_000))
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Falsch A1", 1_000)
        g.answer("Peter", "Richtig 1", 2_000)
        g.answer("Clara", "Richtig 1", 4_000)
        g.time.advanceBy(3_500 + 3_000)
        assertEquals(7_000L, g.view("Clara").quiz?.deadlineMs, "Countdown: 2 s + 5 s")
        g.time.advanceBy(10_000)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 5_000L, "Clara" to 3_000L), g.scores())
        assertEquals("+5,00 s", g.result("Peter")?.gained)
        assertEquals("3,00 s", g.result("Clara")?.score)
    }

    @Test
    fun `countdown closes the answers for players who wait too long`() {
        val g = game(Settings(quizAnswerMs = 20_000, quizScoring = QuizScoring.COUNTDOWN, quizCountdownMs = 5_000))
        g.master.startQuiz(listOf(question(1)))
        g.answer("Peter", "Richtig 1", 1_000)
        g.answer("Clara", "Richtig 1", 9_000) // nach dem Countdown
        g.time.advanceBy(3_500 + 8_000)
        assertEquals(MasterEngine.Phase.QUIZ_REVEAL, g.master.snapshot().phase)
        assertEquals(QuizOutcome.NONE, g.result("Clara")?.outcome)
        assertEquals(5_000L, g.scores().getValue("Peter"))
    }

    @Test
    fun `battle mode gives only the fastest the remaining time`() {
        val g = game(Settings(quizAnswerMs = 10_000, quizScoring = QuizScoring.BATTLE))
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Richtig 1", 2_500)
        g.answer("Peter", "Richtig 1", 1_500)
        g.answer("Clara", "Falsch A1", 500)
        g.time.advanceBy(10_000)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 8_500L, "Clara" to 0L), g.scores())
        assertEquals(ScoringMode.REMAINING_TIME, g.master.snapshot().scoring)
    }

    @Test
    fun `challenge can correct the answer and rescore`() {
        val g = game(Settings(quizAnswerMs = 10_000, quizScoring = QuizScoring.EVERYONE))
        g.master.startQuiz(listOf(question(1), question(2)))
        g.answer("Axel", "Richtig 1", 1_000)
        g.answer("Peter", "Falsch B1", 1_000)
        g.answer("Clara", "Falsch B1", 2_000)
        g.time.advanceBy(10_000)
        assertTrue(g.player("Peter").engine.challenge("B stimmt!"))
        assertFalse(g.player("Peter").engine.challenge("nochmal"), "nur einmal je Frage")
        g.time.advanceBy(300)
        val snap = g.master.snapshot().quiz!!
        assertEquals(listOf("Peter"), snap.challenges.map { it.name })
        assertEquals("B stimmt!", snap.challenges.single().reason)

        assertTrue(g.master.decideChallenge(ChallengeDecision.CORRECT, newCorrect = 2))
        g.time.advanceBy(300)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 1L, "Clara" to 1L), g.scores())
        val peter = g.result("Peter")!!
        assertTrue(peter.corrected)
        assertEquals(QuizOutcome.RIGHT, peter.outcome)
        assertEquals("Falsch B1", g.ask("Peter").answers[peter.correctPosition])
        assertEquals(QuizOutcome.WRONG, g.result("Axel")?.outcome)
        assertTrue(g.master.snapshot().quiz!!.challenges.isEmpty())
    }

    @Test
    fun `annulled question takes the points back`() {
        val g = game()
        g.master.startQuiz(listOf(question(1)))
        ids.forEach { g.answer(it, "Richtig 1", 1_000 + ids.indexOf(it) * 100L) }
        g.time.advanceBy(8_000)
        assertEquals(1L, g.scores().getValue("Axel"))
        var decided: ChallengeDecision? = null
        g.master.onQuizDecision = { _, d, _, _, _ -> decided = d }
        assertTrue(g.master.decideChallenge(ChallengeDecision.BLOCK))
        g.time.advanceBy(300)
        assertEquals(0L, g.scores().getValue("Axel"))
        assertEquals(QuizOutcome.ANNULLED, g.result("Axel")?.outcome)
        assertEquals(ChallengeDecision.BLOCK, decided)
    }

    @Test
    fun `correcting in countdown mode restarts the countdown at the first now correct answer`() {
        val g = game(Settings(quizAnswerMs = 20_000, quizScoring = QuizScoring.COUNTDOWN, quizCountdownMs = 5_000))
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Falsch B1", 1_000)
        g.answer("Peter", "Richtig 1", 2_000)
        g.answer("Clara", "Falsch B1", 4_000)
        g.time.advanceBy(3_500 + 5_000)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 5_000L, "Clara" to 0L), g.scores())
        var after: List<ParticipantResult>? = null
        g.master.onQuizDecision = { _, _, _, _, a -> after = a }
        assertTrue(g.master.decideChallenge(ChallengeDecision.CORRECT, newCorrect = 2))
        g.time.advanceBy(300)
        assertEquals(mapOf("Axel" to 5_000L, "Peter" to 0L, "Clara" to 2_000L), g.scores())
        assertEquals("+2,00 s", g.result("Clara")?.gained)
        assertEquals(listOf(true, false, true), after!!.map { it.correct })
    }

    @Test
    fun `annul and block take back times in every scoring mode`() {
        for (scoring in QuizScoring.entries) for (decision in listOf(ChallengeDecision.ANNUL, ChallengeDecision.BLOCK)) {
            val g = game(Settings(quizAnswerMs = 10_000, quizScoring = scoring, quizCountdownMs = 5_000))
            g.master.startQuiz(listOf(question(1), question(2)))
            g.answer("Axel", "Richtig 1", 1_000)
            g.answer("Peter", "Richtig 1", 2_000)
            g.answer("Clara", "Falsch A1", 500)
            g.time.advanceBy(10_000)
            assertTrue(g.scores().getValue("Axel") > 0, "$scoring")
            var before: List<ParticipantResult>? = null
            var after: List<ParticipantResult>? = listOf()
            g.master.onQuizDecision = { _, _, _, b, a -> before = b; after = a }
            assertTrue(g.master.decideChallenge(decision))
            g.time.advanceBy(300)
            assertEquals(mapOf("Axel" to 0L, "Peter" to 0L, "Clara" to 0L), g.scores(), "$scoring $decision")
            assertEquals(3, before!!.size)
            assertNull(after, "zählt nicht mehr")
            assertEquals(QuizOutcome.ANNULLED, g.result("Peter")?.outcome)
            assertEquals("", g.result("Axel")?.gained)
            // Rangliste der Spieler ist ebenfalls zurückgesetzt.
            val board = g.received.getValue("Axel").filterIsInstance<Message.Scoreboard>().last()
            assertTrue(board.entries.all { it.score == 0L }, "$scoring $decision")
        }
    }

    @Test
    fun `replacement question takes the same number and the old one leaves no trace`() {
        val g = game(Settings(quizAnswerMs = 10_000, quizScoring = QuizScoring.EVERYONE))
        g.master.startQuiz(listOf(question(1), question(2)))
        ids.forEach { g.answer(it, "Richtig 1", 1_000) }
        g.time.advanceBy(10_000)
        assertEquals(1L, g.scores().getValue("Axel"))
        var decided: ChallengeDecision? = null
        g.master.onQuizDecision = { _, d, _, _, a -> decided = d; assertNull(a) }
        assertTrue(g.master.replaceQuestion(question(9)))
        assertEquals(ChallengeDecision.REPLACE, decided)
        assertFalse(g.master.decideChallenge(ChallengeDecision.REPLACE))
        g.time.advanceBy(300)
        val ask = g.ask("Peter")
        assertEquals(1, ask.number)
        assertEquals(2, ask.total)
        assertEquals("Frage 9?", ask.text)
        assertEquals(0L, g.scores().getValue("Axel"))
        assertEquals(setOf("q9", "q2"), g.master.quizQuestionIds)
        ids.forEach { g.answer(it, "Richtig 9", 1_000) }
        g.time.advanceBy(10_000)
        assertEquals(1L, g.scores().getValue("Axel"))
        var replay: Message.Replay? = null
        g.master.onReplay = { replay = it }
        assertTrue(g.master.showReplay())
        assertEquals(2, replay!!.frames.size, "Start + eine gewertete Frage")
    }

    @Test
    fun `a late player joins after the current question and plays on from zero`() {
        val g = game(Settings(quizAnswerMs = 10_000, quizScoring = QuizScoring.EVERYONE))
        g.master.startQuiz(listOf(question(1), question(2)))
        g.time.advanceBy(3_500)
        assertFalse(g.master.canAcceptJoin(), "nicht mitten in der Frage")
        ids.forEach { g.answer(it, "Richtig 1", 1_000) }
        g.time.advanceBy(10_000)
        assertTrue(g.master.canAcceptJoin(), "bei der Auflösung")
        g.addPlayer("Dora", 0, 50)
        g.syncClocks()
        assertTrue(g.master.nextQuestion())
        g.time.advanceBy(300)
        assertEquals(2, g.ask("Dora").number)
        (ids + "Dora").forEach { g.answer(it, "Richtig 2", 1_000) }
        g.time.advanceBy(10_000)
        assertEquals(2L, g.scores().getValue("Axel"))
        assertEquals(1L, g.scores().getValue("Dora"))
    }

    @Test
    fun `skip goes straight to the next question and the quiz ends after the last`() {
        val g = game()
        var skipped: String? = null
        val revealed = mutableListOf<String>()
        g.master.onQuizSkipped = { q, _ -> skipped = q.id }
        g.master.onQuizRevealed = { q, _ -> revealed += q.id }
        g.master.startQuiz(listOf(question(1), question(2)))
        g.time.advanceBy(1_000)
        assertTrue(g.master.skipQuestion())
        assertEquals("q1", skipped)
        g.time.advanceBy(300)
        assertEquals(2, g.view("Axel").quiz?.number)
        ids.forEach { g.answer(it, "Richtig 2", 1_000) }
        g.time.advanceBy(8_000)
        assertEquals(listOf("q2"), revealed)
        assertFalse(g.master.snapshot().quiz!!.hasNext)
        assertTrue(g.master.nextQuestion())
        g.time.advanceBy(300)
        val snap = g.master.snapshot()
        assertEquals(MasterEngine.Phase.READY, snap.phase)
        assertTrue(snap.quiz!!.finished)
        assertNull(g.view("Axel").quiz)
        assertTrue(g.view("Axel").showBoard)
        assertTrue(g.received.getValue("Peter").contains(Message.Sound(SoundEvent.ROUND_END)))
    }

    @Test
    fun `stats callback gets every participant`() {
        val g = game()
        var results: List<ParticipantResult> = emptyList()
        g.master.onQuizRevealed = { _, r -> results = r }
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Richtig 1", 2_500)
        g.answer("Peter", "Falsch A1", 1_000)
        g.time.advanceBy(20_000)
        val byName = results.associateBy { it.name }
        assertEquals(true, byName.getValue("Axel").correct)
        assertEquals(0.25, byName.getValue("Axel").timeShare, 0.001)
        assertEquals(false, byName.getValue("Peter").correct)
        assertNull(byName.getValue("Clara").correct)
    }

    @Test
    fun `a disconnect does not block the reveal`() {
        val g = game()
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Richtig 1", 1_000)
        g.answer("Peter", "Richtig 1", 1_200)
        g.time.advanceBy(5_000)
        g.master.removePlayer("Clara")
        g.time.advanceBy(100)
        assertEquals(MasterEngine.Phase.QUIZ_REVEAL, g.master.snapshot().phase)
    }

    @Test
    fun `players can join between questions`() {
        val g = game()
        g.master.startQuiz(listOf(question(1), question(2)))
        assertFalse(g.master.canAcceptJoin())
        ids.forEach { g.answer(it, "Richtig 1", 1_000) }
        g.time.advanceBy(8_000)
        assertTrue(g.master.canAcceptJoin())
        val dora = g.addPlayer("Dora", 0, 50)
        g.time.advanceBy(200)
        assertEquals(GameKind.QUIZ, dora.engine.view.kind)
        g.master.nextQuestion()
        g.time.advanceBy(200)
        assertEquals(2, dora.engine.view.quiz?.number)
    }

    @Test
    fun `switching the game kind starts a new game`() {
        val g = game()
        g.master.startQuiz(listOf(question(1)))
        ids.forEach { g.answer(it, "Richtig 1", 1_000 + 10L * ids.indexOf(it)) }
        g.time.advanceBy(8_000)
        g.master.nextQuestion()
        assertEquals(1L, g.scores().getValue("Axel"))
        assertTrue(g.master.chooseKind(GameKind.BUZZER))
        g.time.advanceBy(200)
        assertEquals(0L, g.scores().getValue("Axel"))
        assertEquals(GameKind.BUZZER, g.view("Peter").kind)
        assertNull(g.master.snapshot().quiz)
    }

    @Test
    fun `typing indicator shows and expires`() {
        val g = game()
        g.player("Peter").engine.sendTyping(true)
        g.time.advanceBy(200)
        assertTrue(g.master.snapshot().someoneTyping)
        g.time.advanceBy(MasterEngine.TYPING_TIMEOUT_MS)
        assertFalse(g.master.snapshot().someoneTyping)
        g.player("Peter").engine.sendTyping(true)
        g.time.advanceBy(200)
        g.player("Peter").engine.sendChat(ALL_ADDRESS, "Hallo")
        g.time.advanceBy(200)
        assertFalse(g.master.snapshot().someoneTyping, "Senden beendet das Tippen")
    }

    @Test
    fun `quiz messages survive the codec`() {
        val msgs = listOf(
            Message.Mode(GameKind.QUIZ), Message.Mode(null),
            Message.QuizAsk(3, 12, "Küche & Genuss", QuizLevel.HARD, "Was ist | echtes = Wasabi?", listOf("a,b", "c:d", "e&f", "ä ö"), 123, 3_123, 20_000),
            Message.QuizAnswer(3, 2, 4_321),
            Message.QuizDeadline(3, 7_000),
            Message.QuizResult(3, 1, -1, QuizOutcome.NONE, "", "4", "Erklärung | mit Zeichen", "https://x.y/z?a=b&c=d", true),
            Message.QuizChallenge(3, "Stimmt nicht!"),
            Message.Typing(true),
            Message.ApkRequest,
            Message.ApkInfo(987654321L, 12, 12_345_678),
        )
        for (m in msgs) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test
    fun `peer info carries build and protocol`() {
        val text = PeerInfo.format("Axel [Test]", 12)
        assertEquals("Axel [Test] [12/$PROTOCOL_VERSION]", text)
        val p = PeerInfo.parse(text)
        assertEquals("Axel [Test]", p.name)
        assertEquals(12, p.build)
        assertTrue(p.compatible)
        assertFalse(p.updateOnly)
        assertTrue(PeerInfo.parse(PeerInfo.format("Peter", 13, updateOnly = true)).updateOnly)
        val old = PeerInfo.parse("Clara")
        assertTrue(old.legacy)
        assertFalse(old.compatible)
        assertEquals("Clara", old.name)
        assertFalse(PeerInfo.parse("Dora [14/9]").compatible)
    }

    @Test
    fun `a running quiz snapshot hides nothing the master needs`() {
        val g = game()
        g.master.startQuiz(listOf(question(1)))
        g.answer("Axel", "Falsch C1", 1_000)
        g.time.advanceBy(3_500 + 1_200)
        val snap = g.master.snapshot().quiz
        assertNotNull(snap)
        val axel = snap.rows.first { it.id == "Axel" }
        assertTrue(axel.answered)
        assertEquals(1_000L, axel.elapsedMs)
        assertNull(axel.chosen, "Antwort erst bei der Auflösung")
    }
}
