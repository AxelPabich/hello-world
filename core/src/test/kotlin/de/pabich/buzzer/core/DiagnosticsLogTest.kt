package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsLogTest {
    private fun game(settings: Settings = Settings()): SimGame {
        val g = SimGame(settings)
        g.addPlayer("Axel", clockOffsetMs = 3_000, latencyMs = 50)
        g.addPlayer("Peter", clockOffsetMs = -7_000, latencyMs = 100)
        g.syncClocks()
        return g
    }

    @Test
    fun `repeated displays are logged once`() {
        val g = game()
        g.master.release()
        g.scheduleTap("Axel", 200)
        g.scheduleTap("Peter", 400)
        g.time.advanceBy(10_000)
        g.master.markWrong() // schickt Peter seine Anzeige erneut, dann grün
        g.time.advanceBy(1_000)
        val lines = g.playerLogs.getValue("Peter").filter { it.startsWith("Anzeige") }
        assertEquals(lines.distinct().size, lines.size, lines.toString())
        assertEquals(listOf("Anzeige: RED Platz 2", "Anzeige: GREEN Platz 2"), lines)
    }

    @Test
    fun `missing reaction is logged on the master`() {
        val g = game(Settings(timeLimitMs = 3_000))
        g.master.release()
        g.scheduleTap("Axel", 200)
        g.time.advanceBy(15_000)
        assertTrue("Keine Reaktion: Peter" in g.masterLog, g.masterLog.toString())
    }

    @Test
    fun `ties and sudden death are logged`() {
        val g = game(Settings(tieThresholdMs = 20))
        g.master.release()
        g.scheduleTap("Axel", 341)
        g.scheduleTap("Peter", 340)
        while (g.master.snapshot().phase != MasterEngine.Phase.SUDDEN_DEATH_PREP) g.time.advanceBy(10)
        g.time.advanceBy(MasterEngine.SUDDEN_DEATH_PAUSE_MS + 100)
        g.scheduleTap("Axel", 300)
        g.scheduleTap("Peter", 500)
        g.time.advanceBy(15_000)
        assertTrue(g.masterLog.any { it.startsWith("Gleichstand") && "(1 ms)" in it }, g.masterLog.toString())
        assertTrue(g.masterLog.any { it.startsWith("Sudden Death um Platz 1:") }, g.masterLog.toString())
        assertTrue("Sudden Death: Axel 300 ms, Peter 500 ms" in g.masterLog, g.masterLog.toString())
    }

    @Test
    fun `unusual round trip times are flagged`() {
        val sync = ClockSync()
        repeat(10) { sync.addSample(masterSent = it * 1000L, playerTime = 0, masterReceived = it * 1000L + 210) }
        assertFalse(sync.rttOutlier)
        sync.addSample(masterSent = 20_000, playerTime = 0, masterReceived = 20_939)
        assertTrue(sync.rttOutlier)
        assertEquals(210L, sync.typicalRttMs)

        val g = game()
        // Kurze Ausreißer ohne Vorgeschichte gelten nicht als auffällig.
        g.master.release()
        assertFalse(g.masterLog.any { it.startsWith("Hinweis: Funk-Laufzeit") })
    }
}
