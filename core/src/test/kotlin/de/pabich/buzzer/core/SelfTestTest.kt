package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertTrue

class SelfTestTest {
    @Test
    fun `self test passes on a healthy build`() {
        val results = SelfTest.runOffline()
        assertTrue(results.all { it.ok }, SelfTest.format(results))
    }
}
