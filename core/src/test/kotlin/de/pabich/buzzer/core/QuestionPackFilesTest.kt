package de.pabich.buzzer.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prüft die Fragenpakete im Repository (`quiz/`), die in die App eingebaut werden. */
class QuestionPackFilesTest {
    private val dir = listOf(File("../quiz"), File("quiz")).first { it.isDirectory }

    @Test
    fun `all packs are valid`() {
        val index = QuestionPacks.parseIndex(File(dir, "index.json").readText())
        val files = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }!!.map { it.name }.toSet()
        assertEquals(files, index.keys, "index.json listet genau die vorhandenen Pakete")
        // Die Stufen verwaltet die Fragendatenbank selbst.
        val levels = QuestionPacks.parseLevels(File(dir, "index.json").readText())
        assertTrue(levels.size >= 3, "index.json nennt die Stufen")
        assertEquals(levels.size, levels.map { it.key }.toSet().size, "Stufen eindeutig")
        QuizLevels.update(levels)
        val problems = mutableListOf<String>()
        val packs = files.map { QuestionPacks.parse(File(dir, it).readText(), problems) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        for (pack in packs) {
            assertEquals(index.getValue("${pack.name}.json"), pack.version, "Version im Index: ${pack.name}")
            assertTrue(pack.questions.all { it.category == pack.title }, "Kategorie = Pakettitel: ${pack.name}")
            assertTrue(pack.questions.all { it.source.startsWith("https://") && it.explanation.isNotEmpty() }, "Quelle und Erklärung: ${pack.name}")
        }
        // Jede Stufe der Datenbank ist in jeder Kategorie vertreten (gleich viele Fragen ist das Ziel, mindestens aber eine).
        for (pack in packs) {
            for (level in levels) {
                assertTrue(pack.questions.any { it.level == level }, "${pack.title}: keine Frage der Stufe ${level.label}")
            }
        }
        val ids = packs.flatMap { p -> p.questions.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "IDs eindeutig über alle Pakete")
        assertTrue(ids.size >= 60)
    }

    @Test
    fun `levels can be added in the question database without code changes`() {
        val text = """{"stufen": [
            {"schluessel": "leicht", "name": "Leicht", "startwert": 0.8, "gemessenAb": 0.7},
            {"schluessel": "mittel", "name": "Mittel", "startwert": 0.5, "gemessenAb": 0.4},
            {"schluessel": "schwer", "name": "Schwer", "startwert": 0.3, "gemessenAb": 0.1},
            {"schluessel": "Profi", "name": "Profi-Liga", "startwert": 0.1, "gemessenAb": 0.0, "nurAutor": true, "vorgewaehlt": false}
        ], "pakete": []}"""
        try {
            QuizLevels.update(QuestionPacks.parseLevels(text))
            assertEquals(listOf("leicht", "mittel", "schwer", "profi"), QuizLevel.entries.map { it.key })
            assertEquals(setOf("leicht", "mittel", "schwer"), QuizLevel.STANDARD.map { it.key }.toSet())
            val profi = QuizLevel.byKey("PROFI")!!
            assertEquals("Profi-Liga", profi.label)
            // Eine Frage mit der neuen Stufe lässt sich lesen …
            val pack = QuestionPacks.parse(
                """{"paket": "x", "fragen": [{"id": "x-1", "kategorie": "X", "stufe": "profi", "frage": "F?", "antworten": ["a","b","c","d"]}]}""",
            )
            assertEquals(profi, pack.questions.single().level)
            // … ist in den Einstellungen wählbar …
            val settings = Settings(quizLevels = setOf(profi))
            assertEquals(setOf(profi), SettingsCodec.fromMap(SettingsCodec.toMap(settings)).settings.quizLevels)
            // … und kommt beim Spieler an, auch wenn dessen Stufenliste sie (noch) nicht kennt.
            val ask = Message.QuizAsk(1, 1, "X", profi, "F?", listOf("a", "b", "c", "d"), 0, 1, 1000)
            QuizLevels.reset()
            val decoded = MessageCodec.decode(MessageCodec.encode(ask)) as Message.QuizAsk
            assertEquals("Profi-Liga", decoded.level.label)
            assertEquals("profi", decoded.level.key)
        } finally {
            QuizLevels.reset()
        }
    }
}
