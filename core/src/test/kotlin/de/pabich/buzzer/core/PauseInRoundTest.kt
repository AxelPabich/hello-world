package de.pabich.buzzer.core

import de.pabich.buzzer.core.MasterEngine.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pause mitten in der Runde (Punkt 85). */
class PauseInRoundTest {
    private fun question() = QuizQuestion(
        id = "q1", category = "Technik", level = QuizLevel.MEDIUM, text = "Frage?",
        answers = listOf("Richtig", "Falsch A", "Falsch B", "Falsch C"), correctIndex = 0,
        explanation = "", source = "",
    )

    private fun quizGame(): SimGame {
        val g = SimGame(Settings(quizAnswerMs = 15_000))
        g.addPlayer("Peter", clockOffsetMs = 700, latencyMs = 40)
        g.addPlayer("Clara", clockOffsetMs = -300, latencyMs = 60)
        g.syncClocks()
        assertTrue(g.master.chooseKind(GameKind.QUIZ))
        g.time.advanceBy(200)
        assertTrue(g.master.startQuiz(listOf(question(), question().copy(id = "q2"))))
        return g
    }

    private fun SimGame.tapRight(id: PlayerId) {
        val ask = received.getValue(id).filterIsInstance<Message.QuizAsk>().last()
        assertTrue(player(id).engine.tapAnswer(ask.answers.indexOf("Richtig"), player(id).clock.now()), "$id konnte nicht antworten")
    }

    @Test
    fun `quiz question stands still during the pause and continues afterwards`() {
        val g = quizGame()
        g.time.advanceBy(8_000) // Antworten sind seit ein paar Sekunden offen
        assertEquals(Phase.QUIZ_ANSWERS, g.master.snapshot().phase)
        val deadlineBefore = g.master.snapshot().quiz!!.deadline - g.time.now
        assertTrue(g.master.setPaused(true))
        g.time.advanceBy(60_000) // viel länger als die Antwortzeit
        assertEquals(Phase.QUIZ_ANSWERS, g.master.snapshot().phase, "keine Auflösung während der Pause")
        assertTrue(g.view("Peter").paused)
        // Der Zähler des Spielleiters steht.
        val deadlineDuring = g.master.snapshot().quiz!!.deadline - g.time.now
        assertTrue(kotlin.math.abs(deadlineBefore - deadlineDuring) < 50, "Zähler steht: $deadlineBefore ≈ $deadlineDuring")

        assertTrue(g.master.setPaused(false))
        g.time.advanceBy(300)
        assertFalse(g.view("Peter").paused)
        assertEquals(PlayerEngine.QuizStage.ANSWERS, g.view("Peter").quiz!!.stage, "Antworten wieder offen")
        // Antworten geht wieder – die Restzeit ist ungefähr die vor der Pause.
        g.tapRight("Peter")
        g.tapRight("Clara")
        g.time.advanceBy(1_000)
        assertEquals(Phase.QUIZ_REVEAL, g.master.snapshot().phase)
        assertEquals(QuizOutcome.RIGHT, g.received.getValue("Peter").filterIsInstance<Message.QuizResult>().single().outcome)
    }

    @Test
    fun `unanswered question is revealed only after the remaining time`() {
        val g = quizGame()
        g.time.advanceBy(8_000)
        val remaining = g.master.snapshot().quiz!!.deadline - g.time.now
        assertTrue(g.master.setPaused(true))
        g.time.advanceBy(120_000)
        assertTrue(g.master.setPaused(false))
        g.time.advanceBy(remaining - 2_000)
        assertEquals(Phase.QUIZ_ANSWERS, g.master.snapshot().phase, "Restzeit läuft noch")
        g.time.advanceBy(4_000)
        assertEquals(Phase.QUIZ_REVEAL, g.master.snapshot().phase)
    }

    @Test
    fun `answer tapped just before the pause still counts, later ones do not`() {
        val g = quizGame()
        g.time.advanceBy(6_000)
        g.tapRight("Peter")
        g.time.advanceBy(500)
        assertTrue(g.master.setPaused(true))
        g.time.advanceBy(1_000)
        assertTrue(g.master.snapshot().quiz!!.rows.first { it.name == "Peter" }.answered)
        // Clara tippt (aus Sicht des Spielers) erst nach Beginn der Pause und kommt als verspätete Nachricht an.
        g.master.onMessage("Clara", Message.QuizAnswer(1, 0, 9_000))
        assertFalse(g.master.snapshot().quiz!!.rows.first { it.name == "Clara" }.answered)
    }

