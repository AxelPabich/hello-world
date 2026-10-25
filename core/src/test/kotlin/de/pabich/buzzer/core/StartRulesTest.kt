package de.pabich.buzzer.core

import de.pabich.buzzer.core.MasterEngine.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spielregeln bestätigen, Lesezeit, gleiche Antwortreihenfolge, Endergebnis (Punkte 86–90). */
class StartRulesTest {
    private fun question(id: String = "q1", text: String = "Frage?") = QuizQuestion(
        id = id, category = "Technik", level = QuizLevel.MEDIUM, text = text,
        answers = listOf("Richtig", "Falsch A", "Falsch B", "Falsch C"), correctIndex = 0, explanation = "", source = "",
    )

    private fun game(settings: Settings = Settings(quizAnswerMs = 15_000)): SimGame {
        val g = SimGame(settings)
        g.addPlayer("Peter", clockOffsetMs = 700, latencyMs = 40)
        g.addPlayer("Clara", clockOffsetMs = -300, latencyMs = 60)
        g.syncClocks()
        assertTrue(g.master.chooseKind(GameKind.QUIZ))
        g.time.advanceBy(200)
        return g
    }

    private fun SimGame.asks(id: PlayerId) = received.getValue(id).filterIsInstance<Message.QuizAsk>()

    @Test
    fun `new messages and settings survive the codec`() {
        val ms = listOf(
            Message.Rules("Quiz", listOf("Zeit" to "20 s"), confirmId = 3), Message.Rules("Quiz", emptyList()),
            Message.RulesOk(3), Message.RulesDone(3), Message.ProfileRequest, Message.QuizEnd(true), Message.ReadSpeed(95),
        )
        for (m in ms) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
        val s = Settings(quizShuffleAnswers = false, quizReadAdapt = false, rulesConfirm = false)
        assertEquals(s, SettingsCodec.fromMap(SettingsCodec.toMap(s)).settings)
    }

    @Test
    fun `rules are shown to everyone and the game waits for all confirmations`() {
        val g = game()
        var confirmed = 0
        g.master.onRulesConfirmed = { confirmed++ }
        assertTrue(g.master.requestRulesConfirmation("Quiz", listOf("Fragen" to "1")))
        g.time.advanceBy(300)
        val id = g.received.getValue("Peter").filterIsInstance<Message.Rules>().last().confirmId
        assertTrue(id > 0)
        assertEquals(setOf(g.master.masterReadyLabel, "Peter", "Clara"), g.master.snapshot().rules!!.waitingNames.toSet())
        g.master.onMessage("Peter", Message.RulesOk(id))
        g.master.confirmMasterRules()
        assertEquals(0, confirmed)
        assertEquals(listOf("Clara"), g.master.snapshot().rules!!.waitingNames)
        g.master.onMessage("Clara", Message.RulesOk(id + 1)) // falsche Abfrage
        assertEquals(0, confirmed)
        g.master.onMessage("Clara", Message.RulesOk(id))
        assertEquals(1, confirmed)
        assertEquals(null, g.master.snapshot().rules)
        g.time.advanceBy(300)
        assertTrue(g.received.getValue("Clara").any { it is Message.RulesDone })
    }

    @Test
    fun `a player who drops out no longer blocks and old apps are exempt`() {
        val g = game()
        var confirmed = 0
        g.master.onRulesConfirmed = { confirmed++ }
        assertTrue(g.master.requestRulesConfirmation("Quiz", emptyList(), exempt = setOf("Clara")))
        g.master.confirmMasterRules()
        g.master.onMessage("Peter", Message.RulesOk(g.received.getValue("Peter").filterIsInstance<Message.Rules>().last().confirmId))
        assertEquals(1, confirmed, "Clara (alte App) wird nicht erwartet")
        assertFalse(g.master.requestRulesConfirmation("Quiz", emptyList(), exempt = setOf("Peter", "Clara")), "niemand zu fragen")
    }

    @Test
    fun `master can start without waiting`() {
        val g = game()
        var confirmed = 0
        g.master.onRulesConfirmed = { confirmed++ }
        g.master.requestRulesConfirmation("Quiz", emptyList())
        g.master.endRulesConfirmation(proceed = true)
        assertEquals(1, confirmed)
    }

