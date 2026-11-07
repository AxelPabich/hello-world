package de.pabich.buzzer.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * Intensivtest (Wunsch vom 07.11.2026): merkt sich, ob die zuletzt gezeigten Quizfragen auf diesen Bildschirm
 * passten – die Frage in ihrem Bereich und alle vier Antworten ohne Scrollen. Die App meldet das im Testmodus an
 * den Spielleiter. Schlüssel sind Fragetext bzw. die Antworten in der angezeigten Reihenfolge.
 */
object QuizFitMonitor {
    data class Fit(val fits: Boolean, val fontSp: Float)

    private val questions = ConcurrentHashMap<String, Fit>()
    private val answers = ConcurrentHashMap<String, Fit>()

    fun reportQuestion(text: String, fit: Fit) {
        if (questions.size > 50) questions.clear()
        questions[text] = fit
    }

    fun reportAnswers(list: List<String>, fit: Fit) {
        if (answers.size > 50) answers.clear()
        answers[key(list)] = fit
    }

    fun question(text: String): Fit? = questions[text]
    fun answers(list: List<String>): Fit? = answers[key(list)]

    private fun key(list: List<String>) = list.joinToString("\u0001")
}
