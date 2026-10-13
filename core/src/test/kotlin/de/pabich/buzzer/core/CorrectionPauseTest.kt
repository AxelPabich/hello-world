package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorrectionPauseTest {
    private val ids = listOf("Axel", "Peter", "Clara")

    private fun game(settings: Settings = Settings()): SimGame {
        val g = SimGame(settings)
        ids.forEachIndexed { i, id -> g.addPlayer(id, clockOffsetMs = i * 777L, latencyMs = 50) }
        g.syncClocks()
        return g
    }

    /** Runde, in der die Spieler in der Reihenfolge [order] buzzern; [correctAt] = wer richtig antwortet. */
    private fun SimGame.round(order: List<String>, reactions: List<Long>, correctAt: String?) {
        master.release()
        order.forEachIndexed { i, id -> scheduleTap(id, reactions[i]) }
        while (master.snapshot().phase != MasterEngine.Phase.ANSWERING) time.advanceBy(10)
        time.advanceBy(200)
        for (id in order) {
            if (id == correctAt) {
                master.markCorrect()
                break
            }
            master.markWrong()
        }
        time.advanceBy(500)
    }

    private fun SimGame.scores() = master.snapshot().players.associate { it.id to it.scoreValue }

    @Test
    fun `a point round can be given to another player`() {
        val g = game()
        g.round(listOf("Peter", "Axel", "Clara"), listOf(200, 400, 600), correctAt = "Peter")
        assertEquals(mapOf("Axel" to 0L, "Peter" to 1L, "Clara" to 0L), g.scores())
        assertEquals("Peter", g.master.snapshot().lastRoundWinner)

        assertTrue(g.master.correctLastRound("Clara"))
        g.time.advanceBy(300)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 0L, "Clara" to 1L), g.scores())
        assertEquals("1", g.view("Clara").score)
        assertEquals("0", g.view("Peter").score)
        assertEquals("Clara", g.master.snapshot().lastRoundWinner)
    }

    @Test
    fun `a round can be cancelled and the correction shows in the replay`() {
        val g = game()
        g.round(listOf("Peter", "Axel", "Clara"), listOf(200, 400, 600), correctAt = "Peter")
        g.master.nextRound()
        assertTrue(g.master.canCorrectLastRound()) // auch nach „Nächste Runde“
        assertTrue(g.master.correctLastRound(null))
        g.time.advanceBy(300)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 0L, "Clara" to 0L), g.scores())
        assertNull(g.master.snapshot().lastRoundWinner)
        assertEquals("0", g.view("Peter").score)

        var replay: Message.Replay? = null
        g.master.onReplay = { replay = it }
        g.master.showReplay()
        assertEquals(listOf(0L, 0L, 0L), replay!!.frames.last())
    }

    @Test
    fun `a round without a winner can get one later`() {
        val g = game()
        g.round(listOf("Peter", "Axel", "Clara"), listOf(200, 400, 600), correctAt = null)
        assertEquals(mapOf("Axel" to 0L, "Peter" to 0L, "Clara" to 0L), g.scores())
        assertTrue(g.master.correctLastRound("Axel"))
        assertEquals(1L, g.scores().getValue("Axel"))
    }

    @Test
    fun `time accounts are corrected with the reaction of that round`() {
        val g = game(Settings(scoring = ScoringMode.REMAINING_TIME, timeLimitMs = 10_000))
        g.round(listOf("Peter", "Axel", "Clara"), listOf(1_000, 3_000, 6_000), correctAt = "Peter")
        assertEquals(9_000L, g.scores().getValue("Peter"))
        g.master.correctLastRound("Axel")
        g.time.advanceBy(300)
        assertEquals(0L, g.scores().getValue("Peter"))
        assertEquals(7_000L, g.scores().getValue("Axel"))
        assertEquals("7,00 s", g.view("Axel").score)
    }

    @Test
    fun `no correction while a round is running`() {
        val g = game()
        assertFalse(g.master.canCorrectLastRound())
        g.round(listOf("Peter", "Axel", "Clara"), listOf(200, 400, 600), correctAt = "Peter")
        g.master.nextRound()
        g.master.release()
        assertFalse(g.master.correctLastRound("Axel"))
    }

    @Test
    fun `pause covers all players and blocks the release`() {
        val g = game()
        assertTrue(g.master.setPaused(true))
        g.time.advanceBy(200)
        assertTrue(ids.all { g.view(it).paused })
        assertTrue(g.master.snapshot().paused)
        assertFalse(g.master.release())

        // Wer während der Pause beitritt, sieht sie auch.
        val late = g.addPlayer("Dora", clockOffsetMs = 0, latencyMs = 50)
        g.time.advanceBy(200)
        assertTrue(late.engine.view.paused)

        assertTrue(g.master.setPaused(false))
        g.time.advanceBy(200)
        assertFalse(g.view("Axel").paused)
        assertTrue(g.master.release())
        assertFalse(g.master.setPaused(true)) // nicht mitten in der Runde
    }

    @Test
    fun `pause message survives the codec`() {
        for (m in listOf(Message.Pause(true), Message.Pause(false))) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }
}