    @Test
    fun `lead time follows the length of the question and the slowest reader`() {
        val short = game()
        assertTrue(short.master.startQuiz(listOf(question(text = "Kurz?"))))
        short.time.advanceBy(1_000)
        val shortAsk = short.asks("Peter").single()
        val shortLead = shortAsk.answersAtLocal - shortAsk.questionAtLocal

        val long = game()
        assertTrue(long.master.startQuiz(listOf(question(text = "Dies ist eine ziemlich lange Frage, die man erst in Ruhe lesen muss, bevor man antworten kann – oder?"))))
        long.time.advanceBy(1_000)
        val longAsk = long.asks("Peter").single()
        val longLead = longAsk.answersAtLocal - longAsk.questionAtLocal
        assertTrue(longLead > shortLead + 3_000, "lange Frage → längere Wartezeit ($shortLead / $longLead)")

        val slow = game()
        slow.master.onMessage("Peter", Message.ReadSpeed(200))
        assertTrue(slow.master.startQuiz(listOf(question(text = "Dies ist eine ziemlich lange Frage, die man erst in Ruhe lesen muss, bevor man antworten kann – oder?"))))
        slow.time.advanceBy(1_000)
        val slowAsk = slow.asks("Clara").single()
        assertTrue(slowAsk.answersAtLocal - slowAsk.questionAtLocal > longLead + 3_000, "langsamer Leser → noch länger")
        assertEquals(200, slow.master.snapshot().players.first { it.id == "Peter" }.readMsPerChar)

        val off = game(Settings(quizAnswerMs = 15_000, quizReadAdapt = false))
        assertTrue(off.master.startQuiz(listOf(question(text = "Dies ist eine ziemlich lange Frage, die man erst in Ruhe lesen muss, bevor man antworten kann – oder?"))))
        off.time.advanceBy(1_000)
        val offAsk = off.asks("Peter").single()
        assertEquals(MasterEngine.QUESTION_LEAD_MS, offAsk.answersAtLocal - offAsk.questionAtLocal)
    }

    @Test
    fun `answer order is the same for everyone when shuffling is off`() {
        val g = game(Settings(quizAnswerMs = 15_000, quizShuffleAnswers = false))
        assertTrue(g.master.startQuiz(listOf(question("a"), question("b"))))
        g.time.advanceBy(1_000)
        assertEquals(g.asks("Peter").single().answers, g.asks("Clara").single().answers)

        // Mit Mischen bekommt (bei vielen Fragen sicher) mindestens einer eine andere Reihenfolge.
        var differ = false
        repeat(12) { i ->
            val m = game()
            assertTrue(m.master.startQuiz(listOf(question("m$i"))))
            m.time.advanceBy(1_000)
            if (m.asks("Peter").single().answers != m.asks("Clara").single().answers) differ = true
        }
        assertTrue(differ, "gemischt unterscheiden sich die Reihenfolgen")
    }

    @Test
    fun `players get the end result when the quiz ends or is aborted`() {
        val g = game()
        assertTrue(g.master.startQuiz(listOf(question("a"), question("b"))))
        g.time.advanceBy(2_000)
        assertTrue(g.master.stopQuiz())
        g.time.advanceBy(1_000)
        assertTrue(g.view("Peter").finalResult)
        assertTrue(g.view("Peter").finalAborted)
        g.player("Peter").engine.dismissFinalResult()
        assertFalse(g.view("Peter").finalResult)
        assertEquals(Phase.READY, g.master.snapshot().phase)

        // Nach regulärem Ende ebenfalls.
        val h = game()
        assertTrue(h.master.startQuiz(listOf(question("a"))))
        h.time.advanceBy(30_000)
        assertEquals(Phase.QUIZ_REVEAL, h.master.snapshot().phase)
        assertTrue(h.master.nextQuestion())
        h.time.advanceBy(1_000)
        assertTrue(h.view("Clara").finalResult)
        assertFalse(h.view("Clara").finalAborted)
    }

    @Test
    fun `a player can request the profile of the running game`() {
        val g = game()
        val peter = g.player("Peter")
        peter.engine.requestProfile()
        g.time.advanceBy(300)
        val profile = g.received.getValue(peter.id).filterIsInstance<Message.Profile>().single()
        assertEquals(SettingsCodec.toMap(g.master.settings), profile.values)
    }
}
