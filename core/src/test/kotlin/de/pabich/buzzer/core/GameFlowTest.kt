package de.pabich.buzzer.core

import de.pabich.buzzer.core.MasterEngine.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameFlowTest {
    private fun game(settings: Settings = Settings()): SimGame {
        val g = SimGame(settings)
        // Sehr unterschiedliche Uhren und Laufzeiten – die Reihenfolge darf davon nicht abhängen.
        g.addPlayer("A", clockOffsetMs = 7_000, latencyMs = 300)
        g.addPlayer("B", clockOffsetMs = -3_000, latencyMs = 20)
        g.addPlayer("C", clockOffsetMs = 123_456, latencyMs = 100)
        g.syncClocks()
        return g
    }

    private fun SimGame.settle() = time.advanceBy(30_000)

    private fun SimGame.assertView(id: String, display: Display, place: Int = 0) {
        val v = view(id)
        assertEquals(display to place, v.display to v.place, "Anzeige von $id")
    }

    @Test
    fun `order follows reaction time, not arrival time`() {
        val g = game()
        assertTrue(g.master.release())
        g.scheduleTap("A", 250) // langsame Funkstrecke, aber zweitschnellste Reaktion
        g.scheduleTap("B", 300) // kommt zuerst an
        g.scheduleTap("C", 200)
        g.settle()

        assertEquals(Phase.ANSWERING, g.master.snapshot().phase)
        g.assertView("C", Display.GREEN, 1)
        g.assertView("A", Display.RED, 2)
        g.assertView("B", Display.RED, 3)
        assertEquals(250L, g.row("A").reactionMs)
    }

    @Test
    fun `wrong answer passes on to the next player, correct answer scores`() {
        val g = game()
        g.master.release()
        g.scheduleTap("A", 250)
        g.scheduleTap("B", 300)
        g.scheduleTap("C", 200)
        g.settle()

        assertTrue(g.master.markWrong())
        g.settle()
        g.assertView("C", Display.GRAY)
        g.assertView("A", Display.GREEN, 2)

        assertTrue(g.master.markCorrect())
        g.settle()
        assertEquals(Phase.ROUND_OVER, g.master.snapshot().phase)
        assertEquals("1", g.view("A").score)

        g.master.nextRound()
        g.settle()
        for (id in listOf("A", "B", "C")) g.assertView(id, Display.BLACK)
        assertEquals("1", g.view("A").score)
    }

    @Test
    fun `round ends when every player answered wrong`() {
        val g = game()
        g.master.release()
        g.scheduleTap("A", 250)
        g.scheduleTap("B", 300)
        g.scheduleTap("C", 200)
        g.settle()
        repeat(3) { assertTrue(g.master.markWrong()) }
        g.settle()
        assertEquals(Phase.ROUND_OVER, g.master.snapshot().phase)
        for (id in listOf("A", "B", "C")) g.assertView(id, Display.GRAY)
    }

    @Test
    fun `fastest player sees green before the others have pressed`() {
        val g = game()
        g.master.release()
        g.scheduleTap("C", 200)
        g.scheduleTap("A", 4_000)
        g.time.advanceTo(g.localGoAt("C") - 123_456 + 200 + 1_500)
        g.assertView("C", Display.GREEN, 1)
        g.assertView("B", Display.YELLOW)
        assertEquals(Phase.RUNNING, g.master.snapshot().phase)
    }

    @Test
    fun `tapping before the release is a false start`() {
        val g = game()
        g.master.release()
        g.time.advanceBy(400) // GoAt ist überall angekommen, Freigabe aber noch nicht erreicht
        g.touchDownNow("A")
        g.scheduleTap("B", 300)
        g.scheduleTap("C", 350)
        g.settle()
        g.assertView("A", Display.GRAY)
        g.assertView("B", Display.GREEN, 1)
        g.assertView("C", Display.RED, 2)
    }

    @Test
    fun `players who do not press within the time limit turn gray`() {
        val g = game(Settings(timeLimitMs = 5_000))
        g.master.release()
        g.scheduleTap("A", 250)
        g.settle()
        g.assertView("A", Display.GREEN, 1)
        g.assertView("B", Display.GRAY)
        g.assertView("C", Display.GRAY)
        assertEquals(Phase.ANSWERING, g.master.snapshot().phase)
    }

    @Test
    fun `tie for first place goes to sudden death`() {
        val g = game(Settings(tieThresholdMs = 20))
        g.master.release()
        g.scheduleTap("A", 200)
        g.scheduleTap("B", 210)
        g.scheduleTap("C", 400)
        // Bis kurz nach dem Einfrieren: beide pink, C rot auf Platz 3.
        g.time.advanceTo(g.localGoAt("C") - 123_456 + 400 + 1_100)
        assertEquals(Phase.SUDDEN_DEATH_PREP, g.master.snapshot().phase)
        g.assertView("A", Display.PINK, 1)
        g.assertView("B", Display.PINK, 1)
        g.assertView("C", Display.RED, 3)

        g.time.advanceBy(MasterEngine.SUDDEN_DEATH_PAUSE_MS) // Sudden Death startet
        g.scheduleTap("A", 300)
        g.scheduleTap("B", 150)
        g.settle()
        g.assertView("B", Display.GREEN, 1)
        g.assertView("A", Display.RED, 2)
        g.assertView("C", Display.RED, 3)
        // Für die Wertung zählt die Reaktion aus der Hauptrunde.
        assertEquals(210L, g.row("B").reactionMs)
    }

    @Test
    fun `tie on a later place is resolved only when it is reached`() {
        val g = game(Settings(tieThresholdMs = 20))
        g.master.release()
        g.scheduleTap("A", 100)
        g.scheduleTap("B", 300)
        g.scheduleTap("C", 310)
        g.settle()
        g.assertView("A", Display.GREEN, 1)
        g.assertView("B", Display.RED, 2)
        g.assertView("C", Display.RED, 2)

        g.master.markWrong()
        g.time.advanceBy(1_000)
        g.assertView("B", Display.PINK, 2)
        g.assertView("C", Display.PINK, 2)
        g.time.advanceBy(MasterEngine.SUDDEN_DEATH_PAUSE_MS)
        g.scheduleTap("C", 180)
        g.scheduleTap("B", 260)
        g.settle()
        g.assertView("C", Display.GREEN, 2)
        g.assertView("B", Display.RED, 3)
    }

    @Test
    fun `hold-and-release mode starts when everybody holds and counts the release`() {
        val g = game(Settings(startMode = StartMode.HOLD_RELEASE))
        g.master.release()
        g.time.advanceBy(500)
        assertEquals(Phase.WAIT_HOLD, g.master.snapshot().phase)
        g.touchDownNow("A")
        g.touchDownNow("B")
        g.time.advanceBy(500)
        g.assertView("A", Display.BLUE)
        assertEquals(Display.BLUE, g.row("A").display)
        assertEquals(Phase.WAIT_HOLD, g.master.snapshot().phase) // C hält noch nicht

        g.touchDownNow("C")
        g.time.advanceBy(500)
        assertEquals(Phase.COUNTDOWN, g.master.snapshot().phase)
        g.scheduleRelease("A", 220)
        g.scheduleRelease("B", 180)
        g.scheduleRelease("C", 500)
        g.settle()
        g.assertView("B", Display.GREEN, 1)
        g.assertView("A", Display.RED, 2)
        g.assertView("C", Display.RED, 3)
    }

    @Test
    fun `releasing during the random delay is a false start in hold mode`() {
        val g = game(Settings(startMode = StartMode.HOLD_RELEASE, delayMinMs = 3_000, delayMaxMs = 3_000))
        g.master.release()
        g.time.advanceBy(500)
        listOf("A", "B", "C").forEach { g.touchDownNow(it) }
        g.time.advanceBy(1_500) // alle halten, Verzögerung läuft
        g.touchUpNow("A")
        g.scheduleRelease("B", 200)
        g.scheduleRelease("C", 300)
        g.settle()
        g.assertView("A", Display.GRAY)
        g.assertView("B", Display.GREEN, 1)
    }

    @Test
    fun `lifting the finger before everybody holds is allowed`() {
        val g = game(Settings(startMode = StartMode.HOLD_RELEASE))
        g.master.release()
        g.time.advanceBy(500)
        g.touchDownNow("A")
        g.time.advanceBy(100)
        g.touchUpNow("A")
        g.time.advanceBy(500)
        g.assertView("A", Display.BLACK)
        listOf("A", "B", "C").forEach { g.touchDownNow(it) }
        g.time.advanceBy(500)
        assertEquals(Phase.COUNTDOWN, g.master.snapshot().phase)
    }

    @Test
    fun `remaining-time scoring adds the time left and locks the time limit`() {
        val g = game(Settings(scoring = ScoringMode.REMAINING_TIME, timeLimitMs = 10_000))
        g.master.release()
        g.scheduleTap("A", 1_234)
        g.scheduleTap("B", 2_000)
        g.scheduleTap("C", 3_000)
        g.settle()
        g.master.markCorrect()
        g.settle()
        assertEquals("8,77 s", g.view("A").score)

        g.master.nextRound()
        g.master.updateSettings(g.master.settings.copy(timeLimitMs = 20_000, scoring = ScoringMode.CORRECT_ANSWERS))
        assertEquals(10_000, g.master.settings.timeLimitMs)
        assertEquals(ScoringMode.REMAINING_TIME, g.master.settings.scoring)
        assertTrue(g.master.snapshot().timeLimitLocked)

        g.master.newGame()
        assertFalse(g.master.snapshot().timeLimitLocked)
    }

    @Test
    fun `settings cannot change during a round`() {
        val g = game()
        g.master.release()
        assertFalse(g.master.updateSettings(Settings(startMode = StartMode.HOLD_RELEASE)))
    }

    @Test
    fun `disconnect of the answering player passes on`() {
        val g = game()
        g.master.release()
        g.scheduleTap("A", 250)
        g.scheduleTap("B", 300)
        g.scheduleTap("C", 200)
        g.settle()
        g.master.removePlayer("C")
        g.settle()
        g.assertView("A", Display.GREEN, 2)
        g.master.nextRound()
        assertEquals(listOf("A", "B"), g.master.snapshot().players.map { it.id })
    }

    @Test
    fun `local master player has no latency advantage`() {
        val g = SimGame()
        g.addPlayer("R", clockOffsetMs = 50_000, latencyMs = 250)
        // Lokaler Spieler auf dem Master-Gerät: gleiche Uhr, Nachrichten ohne Laufzeit.
        g.addPlayer("M", clockOffsetMs = 0, latencyMs = 0)
        g.syncClocks()
        g.master.release()
        g.scheduleTap("R", 180)
        g.scheduleTap("M", 230)
        g.settle()
        g.assertView("R", Display.GREEN, 1)
        g.assertView("M", Display.RED, 2)
    }
}
