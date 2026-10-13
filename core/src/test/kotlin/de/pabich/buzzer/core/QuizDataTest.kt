package de.pabich.buzzer.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizDataTest {
    private fun q(id: String, category: String = "Technik", level: QuizLevel = QuizLevel.MEDIUM, validUntil: String? = null, version: Int = 1) =
        Question(id, version, category, level, "Frage $id?", listOf("R", "F1", "F2", "F3"), "", "", "2026-10-13", validUntil)

    private fun request(count: Int = 3, players: Set<String> = setOf("Axel", "Peter"), levels: Set<QuizLevel> = emptySet(), categories: Set<String> = emptySet()) =
        QuestionPicker.Request(categories, levels, count, players, "2026-10-13")

    @Test
    fun `json round trip`() {
        val value = linkedMapOf(
            "text" to "Küche & \"Genuss\"\nZeile 2 \\ Ende",
            "zahl" to 42L,
            "komma" to 0.25,
            "ja" to true,
            "nichts" to null,
            "liste" to listOf("a", 1L, false),
            "verschachtelt" to listOf(mapOf("x" to listOf<Any>()), mapOf<String, Any>()),
        )
        assertEquals(value, MiniJson.parse(MiniJson.write(value)))
        assertEquals(mapOf("u" to "é€"), MiniJson.parse("""{"u": "é€"}"""))
        assertFailsWith<MiniJson.ParseException> { MiniJson.parse("""{"a": 1,}""") }
        assertFailsWith<MiniJson.ParseException> { MiniJson.parse("""[1, 2""") }
    }

    @Test
    fun `pack parser checks every question`() {
        val text = """
            {"paket": "test", "titel": "Test", "version": 3, "fragen": [
              {"id": "t-1", "kategorie": "Technik", "stufe": "leicht", "frage": "A?", "antworten": ["1", "2", "3", "4"]},
              {"id": "t-2", "kategorie": "Technik", "stufe": "mittel", "frage": "B?", "antworten": ["1", "2", "3"]},
              {"id": "t-3", "kategorie": "Technik", "stufe": "extrem", "frage": "C?", "antworten": ["1", "2", "3", "4"]},
              {"id": "t-1", "kategorie": "Technik", "stufe": "schwer", "frage": "D?", "antworten": ["1", "2", "3", "4"]},
              {"id": "t-5", "kategorie": "Technik", "stufe": "schwer", "frage": "E?", "antworten": ["1", "2", "2", "4"]}
            ]}
        """
        val problems = mutableListOf<String>()
        val pack = QuestionPacks.parse(text, problems)
        assertEquals(3, pack.version)
        assertEquals(listOf("t-1"), pack.questions.map { it.id })
        assertEquals(4, problems.size, problems.joinToString("\n"))
        assertEquals(QuizLevel.EASY, pack.questions.single().level)
    }

    @Test
    fun `newer pack version wins`() {
        val a = QuestionPack("kueche", "Küche", 1, listOf(q("a")))
        val b = QuestionPack("kueche", "Küche", 2, listOf(q("b")))
        assertEquals(listOf("b"), QuestionPacks.merge(listOf(a, b)).single().questions.map { it.id })
        assertEquals(mapOf("kueche.json" to 2), QuestionPacks.parseIndex("""{"pakete": [{"datei": "kueche.json", "version": 2}]}"""))
    }

    @Test
    fun `unseen questions come first, then the oldest`() {
        val all = (1..6).map { q("q$it", category = if (it % 2 == 0) "Technik" else "Erdkunde") }
        var book = StatsBook()
        book = book.recordResult(all[0], listOf(ParticipantResult("Axel", true, 0.2)), now = 100)
        book = book.recordResult(all[1], listOf(ParticipantResult("Peter", false, 0.5)), now = 50)
        book = book.recordResult(all[2], listOf(ParticipantResult("Dora", true, 0.5)), now = 10) // Dora spielt nicht mit
        val r = QuestionPicker.pick(all, book, request(count = 6), Random(1))
        assertEquals(4, r.fresh)
        assertEquals(6, r.matching)
        assertEquals(setOf("q3", "q4", "q5", "q6"), r.questions.take(4).map { it.id }.toSet())
        assertEquals(listOf("q2", "q1"), r.questions.drop(4).map { it.id }, "danach das am längsten nicht gespielte")
    }

    @Test
    fun `categories alternate among equally fresh questions`() {
        val all = (1..4).map { q("t$it", "Technik") } + (1..4).map { q("e$it", "Erdkunde") } + (1..4).map { q("k$it", "Küche") }
        val r = QuestionPicker.pick(all, StatsBook(), request(count = 9), Random(7))
        val cats = r.questions.map { it.category }
        assertTrue(cats.zipWithNext().none { it.first == it.second }, cats.toString())
        assertEquals(listOf(3, 3, 3), cats.groupingBy { it }.eachCount().values.toList())
    }

    @Test
    fun `blocked, expired and filtered questions are left out`() {
        val all = listOf(
            q("ok"),
            q("alt", validUntil = "2026-10-12"),
            q("heute", validUntil = "2026-10-13"),
            q("gesperrt"),
            q("schwer", level = QuizLevel.HARD),
            q("andere", category = "Geschichte"),
        )
        val book = StatsBook().recordDecision(all[3], ChallengeDecision.BLOCK, null)
        val r = QuestionPicker.pick(all, book, request(count = 10, levels = setOf(QuizLevel.MEDIUM), categories = setOf("Technik")))
        assertEquals(setOf("ok", "heute"), r.questions.map { it.id }.toSet())
    }

    @Test
    fun `difficulty is learned from the answers`() {
        val question = q("x", level = QuizLevel.MEDIUM)
        var book = StatsBook()
        // Wenige Antworten: Autoren-Stufe bleibt.
        book = book.recordResult(question, List(4) { ParticipantResult("S$it", true, 0.1) }, 1)
        assertNull(Difficulty.measured(book.of(question), question.level))
        assertEquals(QuizLevel.MEDIUM, Difficulty.effective(book.of(question), question.level))
        // Viele richtig und schnell → leicht.
        book = book.recordResult(question, List(6) { ParticipantResult("T$it", true, 0.1) }, 2)
        assertEquals(QuizLevel.EASY, Difficulty.effective(book.of(question), question.level))
        // Richtig, aber langsam → eine Stufe schwerer.
        val slow = q("y", level = QuizLevel.MEDIUM)
        val slowBook = StatsBook().recordResult(slow, List(8) { ParticipantResult("S$it", true, 0.8) }, 1)
        assertEquals(QuizLevel.MEDIUM, Difficulty.effective(slowBook.of(slow), slow.level))
        // Kaum jemand richtig → schwer.
        val hard = q("z", level = QuizLevel.EASY)
        val hardBook = StatsBook().recordResult(hard, List(10) { ParticipantResult("S$it", if (it == 0) true else null, 0.3) }, 1)
        assertEquals(QuizLevel.HARD, Difficulty.effective(hardBook.of(hard), hard.level))
        // Gefiltert wird nach der gemessenen Stufe.
        val r = QuestionPicker.pick(listOf(question), book, request(levels = setOf(QuizLevel.EASY)))
        assertEquals(1, r.questions.size)
    }

    @Test
    fun `a new question version starts the counts again but keeps who has seen it`() {
        val v1 = q("x", version = 1)
        val book = StatsBook().recordResult(v1, listOf(ParticipantResult("Axel", false, 0.5)), 5)
            .recordDecision(v1, ChallengeDecision.CORRECT, 2)
        assertEquals(2, book.of(v1).correctOverride)
        assertEquals(2, v1.toQuiz(book.of(v1).correctOverride).correctIndex)
        val v2 = q("x", version = 2)
        val s = book.of(v2)
        assertEquals(0, s.answers)
        assertNull(s.correctOverride)
        assertEquals(setOf("Axel"), s.seenBy)
    }

    @Test
    fun `stats file round trip`() {
        val question = q("x")
        val book = StatsBook()
            .recordResult(question, listOf(ParticipantResult("Axel", true, 0.25), ParticipantResult("Peter", null, 1.0)), 1234)
            .recordChallenge(question, "Peter", "Stimmt nicht")
            .recordDecision(question, ChallengeDecision.KEEP, null)
            .recordSeen(q("y"), listOf("Clara"), 99)
        val json = book.toJson(mapOf("x" to question))
        assertTrue("\"frage\": \"Frage x?\"" in json)
        assertEquals(book, StatsBook.fromJson(json))
    }

    @Test
    fun `quiz settings survive the codec and old profiles get the new sound`() {
        val s = Settings(
            quizCategories = setOf("Küche & Genuss", "Film & Fernsehen"),
            quizLevels = setOf(QuizLevel.EASY, QuizLevel.HARD),
            quizCount = 15,
            quizAnswerMs = 25_000,
            quizScoring = QuizScoring.BATTLE,
            quizCountdownMs = 7_000,
        )
        val decoded = SettingsCodec.fromMap(SettingsCodec.toMap(s))
        assertEquals(s, decoded.settings)
        assertTrue(decoded.missingKeys.isEmpty())
        // Profil aus Build 9: ohne Quiz-Felder, ohne Kenntnis des Tuschs.
        val old = SettingsCodec.toMap(Settings()).filterKeys { !it.startsWith("quiz") && it != "soundsKnown" } +
            ("sounds" to "JOIN,BUZZ")
        val upgraded = SettingsCodec.fromMap(old)
        assertEquals(setOf(SoundEvent.JOIN, SoundEvent.BUZZ, SoundEvent.TUSCH), upgraded.settings.sounds)
        assertTrue("quizScoring" in upgraded.missingKeys)
        assertFalse("soundsKnown" in upgraded.missingKeys)
        // Wer den Tusch ausgeschaltet hat, behält das.
        val off = SettingsCodec.fromMap(SettingsCodec.toMap(Settings(sounds = setOf(SoundEvent.JOIN))))
        assertEquals(setOf(SoundEvent.JOIN), off.settings.sounds)
    }

    @Test
    fun `tusch sound renders`() {
        val pcm = SoundSynth.render(SoundEvent.TUSCH)
        assertTrue(pcm.size > SoundSynth.SAMPLE_RATE)
        assertTrue(pcm.any { it > 1000 })
    }
}
