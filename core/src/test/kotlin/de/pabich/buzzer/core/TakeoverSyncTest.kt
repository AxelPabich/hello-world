package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sync-Verfahren der Übernahme (Punkt 118): Kennung in der Ankündigung, Epoche, gestaffelte Reihenfolge. */
class TakeoverSyncTest {
    private fun succession(epoch: Int = 0, order: List<String> = listOf("A53", "Natsch", "S6")) = Message.Succession(
        game = "ab23cd", master = "Axel", to = "", kind = GameKind.QUIZ, round = 4, profile = "Familienabend",
        settings = emptyMap(), scores = listOf("Axel" to 1L, "A53" to 2L), quiz = emptyList(), quizNext = -1, test = false,
        epoch = epoch, order = order, sync = true,
    )

    private fun peer(name: String, game: String? = null, epoch: Int = 0) =
        PeerInfo.parse(PeerInfo.format(name, 46, game = game, epoch = epoch))

    @Test
    fun `announcement carries game and epoch and old format still parses`() {
        val p = PeerInfo.parse(PeerInfo.format("Samsung A53", 46, game = "ab23cd", epoch = 3))
        assertEquals("Samsung A53", p.name)
        assertEquals(46, p.build)
        assertTrue(p.compatible)
        assertEquals("ab23cd", p.game)
        assertEquals(3, p.epoch)
        // Ohne Kennung genau wie bisher (ältere Builds verstehen das weiterhin).
        assertEquals("Axel [45/2]", PeerInfo.format("Axel", 45))
        val old = PeerInfo.parse("Axel [45/2]")
        assertEquals("Axel", old.name)
        assertNull(old.game)
        assertTrue(PeerInfo.parse("Axel [45/2/u]").updateOnly)
    }

    @Test
    fun `succession with epoch order and sync survives the codec`() {
        val s = succession(epoch = 2, order = listOf("Samsung A53", "Natsch, der Große", "S6|x"))
        assertEquals(s, MessageCodec.decode(MessageCodec.encode(s)))
        // Ein Stand ohne die neuen Felder (älterer Spielleiter) bedeutet: altes Verfahren.
        val legacy = MessageCodec.decode(MessageCodec.encode(s.copy(epoch = 0, order = emptyList(), sync = false))) as Message.Succession
        assertFalse(legacy.sync)
    }

    @Test
    fun `successors wait by rank`() {
        val s = succession()
        assertEquals(0, TakeoverRules.rank(s, "A53"))
        assertEquals(2, TakeoverRules.rank(s, "S6"))
        assertEquals(3, TakeoverRules.rank(s, "Neu"))
        assertEquals(5_000, TakeoverRules.waitMs(0))
        assertEquals(15_000, TakeoverRules.waitMs(2))
    }

    @Test
    fun `waiting players join the successor or the returning master only`() {
        val s = succession(epoch = 0)
        assertTrue(TakeoverRules.joinWhileWaiting(s, peer("A53", "ab23cd", 1)))
        assertTrue(TakeoverRules.joinWhileWaiting(s, peer("Axel")))
        assertTrue(TakeoverRules.joinWhileWaiting(s, peer("Axel", "ab23cd", 0)))
        assertFalse(TakeoverRules.joinWhileWaiting(s, peer("A53")))
        assertFalse(TakeoverRules.joinWhileWaiting(s, peer("Fremd", "zz99zz", 1)))
    }

    @Test
    fun `new master never yields to the predecessor but to a higher epoch or a better rank`() {
        val s = succession(epoch = 0)
        // Der Fall vom 01.11.2026: Axel ist noch zu sehen – A53 bleibt Spielleiter.
        assertFalse(TakeoverRules.yieldTo(s, 1, "A53", peer("Axel")))
        assertFalse(TakeoverRules.yieldTo(s, 1, "A53", peer("Axel", "ab23cd", 0)))
        // Zwei haben zugleich übernommen: der vordere Rang gewinnt.
        assertTrue(TakeoverRules.yieldTo(s, 1, "Natsch", peer("A53", "ab23cd", 1)))
        assertFalse(TakeoverRules.yieldTo(s, 1, "A53", peer("Natsch", "ab23cd", 1)))
        // Eine neuere Leitung hat immer Vorrang.
        assertTrue(TakeoverRules.yieldTo(s, 1, "A53", peer("S6", "ab23cd", 2)))
        assertFalse(TakeoverRules.yieldTo(s, 1, "A53", peer("S6", "zz99zz", 5)))
    }

    @Test
    fun `former master joins its successor`() {
        assertTrue(TakeoverRules.joinAsFormerMaster("ab23cd", 0, peer("A53", "ab23cd", 1)))
        assertFalse(TakeoverRules.joinAsFormerMaster("ab23cd", 1, peer("A53", "ab23cd", 1)))
        assertFalse(TakeoverRules.joinAsFormerMaster("ab23cd", 0, peer("A53")))
    }

    @Test
    fun `master shares epoch order and sync and the successor counts up`() {
        val g = SimGame(Settings())
        g.addPlayer("Peter", clockOffsetMs = 0, latencyMs = 200)
        g.addPlayer("Clara", clockOffsetMs = 0, latencyMs = 20)
        g.syncClocks()
        g.master.syncCapable = { true }
        val s = g.master.succession()
        assertEquals(0, s.epoch)
        assertTrue(s.sync)
        assertEquals(listOf("Clara", "Peter"), s.order)
        val next = MasterEngine(Clock { g.time.now }, g.time) { _, _ -> }
        next.takeOver(s, emptyList(), -1)
        assertEquals(1, next.epoch)
        assertTrue(next.syncGame)
    }

    @Test
    fun `former master sends its players to the successor`() {
        val g = SimGame(Settings())
        g.addPlayer("Peter", clockOffsetMs = 0, latencyMs = 30)
        g.addPlayer("Clara", clockOffsetMs = 0, latencyMs = 30)
        g.syncClocks()
        val s = g.master.redirectTo("Samsung A53")
        g.time.advanceBy(200)
        assertEquals("Samsung A53", s.to)
        assertTrue(g.master.handedOver)
        for (id in listOf("Peter", "Clara")) {
            assertEquals("Samsung A53", g.received.getValue(id).filterIsInstance<Message.Succession>().last().to)
        }
    }
}
