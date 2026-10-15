package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadyReviewTest {
    private fun game(): SimGame {
        val g = SimGame(Settings(quizAnswerMs = 10_000))
        listOf("Axel", "Peter", "Clara").forEachIndexed { i, id -> g.addPlayer(id, 100L * i, 50) }
        g.syncClocks()
        return g
    }

    @Test
    fun `get ready asks every player and signals go when all confirmed`() {
        val g = game()
        val sounds = mutableListOf<SoundEvent>()
        g.master.onSound = { sounds += it }
        assertTrue(g.master.askReady())
        g.time.advanceBy(200)
        assertTrue(listOf("Axel", "Peter", "Clara").all { g.view(it).readyAsk != null })
        val master = g.master.masterReadyLabel
        assertEquals(listOf(master, "Axel", "Peter", "Clara"), g.master.snapshot().ready?.waitingNames)
        assertTrue(g.player("Axel").engine.confirmReady())
        assertFalse(g.player("Axel").engine.confirmReady(), "nur einmal")
        g.player("Peter").engine.confirmReady()
        g.time.advanceBy(200)
        assertEquals(listOf(master, "Clara"), g.master.snapshot().ready?.waitingNames)
        assertFalse(SoundEvent.GO in sounds)
        g.player("Clara").engine.confirmReady()
        g.time.advanceBy(200)
        // Ohne den Spielleiter noch kein „Go!“ – er soll vom Start nicht überrascht werden.
        assertFalse(g.master.snapshot().ready!!.allReady)
        assertFalse(SoundEvent.GO in sounds)
        assertTrue(g.master.confirmMasterReady())
        assertFalse(g.master.confirmMasterReady(), "nur einmal")
        assertTrue(g.master.snapshot().ready!!.allReady)
        assertTrue(SoundEvent.GO in sounds)
        // Die Runde beginnt: Abfrage schließt überall.
        assertTrue(g.master.release())
        g.time.advanceBy(200)
        assertNull(g.master.snapshot().ready)
        assertNull(g.view("Axel").readyAsk)
    }

    @Test
    fun `get ready while a check runs does not restart it`() {
        val g = game()
        val allReady = mutableListOf<Int>()
        g.master.onAllReady = { allReady += it }
        assertTrue(g.master.askReady())
        g.time.advanceBy(200)
        g.player("Axel").engine.confirmReady()
        g.time.advanceBy(200)
        assertFalse(g.master.askReady(), "läuft schon")
        assertEquals(listOf("Axel"), g.master.snapshot().ready?.readyNames)
        g.player("Peter").engine.confirmReady()
        g.player("Clara").engine.confirmReady()
        g.time.advanceBy(200)
        assertTrue(allReady.isEmpty(), "Spielleiter fehlt noch")
        g.master.confirmMasterReady()
        assertEquals(listOf(g.master.readyCheckId), allReady)
        g.master.cancelReady()
        assertTrue(g.master.askReady(), "nach dem Abbrechen wieder möglich")
    }

    @Test
    fun `ready check starts by itself after the reveal when enabled`() {
        val g = game()
        g.master.updateSettings(g.master.settings.copy(quizAutoReadyMs = 5_000))
        g.master.chooseKind(GameKind.QUIZ)
        val q = { n: Int -> QuizQuestion("q$n", "Technik", QuizLevel.EASY, "F$n?", listOf("R", "A", "B", "C"), 0, "", "") }
        g.master.startQuiz(listOf(q(1), q(2)))
        g.time.advanceBy(14_500) // Antwortzeit vorbei, Auflösung
        assertEquals(MasterEngine.Phase.QUIZ_REVEAL, g.master.snapshot().phase)
        assertNull(g.master.snapshot().ready)
        // Eine offene Challenge hält die Automatik auf.
        g.player("Peter").engine.challenge("?")
        g.time.advanceBy(6_000)
        assertNull(g.master.snapshot().ready)
        g.master.decideChallenge(ChallengeDecision.KEEP)
        g.time.advanceBy(1_200)
        assertTrue(g.master.snapshot().ready != null)
        assertTrue(g.view("Clara").readyAsk != null)
        // Nach der letzten Frage nicht.
        g.master.nextQuestion()
        g.time.advanceBy(30_000)
        assertNull(g.master.snapshot().ready)
        assertEquals(5_000L, SettingsCodec.fromMap(SettingsCodec.toMap(Settings(quizAutoReadyMs = 5_000))).settings.quizAutoReadyMs)
        assertEquals(0L, Settings(quizAutoReadyMs = -1).normalized().quizAutoReadyMs)
    }

    @Test
    fun `round can start without everyone ready`() {
        val g = game()
        g.master.askReady()
        g.time.advanceBy(200)
        g.player("Axel").engine.confirmReady()
        g.time.advanceBy(200)
        assertTrue(g.master.release())
        g.time.advanceBy(200)
        assertNull(g.view("Peter").readyAsk)
    }

    @Test
    fun `stale ready answers are ignored`() {
        val g = game()
        g.master.askReady()
        g.time.advanceBy(200)
        g.master.cancelReady()
        g.master.askReady()
        g.master.onMessage("Peter", Message.Ready(1))
        assertEquals(listOf(g.master.masterReadyLabel, "Axel", "Peter", "Clara"), g.master.snapshot().ready?.waitingNames)
    }

    @Test
    fun `review shows a ticker until the master decides`() {
        val g = game()
        g.master.chooseKind(GameKind.QUIZ)
        g.master.startQuiz(listOf(QuizQuestion("q", "Technik", QuizLevel.EASY, "F?", listOf("R", "A", "B", "C"), 0, "", "")))
        g.time.advanceBy(20_000)
        assertFalse(g.master.snapshot().reviewing)
        assertTrue(g.master.startReview())
        g.time.advanceBy(200)
        assertTrue(g.view("Peter").reviewing)
        assertTrue(g.master.snapshot().reviewing)
        g.master.decideChallenge(ChallengeDecision.ANNUL)
        g.time.advanceBy(200)
        assertFalse(g.view("Peter").reviewing)
        assertFalse(g.master.startReview(), "nach der Entscheidung keine Prüfung nebenbei")
        g.time.advanceBy(200)
        assertFalse(g.view("Clara").reviewing)
    }

    @Test
    fun `a challenge starts the ticker and the decision ends it`() {
        val g = game()
        g.master.chooseKind(GameKind.QUIZ)
        g.master.startQuiz(listOf(QuizQuestion("q", "Technik", QuizLevel.EASY, "F?", listOf("R", "A", "B", "C"), 0, "", "")))
        g.time.advanceBy(20_000)
        assertTrue(g.player("Peter").engine.challenge("Stimmt nicht"))
        g.time.advanceBy(200)
        assertTrue(g.master.snapshot().reviewing)
        assertTrue(g.view("Clara").reviewing)
        g.master.decideChallenge(ChallengeDecision.KEEP)
        g.time.advanceBy(200)
        assertFalse(g.view("Clara").reviewing)
        assertFalse(g.master.snapshot().reviewing)
        // Ohne Entscheidung: endReview beendet das Laufband ebenfalls.
        g.player("Clara").engine.challenge("")
        g.time.advanceBy(200)
        assertTrue(g.view("Axel").reviewing)
        g.master.endReview()
        g.time.advanceBy(200)
        assertFalse(g.view("Axel").reviewing)
    }

    @Test
    fun `ready and review messages survive the codec`() {
        for (m in listOf(Message.ReadyCheck(3, true), Message.ReadyCheck(3, false), Message.Ready(3), Message.Review(true))) {
            assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
        }
        assertTrue(SoundSynth.render(SoundEvent.GO).isNotEmpty())
    }
}
