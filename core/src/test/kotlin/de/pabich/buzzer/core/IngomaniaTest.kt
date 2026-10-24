package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Vierte Schwierigkeitsstufe „Ingomania“ (Punkt 82). */
class IngomaniaTest {
    private fun question(level: QuizLevel) = Question(
        "x-1", 1, "Technik", level, "Frage?", listOf("R", "F1", "F2", "F3"), "", "", "2026-10-24", null,
    )

    @Test
    fun `level key and default selection`() {
        assertEquals(QuizLevel.INGOMANIA, QuizLevel.byKey("Ingomania"))
        assertEquals(4, QuizLevel.entries.size)
        // Ingomania ist nie vorgewählt – weder bei neuen Einstellungen noch bei leerer Auswahl.
        assertFalse(QuizLevel.INGOMANIA in Settings().quizLevels)
        assertEquals(QuizLevel.STANDARD, Settings(quizLevels = emptySet()).normalized().quizLevels)
    }

    @Test
    fun `settings with ingomania survive the codec and old lists stay as they are`() {
        val with = Settings(quizLevels = setOf(QuizLevel.HARD, QuizLevel.INGOMANIA))
        assertEquals(with.quizLevels, SettingsCodec.fromMap(SettingsCodec.toMap(with)).settings.quizLevels)
        val old = SettingsCodec.fromMap(mapOf("quizLevels" to "leicht,mittel,schwer")).settings
        assertEquals(QuizLevel.STANDARD, old.quizLevels)
    }

    @Test
    fun `wire format stays readable for older apps and new apps recover ingomania`() {
        val ask = Message.QuizAsk(2, 5, "Technik", QuizLevel.INGOMANIA, "Frage?", listOf("a", "b", "c", "d"), 1, 2, 20_000)
        val bytes = MessageCodec.encode(ask)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("l=HARD" in text, "ältere Apps lesen nur die vorhandenen Stufen: $text")
        assertEquals(ask, MessageCodec.decode(bytes))
        val hard = ask.copy(level = QuizLevel.HARD)
        assertEquals(hard, MessageCodec.decode(MessageCodec.encode(hard)))
        // Im Spielstand genügt „schwer“.
        val s = Message.Succession(
            game = "g", master = "A", to = "", kind = GameKind.QUIZ, round = 1, profile = "", settings = emptyMap(),
            scores = emptyList(),
            quiz = listOf("x-1"), quizNext = 0, test = false,
            questions = listOf(QuizQuestion("x-1", "Technik", QuizLevel.INGOMANIA, "F?", listOf("a", "b", "c", "d"), 0, "", "")),
        )
        val back = MessageCodec.decode(MessageCodec.encode(s)) as Message.Succession
        assertEquals(QuizLevel.HARD, back.questions.single().level)
    }

    @Test
    fun `difficulty measurement never makes foreign questions ingomania`() {
        val hard = question(QuizLevel.HARD)
        val bookHard = StatsBook().recordResult(hard, List(12) { ParticipantResult("S$it", null, 0.3) }, 1)
        assertEquals(QuizLevel.HARD, Difficulty.effective(bookHard.of(hard), hard.level))
        val ingo = question(QuizLevel.INGOMANIA)
        val bookIngo = StatsBook().recordResult(ingo, List(12) { ParticipantResult("S$it", null, 0.3) }, 1)
        assertEquals(QuizLevel.INGOMANIA, Difficulty.effective(bookIngo.of(ingo), ingo.level))
        // Löst fast jeder eine Ingomania-Frage, wird sie leichter eingestuft.
        val easy = StatsBook().recordResult(ingo, List(12) { ParticipantResult("S$it", true, 0.1) }, 1)
        assertEquals(QuizLevel.EASY, Difficulty.effective(easy.of(ingo), ingo.level))
    }

    @Test
    fun `picker only draws ingomania when chosen`() {
        val all = listOf(question(QuizLevel.HARD), question(QuizLevel.INGOMANIA).copy(id = "x-2"))
        fun pick(levels: Set<QuizLevel>) = QuestionPicker.pick(
            all, StatsBook(), QuestionPicker.Request(emptySet(), levels, 5, setOf("Axel"), "2026-10-24"),
        ).questions.map { it.id }
        assertEquals(listOf("x-1"), pick(QuizLevel.STANDARD))
        assertEquals(listOf("x-2"), pick(setOf(QuizLevel.INGOMANIA)))
    }
}
