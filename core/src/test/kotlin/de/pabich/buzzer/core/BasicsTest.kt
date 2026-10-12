package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BasicsTest {
    @Test
    fun `codec round-trips every message`() {
        val messages = listOf(
            Message.Ping(1, 123),
            Message.Pong(2, 123, 456),
            Message.Arm(3, 4, true),
            Message.Hold(3, 4, false),
            Message.GoAt(3, 4, StartMode.HOLD_RELEASE, 999_999, 10_000, false),
            Message.Buzz(3, 4, 187, false),
            Message.State(Display.RED, 2, "12,34 s"),
            Message.Freeze(3),
            Message.Reset("a|b=c"),
        )
        for (m in messages) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test
    fun `codec rejects garbage`() {
        assertNull(MessageCodec.decode("NOPE|x=1".toByteArray()))
        assertNull(MessageCodec.decode("BUZZ|r=1".toByteArray()))
    }

    @Test
    fun `clock sync uses the sample with the shortest round trip`() {
        val sync = ClockSync()
        // Echte Abweichung +5000 ms; symmetrische Laufzeit 20 ms.
        sync.addSample(masterSent = 0, playerTime = 5_010, masterReceived = 20)
        // Asymmetrisch verzögert (Rückweg 200 ms) – verfälscht, aber lange Laufzeit.
        sync.addSample(masterSent = 100, playerTime = 5_110, masterReceived = 310)
        assertEquals(5_000, sync.offsetMs)
        assertEquals(210, sync.recentMaxRttMs)
    }

    @Test
    fun `ties are grouped relative to the first of the group`() {
        val groups = groupByReaction(listOf("a" to 300L, "b" to 210L, "c" to 200L, "d" to 225L), tieThresholdMs = 20)
        assertEquals(listOf(listOf("c", "b"), listOf("d"), listOf("a")), groups)
    }

    @Test
    fun `remaining time is formatted in German`() {
        assertEquals("8,77 s", formatScore(8_765, ScoringMode.REMAINING_TIME))
        assertEquals("3", formatScore(3, ScoringMode.CORRECT_ANSWERS))
    }
}
