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
        val problems = mutableListOf<String>()
        val packs = files.map { QuestionPacks.parse(File(dir, it).readText(), problems) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        for (pack in packs) {
            assertEquals(index.getValue("${pack.name}.json"), pack.version, "Version im Index: ${pack.name}")
            assertTrue(pack.questions.all { it.category == pack.title }, "Kategorie = Pakettitel: ${pack.name}")
            assertTrue(pack.questions.all { it.source.startsWith("https://") && it.explanation.isNotEmpty() }, "Quelle und Erklärung: ${pack.name}")
        }
        val ids = packs.flatMap { p -> p.questions.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "IDs eindeutig über alle Pakete")
        assertTrue(ids.size >= 60)
    }
}