    @Test
    fun `quiz shift survives the codec`() {
        val m = Message.QuizShift(3, 12_345)
        assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test
    fun `buzzer round before the decision is aborted and can be restarted`() {
        val g = SimGame()
        g.addPlayer("A", clockOffsetMs = 0, latencyMs = 40)
        g.addPlayer("B", clockOffsetMs = 500, latencyMs = 40)
        g.syncClocks()
        assertTrue(g.master.release())
        g.time.advanceBy(300)
        val round = g.master.snapshot().round
        assertTrue(g.master.setPaused(true))
        assertEquals(Phase.READY, g.master.snapshot().phase)
        assertEquals(round - 1, g.master.snapshot().round, "die Runde zählt nicht")
        g.time.advanceBy(20_000)
        assertEquals(Phase.READY, g.master.snapshot().phase)
        assertFalse(g.master.release(), "während der Pause nicht freigeben")
        assertTrue(g.master.setPaused(false))
        assertTrue(g.master.release())
        assertEquals(round, g.master.snapshot().round)
    }

    @Test
    fun `answer time of the buzzer stands still during the pause`() {
        val g = SimGame(Settings(answerLimitEnabled = true, answerLimitMs = 5_000))
        g.addPlayer("A", clockOffsetMs = 0, latencyMs = 40)
        g.addPlayer("B", clockOffsetMs = 500, latencyMs = 40)
        g.syncClocks()
        assertTrue(g.master.release())
        g.scheduleTap("A", 200)
        g.scheduleTap("B", 400)
        while (g.master.snapshot().phase != Phase.ANSWERING) g.time.advanceBy(100)
        g.time.advanceBy(2_000) // A hat noch etwa 3 s
        assertEquals(Display.GREEN, g.view("A").display)
        assertTrue(g.master.setPaused(true))
        assertEquals(null, g.master.snapshot().answerDeadline)
        g.time.advanceBy(30_000)
        assertEquals(Phase.ANSWERING, g.master.snapshot().phase)
        assertEquals(Display.GREEN, g.view("A").display, "A ist in der Pause nicht ausgeschieden")
        assertTrue(g.master.setPaused(false))
        assertTrue(g.master.snapshot().answerDeadline != null)
        g.time.advanceBy(1_500)
        assertEquals(Display.GREEN, g.view("A").display, "die Restzeit läuft noch")
        g.time.advanceBy(3_000)
        assertEquals(Display.GRAY, g.view("A").display, "danach läuft sie ab")
        assertEquals(Display.GREEN, g.view("B").display)
    }

    private fun pausedQuestion(): SimGame {
        val g = quizGame()
        g.time.advanceBy(8_000)
        assertTrue(g.master.setPaused(true))
        assertTrue(g.master.snapshot().questionPaused)
        return g
    }

    @Test
    fun `paused question can be asked again from the start`() {
        val g = pausedQuestion()
        g.tapRight("Peter") // Antwort vor der Pause zählt nicht mehr, wenn neu gestellt wird
        assertTrue(g.master.restartQuestion())
        g.time.advanceBy(300)
        val snap = g.master.snapshot()
        assertFalse(snap.paused)
        assertFalse(snap.questionPaused)
        assertEquals(1, snap.quiz!!.number, "dieselbe Frage, gleiche Nummer")
        assertEquals("q1", snap.quiz!!.question.id)
        assertFalse(g.view("Peter").paused)
        assertTrue(g.received.getValue("Peter").filterIsInstance<Message.QuizAsk>().size >= 2)
    }

    @Test
    fun `paused question can be aborted without scoring`() {
        val g = pausedQuestion()
        assertTrue(g.master.abortQuestion())
        val snap = g.master.snapshot()
        assertEquals(Phase.QUIZ_REVEAL, snap.phase)
        assertFalse(snap.paused)
        assertTrue(snap.quiz!!.annulled)
        assertTrue(snap.players.all { it.scoreValue == 0L })
        assertEquals(QuizOutcome.ANNULLED, g.received.getValue("Peter").filterIsInstance<Message.QuizResult>().last().outcome)
    }

    @Test
    fun `paused question can be skipped`() {
        val g = pausedQuestion()
        assertTrue(g.master.skipQuestion())
        g.time.advanceBy(300)
        val snap = g.master.snapshot()
        assertFalse(snap.paused)
        assertEquals(2, snap.quiz!!.number)
    }

    @Test
    fun `master can call a challenge on the paused question`() {
        val g = pausedQuestion()
        assertTrue(g.master.challengeQuestion())
        val snap = g.master.snapshot()
        assertEquals(Phase.QUIZ_REVEAL, snap.phase)
        assertFalse(snap.paused)
        assertEquals(1, snap.quiz!!.challenges.size)
        assertTrue(snap.reviewing)
    }

    @Test
    fun `resuming a paused question still works`() {
        val g = pausedQuestion()
        assertTrue(g.master.setPaused(false))
        assertFalse(g.master.snapshot().questionPaused)
    }
}
