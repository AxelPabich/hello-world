package de.pabich.buzzer.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** `aenderungen/aenderungen.json` („Was ist neu?“) bleibt lesbar und richtig sortiert. */
class ChangelogFileTest {
    @Test
    fun `changelog is valid, newest first, at most the top entry without build number`() {
        val file = listOf(File("../aenderungen/aenderungen.json"), File("aenderungen/aenderungen.json")).first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        val entries = ((MiniJson.parse(file.readText()) as Map<String, Any?>)["eintraege"] as List<Map<String, Any?>>)
        assertTrue(entries.isNotEmpty())
        val builds = entries.map { (it["build"] as Number).toInt() }
        assertTrue(builds.drop(1).none { it == 0 }, "nur der oberste Eintrag darf noch ohne Nummer sein: $builds")
        val numbered = builds.filter { it > 0 }
        assertTrue(numbered == numbered.sortedDescending() && numbered.toSet().size == numbered.size, "absteigend, ohne Doppelte: $builds")
        for (e in entries) {
            val points = e["punkte"] as List<*>
            assertTrue(points.isNotEmpty() && points.all { it is String && it.isNotBlank() }, "Punkte fehlen bei $e")
        }
    }
}
