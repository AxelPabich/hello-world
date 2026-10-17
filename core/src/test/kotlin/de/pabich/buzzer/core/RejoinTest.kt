package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kurzer Funkabbruch mitten in einer Quizfrage: Der Spieler steigt wieder ein (Punkt 76). */
class RejoinTest {
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

    /** Tippt die richtige Antwort, sobald [id] die Frage sieht, [afterMs] später. */
    private fun SimGame.tapRight(id: PlayerId, afterMs: Long) {
        val p = player(id)
        val ask = received.getValue(id).filterIsInstance<Message.QuizAsk>().last()
        val pos = ask.answers.indexOf("Richtig")
        val at = maxOf(ask.answersAtLocal - p.clockOffsetMs, time.now)
        time.schedule(at + afterMs - time.now) { p.engine.tapAnswer(pos, p.clock.now()) }
    }

    @Test
    fun `player who drops mid question can still answer`() {
        val g = quizGame()
        g.time.advanceBy(4_000) // Antworten sind offen
        g.master.removePlayer("Peter")
        g.time.advanceBy(2_000)
        // Peter kommt über eine neue Verbindung zurück.
        g.addPlayer("Peter#2", clockOffsetMs = 700, latencyMs = 40, name = "Peter")
        g.time.advanceBy(1_500)
        val ask = g.received.getValue("Peter#2").filterIsInstance<Message.QuizAsk>().single()
        assertEquals(1, ask.number)
        // Die Restzeit stimmt: Antworten waren auf Peters Uhr schon vor gut 4 s offen.
        val sinceAnswers = g.player("Peter#2").clock.now() - ask.answersAtLocal
        assertTrue(sinceAnswers in 4_000..5_500, "Antwortzeit läuft seit $sinceAnswers ms")
        g.tapRight("Peter#2", 500)
        g.time.advanceBy(15_000)
        assertTrue(g.masterLog.any { "Peter steigt wieder in Frage 1 ein" in it })
        val result = g.received.getValue("Peter#2").filterIsInstance<Message.QuizResult>().single()
        assertEquals(QuizOutcome.RIGHT, result.outcome)
        assertEquals(1L, g.row("Peter#2").scoreValue)
        assertEquals(1, g.master.snapshot().players.count { it.name == "Peter" }, "kein doppelter Eintrag")
    }

    @Test
    fun `answer given before the drop still counts`() {
        val g = quizGame()
        g.tapRight("Peter", 800)
        g.time.advanceBy(5_000)
        g.master.removePlayer("Peter")
        g.time.advanceBy(1_000)
        g.addPlayer("Peter#2", clockOffsetMs = 700, latencyMs = 40, name = "Peter")
        g.time.advanceBy(20_000)
        assertTrue(g.masterLog.any { "hatte schon geantwortet" in it })
        assertEquals(QuizOutcome.RIGHT, g.received.getValue("Peter#2").filterIsInstance<Message.QuizResult>().single().outcome)
        assertEquals(1L, g.row("Peter#2").scoreValue)
    }

    @Test
    fun `new player mid question waits for the next one`() {
        val g = quizGame()
        g.time.advanceBy(4_000)
        g.addPlayer("Simone", clockOffsetMs = 0, latencyMs = 40)
        g.time.advanceBy(2_000)
        assertTrue(g.received.getValue("Simone").none { it is Message.QuizAsk })
    }
}
