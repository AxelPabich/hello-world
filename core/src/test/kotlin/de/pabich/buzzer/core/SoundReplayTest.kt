package de.pabich.buzzer.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundReplayTest {
    private class Inbox {
        val sounds = mutableListOf<SoundEvent>()
        var replay: Message.Replay? = null
    }

    private fun game(settings: Settings = Settings()): Triple<SimGame, Map<String, Inbox>, MutableList<SoundEvent>> {
        val g = SimGame(settings)
        val masterSounds = mutableListOf<SoundEvent>()
        g.master.onSound = { masterSounds += it }
        val inboxes = listOf("Axel", "Peter", "Clara").associateWith { Inbox() }
        for ((id, inbox) in inboxes) {
            val p = g.addPlayer(id, clockOffsetMs = id.length * 1_000L, latencyMs = 60)
            p.engine.onSound = { inbox.sounds += it }
            p.engine.onReplay = { inbox.replay = it }
        }
        g.syncClocks()
        return Triple(g, inboxes, masterSounds)
    }

    private fun SimGame.round(first: String, correct: Boolean) {
        master.release()
        listOf("Axel", "Peter", "Clara").forEachIndexed { i, id -> scheduleTap(id, if (id == first) 200 else 600 + i * 150L) }
        while (master.snapshot().phase != MasterEngine.Phase.ANSWERING) time.advanceBy(10)
        time.advanceBy(200)
        if (correct) master.markCorrect() else repeat(3) { master.markWrong() }
        time.advanceBy(500)
    }

    @Test
    fun `every sound renders audible pcm`() {
        for (event in SoundEvent.entries) {
            val pcm = SoundSynth.render(event)
            assertTrue(pcm.isNotEmpty(), "$event leer")
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue(peak in 1_000..32_767, "$event Spitze $peak")
        }
        assertEquals(SoundSynth.SAMPLE_RATE * 10, SoundSynth.render(SoundEvent.CHASE).size)
        assertEquals(SoundSynth.SAMPLE_RATE * 4, SoundSynth.chase(4_000).size)
    }

    @Test
    fun `right and wrong only reach the player concerned`() {
        val (g, inbox, masterSounds) = game()
        g.master.release()
        listOf("Axel", "Peter", "Clara").forEachIndexed { i, id -> g.scheduleTap(id, if (id == "Peter") 200 else 600 + i * 150L) }
        while (g.master.snapshot().phase != MasterEngine.Phase.ANSWERING) g.time.advanceBy(10)
        g.time.advanceBy(200)
        g.master.markWrong()
        g.time.advanceBy(300)
        assertTrue(SoundEvent.WRONG in inbox.getValue("Peter").sounds)
        assertFalse(SoundEvent.WRONG in inbox.getValue("Axel").sounds)
        repeat(2) { g.master.markWrong() }
        g.time.advanceBy(300)

        g.master.nextRound()
        g.round(first = "Axel", correct = true)
        assertTrue(SoundEvent.RIGHT in inbox.getValue("Axel").sounds)
        assertFalse(SoundEvent.RIGHT in inbox.getValue("Peter").sounds)
        // Rundenende hören die anderen und der Master, nicht der Richtig-Spieler.
        assertTrue(SoundEvent.ROUND_END in inbox.getValue("Peter").sounds)
        assertEquals(1, inbox.getValue("Axel").sounds.count { it == SoundEvent.ROUND_END })
        assertTrue(SoundEvent.ROUND_END in masterSounds)
        assertTrue(SoundEvent.JOIN in masterSounds)
    }

    @Test
    fun `disabled sounds are not sent and players know the profile`() {
        val (g, inbox, _) = game(Settings(sounds = DEFAULT_SOUNDS - SoundEvent.WRONG))
        assertFalse(SoundEvent.WRONG in g.view("Peter").rules.sounds)
        g.round(first = "Peter", correct = false)
        assertFalse(SoundEvent.WRONG in inbox.getValue("Peter").sounds)
    }

    @Test
    fun `replay shows the score history between rounds`() {
        val (g, inbox, _) = game(Settings(boardShowNames = false))
        assertFalse(g.master.canShowReplay())
        g.round(first = "Peter", correct = true)
        g.master.nextRound()
        g.round(first = "Axel", correct = true)
        g.master.nextRound()
        g.round(first = "Peter", correct = true)

        var onMaster: Message.Replay? = null
        g.master.onReplay = { onMaster = it }
        assertTrue(g.master.showReplay())
        g.time.advanceBy(300)

        val forPeter = inbox.getValue("Peter").replay!!
        assertEquals(listOf("???", "Du", "???"), forPeter.labels)
        assertEquals(1, forPeter.selfIndex)
        assertEquals(
            listOf(listOf(0L, 0L, 0L), listOf(0L, 1L, 0L), listOf(1L, 1L, 0L), listOf(1L, 2L, 0L)),
            forPeter.frames,
        )
        assertEquals(6_000L, forPeter.durationMs)
        assertEquals(listOf("Axel", "Peter", "Clara"), onMaster!!.labels)

        g.master.nextRound()
        g.master.release()
        assertFalse(g.master.canShowReplay())
    }

    @Test
    fun `replay length is capped at ten seconds`() {
        val (g, _, _) = game()
        repeat(9) {
            g.round(first = "Clara", correct = true)
            g.master.nextRound()
        }
        var replay: Message.Replay? = null
        g.master.onReplay = { replay = it }
        g.master.showReplay()
        assertEquals(MasterEngine.MAX_REPLAY_MS, replay!!.durationMs)
    }

    @Test
    fun `new messages and sound settings survive the codecs`() {
        val messages = listOf(
            Message.Sound(SoundEvent.CHASE),
            Message.Replay(listOf("Du", "A,b:c"), 0, listOf(listOf(0L, 0L), listOf(1L, 2_500L)), ScoringMode.REMAINING_TIME, 5_000),
            Message.Config(10_000, 0, true, DEFAULT_EMOJIS, "Quiz", setOf(SoundEvent.BUZZ, SoundEvent.CHASE)),
        )
        for (m in messages) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
        val s = Settings(sounds = setOf(SoundEvent.TICK, SoundEvent.EMOTE))
        assertEquals(s.sounds, SettingsCodec.fromMap(SettingsCodec.toMap(s)).settings.sounds)
        assertEquals(DEFAULT_SOUNDS, SettingsCodec.fromMap(SettingsCodec.toMap(s) - "sounds").settings.sounds)
    }
}
