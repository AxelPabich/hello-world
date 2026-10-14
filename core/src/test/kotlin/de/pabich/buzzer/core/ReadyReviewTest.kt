package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadyReviewTest {
    private fun game(): SimGame {
        val g = SimGame(Settings(quizAnswerMs = 10_000))
        listOf("Axel", "Peter", "Clara").forEachIndexed { i, id -> g.addPlayer(id, 100L * i, 50) }
        g.syncClocks()
        return g
    }

    @Test
    fun `get ready asks every player and signals go when all confirmed`() {
        val g = game()
        val sounds = mutableListOf<SoundEvent>()
        g.master.onSound = { sounds += it }
        assertTrue(g.master.askReady())
        g.time.advanceBy(200)
        assertTrue(listOf("Axel", "Peter", "Clara").all { g.view(it).readyAsk != null })
        assertEquals(listOf("Axel", "Peter", "Clara"), g.master.snapshot().ready?.waitingNames)
        assertTrue(g.player("Axel").engine.confirmReady())
        assertFalse(g.player("Axel").engine.confirmReady(), "nur einmal")
        g.player("Peter").engine.confirmReady()
        g.time.advanceBy(200)
        assertEquals(listOf("Clara"), g.master.snapshot().ready?.waitingNames)
        assertFalse(SoundEvent.GO in sounds)
        g.player("Clara").engine.confirmReady()
        g.time.advanceBy(200)
        assertTrue(g.master.snapshot().ready!!.allReady)
        assertTrue(SoundEvent.GO in sounds)
        // Die Runde beginnt: Abfrage schließt überall.
        assertTrue(g.master.release())
        g.time.advanceBy(200)
        assertNull(g.master.snapshot().ready)
        assertNull(g.view("Axel").readyAsk)
    }

    @Test
    fun `round can start without everyone ready`() {
        val g = game()
        g.master.askReady()
        g.time.advanceBy(200)
        g.player("Axel").engine.confirmReady()
        g.time.advanceBy(200)
        assertTrue(g.master.release())
        g.time.advanceBy(200)
        assertNull(g.view("Peter").readyAsk)
    }

    @Test
    fun `stale ready answers are ignored`() {
        val g = game()
        g.master.askReady()
        g.time.advanceBy(200)
        g.master.cancelReady()
        g.master.askReady()
        g.master.onMessage("Peter", Message.Ready(1))
        assertEquals(listOf("Axel", "Peter", "Clara"), g.master.snapshot().ready?.waitingNames)
    }

    @Test
    fun `review shows a ticker until the master decides`() {
        val g = game()
        g.master.chooseKind(GameKind.QUIZ)
        g.master.startQuiz(listOf(QuizQuestion("q", "Technik", QuizLevel.EASY, "F?", listOf("R", "A", "B", "C"), 0, "", "")))
        g.time.advanceBy(20_000)
        assertFalse(g.master.snapshot().reviewing)
        assertTrue(g.master.startReview())
        g.time.advanceBy(200)
        assertTrue(g.view("Peter").reviewing)
        assertTrue(g.master.snapshot().reviewing)
        g.master.decideChallenge(ChallengeDecision.ANNUL)
        g.time.advanceBy(200)
        assertFalse(g.view("Peter").reviewing)
        g.master.startReview()
        g.master.endReview()
        g.time.advanceBy(200)
        assertFalse(g.view("Clara").reviewing)
    }

    @Test
    fun `ready and review messages survive the codec`() {
        for (m in listOf(Message.ReadyCheck(3, true), Message.ReadyCheck(3, false), Message.Ready(3), Message.Review(true))) {
            assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
        }
        assertTrue(SoundSynth.render(SoundEvent.GO).isNotEmpty())
    }
}
