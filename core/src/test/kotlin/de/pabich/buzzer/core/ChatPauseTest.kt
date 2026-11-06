package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kurznachrichten bleiben während einer Spielpause möglich (Wunsch vom 06.11.2026). */
class ChatPauseTest {
    private fun question() = QuizQuestion(
        id = "q1", category = "Technik", level = QuizLevel.MEDIUM, text = "Frage?",
        answers = listOf("Richtig", "Falsch A", "Falsch B", "Falsch C"), correctIndex = 0,
        explanation = "", source = "",
    )

    @Test
    fun `chat works while the game is paused mid question`() {
        val g = SimGame(Settings(quizAnswerMs = 15_000, messagesEnabled = true))
        g.addPlayer("Peter", clockOffsetMs = 0, latencyMs = 40)
        g.addPlayer("Clara", clockOffsetMs = 0, latencyMs = 40)
        g.syncClocks()
        assertTrue(g.master.chooseKind(GameKind.QUIZ))
        g.time.advanceBy(200)
        assertTrue(g.master.startQuiz(listOf(question())))
        g.time.advanceBy(4_000)
        assertFalse(g.master.canChat(), "während der Frage kein Chat")

        assertTrue(g.master.setPaused(true))
        assertTrue(g.master.canChat(), "in der Pause ist Chat möglich")
        g.master.onMessage("Peter", Message.ChatSend(ALL_ADDRESS, "Kurz Pizza holen?"))
        g.time.advanceBy(500)
        assertTrue(g.received.getValue("Clara").any { it is Message.Chat && it.text == "Kurz Pizza holen?" })

        assertTrue(g.master.setPaused(false))
        assertFalse(g.master.canChat(), "nach der Pause läuft die Frage weiter – wieder ohne Chat")
    }
}
