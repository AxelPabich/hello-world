package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreboardTest {
    private val players = listOf(
        ScoreInput("a", "Anna", 3),
        ScoreInput("b", "Ben", 5),
        ScoreInput("c", "Carl", 3),
        ScoreInput("d", "Dora", 1),
    )

    private fun List<ScoreEntry>.short() = map { "${it.rank}:${it.label}" }

    @Test
    fun `equal scores share a rank`() {
        val board = buildScoreboard(players, viewer = null, Settings())
        assertEquals(listOf("1:Ben", "2:Anna", "2:Carl", "4:Dora"), board.short())
    }

    @Test
    fun `limited places still show the viewer`() {
        val board = buildScoreboard(players, viewer = "d", Settings(boardPlaces = 1))
        assertEquals(listOf("1:Ben", "4:Du"), board.short())
        assertTrue(board.last().isSelf)
    }

    @Test
    fun `places limit includes everybody sharing the last visible rank`() {
        val board = buildScoreboard(players, viewer = "b", Settings(boardPlaces = 2))
        assertEquals(listOf("1:Du", "2:Anna", "2:Carl"), board.short())
    }

    @Test
    fun `names can be hidden except the own one`() {
        val board = buildScoreboard(players, viewer = "a", Settings(boardShowNames = false))
        assertEquals(listOf("1:???", "2:Du", "2:???", "4:???"), board.short())
    }

    @Test
    fun `scoreboard survives the codec`() {
        val message = Message.Scoreboard(
            listOf(
                ScoreEntry(1, "Zoë: a,b|c", 8_765, "8,77 s", false, "3,45 s"),
                ScoreEntry(2, "Du", 0, "0,00 s", true),
            ),
            maxScore = 8_765,
            show = true,
        )
        assertEquals(message, MessageCodec.decode(MessageCodec.encode(message)))
        val empty = Message.Scoreboard(emptyList(), 0, false)
        assertEquals(empty, MessageCodec.decode(MessageCodec.encode(empty)))
    }

    @Test
    fun `tied scores without time scoring are broken by the smaller reaction time sum`() {
        val tied = listOf(
            ScoreInput("a", "Anna", 3, totalReactionMs = 5_000),
            ScoreInput("b", "Ben", 3, totalReactionMs = 2_000),
            ScoreInput("c", "Carl", 1),
        )
        val board = buildScoreboard(tied, viewer = null, Settings())
        assertEquals(listOf("1:Ben", "2:Anna", "3:Carl"), board.short())
        assertEquals("2,00 s", board[0].tiebreakText)
        assertEquals("5,00 s", board[1].tiebreakText)
        // Kein Gleichstand: kein Tiebreaker-Text.
        assertEquals(null, board[2].tiebreakText)
    }

    @Test
    fun `tied scores with time scoring are broken by the higher correct count`() {
        val tied = listOf(
            ScoreInput("a", "Anna", 3_000, correctCount = 2),
            ScoreInput("b", "Ben", 3_000, correctCount = 4),
        )
        val board = buildScoreboard(tied, viewer = null, Settings(scoring = ScoringMode.REMAINING_TIME))
        assertEquals(listOf("1:Ben", "2:Anna"), board.short())
        assertEquals("4× richtig", board[0].tiebreakText)
        assertEquals("2× richtig", board[1].tiebreakText)
    }

    @Test
    fun `a tie after several buzzer rounds is broken by the accumulated reaction time`() {
        val g = SimGame()
        g.addPlayer("A", clockOffsetMs = 0, latencyMs = 20)
        g.addPlayer("B", clockOffsetMs = 0, latencyMs = 20)
        g.syncClocks()

        // Runde 1: A buzzert zuerst (200 ms), wird aber falsch gewertet; B (300 ms) bekommt den Punkt.
        g.master.release()
        g.scheduleTap("A", 200)
        g.scheduleTap("B", 300)
        g.time.advanceBy(30_000)
        g.master.markWrong()
        g.time.advanceBy(1_000)
        g.master.markCorrect()
        g.time.advanceBy(1_000)
        g.master.nextRound()

        // Runde 2: A buzzert sehr schnell (50 ms) und bekommt den Punkt; B buzzert gar nicht (zählt die
        // volle Wartezeit).
        g.master.release()
        g.scheduleTap("A", 50)
        g.time.advanceBy(30_000)
        g.master.markCorrect()
        g.time.advanceBy(1_000)

        // Beide stehen bei 1 Punkt: A hat die kleinere Zeitsumme (200+50 ms gegen 300+10.000 ms) und führt.
        assertEquals("1", g.view("A").score)
        assertEquals("1", g.view("B").score)
        val board = g.view("B").board
        assertEquals(listOf("1:A", "2:Du"), board.map { "${it.rank}:${it.label}" })
        assertEquals("0,25 s", board[0].tiebreakText)
        assertEquals("10,30 s", board[1].tiebreakText)
    }

    @Test
    fun `players see the board between rounds but not while buzzing`() {
        val g = SimGame(Settings(boardPlaces = 1, boardShowNames = false))
        g.addPlayer("A", clockOffsetMs = 1_000, latencyMs = 50)
        g.addPlayer("B", clockOffsetMs = -2_000, latencyMs = 80)
        g.syncClocks()
        assertTrue(g.view("B").showBoard)
        assertEquals(2, g.view("B").board.size)

        g.master.release()
        g.time.advanceBy(500)
        assertFalse(g.view("B").showBoard)

        g.scheduleTap("A", 200)
        g.scheduleTap("B", 400)
        g.time.advanceBy(30_000)
        assertFalse(g.view("B").showBoard) // Antwortphase
        g.master.markCorrect()
        g.time.advanceBy(1_000)

        // Rundenende: B sieht Platz 1 ohne Namen und sich selbst.
        val viewB = g.view("B")
        assertTrue(viewB.showBoard)
        assertEquals(listOf("1:???", "2:Du"), viewB.board.short())
        assertEquals(1L, viewB.boardMaxScore)
        assertEquals(listOf("1:Du"), g.view("A").board.short())

        g.master.nextRound()
        g.time.advanceBy(1_000)
        assertTrue(g.view("B").showBoard)
        assertEquals(1L, g.row("A").scoreValue)
    }
}
