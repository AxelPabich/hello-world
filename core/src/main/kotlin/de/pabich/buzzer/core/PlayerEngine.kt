package de.pabich.buzzer.core

/**
 * Spieler-Seite: setzt die Freigabe zum vorgegebenen Zeitpunkt um, misst die Reaktion am
 * Touch-Ereignis und meldet sie dem Master. Nicht threadsicher (siehe [MasterEngine]).
 *
 * Touch-Zeiten müssen aus derselben Uhr stammen wie [clock] (Android: `MotionEvent.getEventTime()`
 * bzw. `PointerInputChange.uptimeMillis` zu `SystemClock.uptimeMillis()`).
 */
class PlayerEngine(
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val log: (String) -> Unit = {},
    private val send: (Message) -> Unit,
) {
    enum class TimerKind { REACTION, ANSWER }

    /** Zeitbalken am unteren Rand; [startAt] in der Uhr von [clock]. */
    data class Timer(val kind: TimerKind, val startAt: Long, val durationMs: Long)

    /** Spielregeln vom Master. Limits `null` = ohne Limit; leere [emojis] = Emoticons aus. */
    data class Rules(
        val reactionLimitMs: Long? = null,
        val answerLimitMs: Long? = null,
        val messagesEnabled: Boolean = false,
        val emojis: List<String> = emptyList(),
        val profileName: String = "",
        val sounds: Set<SoundEvent> = DEFAULT_SOUNDS,
    )

    enum class QuizStage {
        /** Frage unterwegs, erscheint gleich überall gleichzeitig. */
        WAITING,
        QUESTION,
        ANSWERS,

        /** Zeit abgelaufen, warte auf die Auflösung. */
        CLOSED,
        REVEALED,
    }

    /** Laufende Quizfrage aus Sicht dieses Spielers; Zeiten in der Uhr von [clock]. */
    data class QuizView(
        val number: Int,
        val total: Int,
        val category: String,
        val level: QuizLevel,
        val text: String,
        val answers: List<String>,
        val questionAt: Long,
        val answersAt: Long,
        val durationMs: Long,
        /** Ende der Antwortzeit ab Einblenden (Countdown kann sie verkürzen). */
        val deadlineMs: Long,
        val stage: QuizStage,
        val chosen: Int = -1,
        val chosenElapsedMs: Long? = null,
        val result: Message.QuizResult? = null,
        val challenged: Boolean = false,
    ) {
        /** Antwortzeit läuft (Emoticons dann nur in ihrer eigenen Bahn). */
        val timerRunning: Boolean get() = stage == QuizStage.ANSWERS || stage == QuizStage.QUESTION || stage == QuizStage.WAITING
    }

    data class View(
        val display: Display,
        val place: Int,
        val score: String,
        val hint: String,
        val board: List<ScoreEntry> = emptyList(),
        val boardMaxScore: Long = 0,
        /** Rundenpause: Rangliste, Limits und Kurznachrichten sichtbar. */
        val showBoard: Boolean = false,
        val timer: Timer? = null,
        val rules: Rules = Rules(),
        val roster: List<Pair<PlayerId, String>> = emptyList(),
        /** Spielpause: Overlay „Kurze Pause“. */
        val paused: Boolean = false,
        val kind: GameKind? = null,
        val quiz: QuizView? = null,
        /** Offene „Bereit?“-Abfrage des Spielleiters (ID), sonst `null`. */
        val readyAsk: Int? = null,
        /** Für die laufende Abfrage schon bestätigt. */
        val readyConfirmed: Boolean = false,
        /** Der Spielleiter prüft die Auflösung – Laufband anzeigen. */
        val reviewing: Boolean = false,
    )

    var onViewChanged: ((View) -> Unit)? = null
    var onEmote: ((from: String, emoji: String) -> Unit)? = null
    var onChat: ((from: String, text: String, toAll: Boolean) -> Unit)? = null
    var onProfile: ((name: String, values: Map<String, String>) -> Unit)? = null
    var onBye: (() -> Unit)? = null
    var onSound: ((SoundEvent) -> Unit)? = null

    /** Ein Durchgang beginnt (Buzzer scharf); für das Summen – nie zeitgleich mit der Freigabe. */
    var onArmed: (() -> Unit)? = null
    var onReplay: ((Message.Replay) -> Unit)? = null

    var view = View(Display.BLACK, 0, "0", "Warte auf den Spielleiter")
        private set

    /** Zeitpunkt (Uhr von [clock]) der letzten Nachricht vom Master – für die Diagnose. */
    var lastMessageAt: Long? = null
        private set

    private var round = -1
    private var attempt = -1
    private var mode = StartMode.TAP
    private var suddenDeath = false
    private var active = false
    private var goAt: Long? = null
    private var fingerDown = false
    private val tasks = mutableListOf<Cancellable>()
    private val quizTasks = mutableListOf<Cancellable>()
    private var lastLoggedDisplay: String? = null

    fun onMessage(message: Message) {
        lastMessageAt = clock.now()
        when (message) {
            is Message.Ping -> send(Message.Pong(message.seq, message.masterTime, clock.now()))
            is Message.Arm -> {
                startContest(message.round, message.attempt, StartMode.HOLD_RELEASE, message.suddenDeath)
                log("Runde ${message.round}: Finger auflegen" + if (message.suddenDeath) " (Sudden Death)" else "")
                if (fingerDown) {
                    send(Message.Hold(round, attempt, true))
                    show(Display.BLUE, "Halten …")
                } else {
                    showIdle()
                }
            }
            is Message.GoAt -> onGoAt(message)
            is Message.State -> {
                endContest()
                // Der Master wiederholt Anzeigen; gleiche aufeinanderfolgende nur einmal protokollieren.
                val line = "Anzeige: ${message.display}" + if (message.place > 0) " Platz ${message.place}" else ""
                if (line != lastLoggedDisplay) log(line)
                lastLoggedDisplay = line
                // Ein laufender Antwort-Timer wird vom Master direkt danach erneut geschickt.
                update { it.copy(timer = null) }
                show(message.display, hintFor(message.display), message.place, message.score)
            }
            is Message.Freeze -> {
                endContest()
                if (view.timer?.kind == TimerKind.REACTION) update { it.copy(timer = null) }
            }
            is Message.Reset -> {
                endContest()
                cancelQuizTasks()
                log("Runde beendet")
                update { it.copy(timer = null, quiz = null) }
                show(Display.BLACK, "Warte auf die nächste Frage", 0, message.score)
            }
            is Message.Scoreboard ->
                update { it.copy(board = message.entries, boardMaxScore = message.maxScore, showBoard = message.show) }
            is Message.Config -> update {
                it.copy(
                    rules = Rules(
                        reactionLimitMs = message.reactionLimitMs.takeIf { ms -> ms > 0 },
                        answerLimitMs = message.answerLimitMs.takeIf { ms -> ms > 0 },
                        messagesEnabled = message.messagesEnabled,
                        emojis = message.emojis,
                        profileName = message.profileName,
                        sounds = message.sounds,
                    ),
                )
            }
            is Message.AnswerTimer -> update {
                it.copy(timer = if (message.remainingMs > 0) Timer(TimerKind.ANSWER, clock.now(), message.remainingMs) else null)
            }
            is Message.Roster -> update { it.copy(roster = message.entries) }
            is Message.Emote -> onEmote?.invoke(message.from, message.emoji)
            is Message.Chat -> onChat?.invoke(message.from, message.text, message.toAll)
            is Message.Profile -> onProfile?.invoke(message.name, message.values)
            is Message.Sound -> onSound?.invoke(message.event)
            is Message.Pause -> {
                log(if (message.on) "Pause" else "Pause beendet")
                update { it.copy(paused = message.on) }
            }
            is Message.Replay -> onReplay?.invoke(message)
            is Message.Mode -> {
                cancelQuizTasks()
                log("Spielart: ${message.kind?.label ?: "noch offen"}")
                update { it.copy(kind = message.kind, quiz = null) }
                show(Display.BLACK, if (message.kind == GameKind.QUIZ) "Quiz – gleich kommt die erste Frage" else "Warte auf den Spielleiter", 0, view.score)
            }
            is Message.QuizAsk -> onQuizAsk(message)
            is Message.ReadyCheck -> {
                log(if (message.on) "Bereit-Abfrage" else "Bereit-Abfrage beendet")
                update { it.copy(readyAsk = if (message.on) message.id else null, readyConfirmed = false) }
            }
            is Message.Review -> update { it.copy(reviewing = message.on) }
            is Message.QuizDeadline -> {
                val q = view.quiz ?: return
                if (q.number != message.number || q.stage == QuizStage.REVEALED) return
                log("Countdown: noch bis ${message.deadlineMs} ms")
                update { it.copy(quiz = q.copy(deadlineMs = message.deadlineMs)) }
                scheduleQuizClose()
            }
            is Message.QuizResult -> {
                val q = view.quiz ?: return
                if (q.number != message.number) return
                cancelQuizTasks()
                log("Auflösung: ${message.outcome}" + (if (message.gained.isNotEmpty()) " ${message.gained}" else "") + if (message.corrected) " (korrigiert)" else "")
                update { it.copy(quiz = q.copy(stage = QuizStage.REVEALED, result = message), score = message.score) }
            }
            Message.Bye -> {
                log("Spielleiter hat die Gruppe geschlossen")
                onBye?.invoke()
            }
            else -> Unit
        }
    }

    private fun onGoAt(m: Message.GoAt) {
        if (!(active && m.round == round && m.attempt == attempt)) {
            startContest(m.round, m.attempt, m.mode, m.suddenDeath)
            showIdle()
        }
        goAt = m.goAtLocal
        log("Freigabe in ${m.goAtLocal - clock.now()} ms" + if (m.suddenDeath) " (Sudden Death)" else "")
        if (mode == StartMode.HOLD_RELEASE && !fingerDown) {
            // Nach dem Abheben wurde die Freigabe schon ausgelöst: Frühstart.
            buzz(m.goAtLocal - 1)
            return
        }
        if (m.timeLimitMs > 0) update { it.copy(timer = Timer(TimerKind.REACTION, m.goAtLocal, m.timeLimitMs)) }
        tasks += scheduler.schedule((m.goAtLocal - clock.now()).coerceAtLeast(0)) {
            if (active) show(Display.YELLOW, if (mode == StartMode.TAP) "Jetzt tippen!" else "Loslassen!")
        }
        if (m.timeLimitMs > 0) {
            tasks += scheduler.schedule((m.goAtLocal + m.timeLimitMs - clock.now()).coerceAtLeast(0)) { endContest() }
        }
    }

    fun onTouchDown(eventTime: Long) {
        fingerDown = true
        if (!active) return
        if (mode == StartMode.TAP) {
            buzz(eventTime)
        } else if (goAt == null) {
            send(Message.Hold(round, attempt, true))
            show(Display.BLUE, "Halten …")
        }
    }

    fun onTouchUp(eventTime: Long) {
        fingerDown = false
        if (!active || mode != StartMode.HOLD_RELEASE) return
        if (goAt == null) {
            send(Message.Hold(round, attempt, false))
            showIdle()
        } else {
            buzz(eventTime)
        }
    }

    fun sendEmote(emoji: String) = send(Message.Emote("", emoji))

    /** Quiz: Antwort an [position] (eigene Reihenfolge) angetippt. */
    fun tapAnswer(position: Int, eventTime: Long): Boolean {
        val q = view.quiz ?: return false
        if (q.stage != QuizStage.ANSWERS || q.chosen >= 0 || position !in q.answers.indices) return false
        val elapsed = (eventTime - q.answersAt).coerceAtLeast(0)
        if (elapsed > q.deadlineMs) return false
        log("Antwort ${position + 1} nach $elapsed ms")
        send(Message.QuizAnswer(q.number, position, elapsed))
        update { it.copy(quiz = q.copy(chosen = position, chosenElapsedMs = elapsed)) }
        return true
    }

    /** Quiz: aktuelle Frage anfechten (nur nach der Auflösung, einmal je Frage). */
    fun challenge(reason: String): Boolean {
        val q = view.quiz ?: return false
        if (q.stage != QuizStage.REVEALED || q.challenged) return false
        send(Message.QuizChallenge(q.number, reason.trim().take(MAX_CHAT_LENGTH)))
        log("Challenge gesendet")
        update { it.copy(quiz = q.copy(challenged = true)) }
        return true
    }

    /** „Bereit!“ auf die Abfrage des Spielleiters. */
    fun confirmReady(): Boolean {
        val id = view.readyAsk ?: return false
        if (view.readyConfirmed) return false
        send(Message.Ready(id))
        log("Bereit bestätigt")
        update { it.copy(readyConfirmed = true) }
        return true
    }

    /** Tipp-Hinweis für den Master. */
    fun sendTyping(on: Boolean) = send(Message.Typing(on))

    private fun onQuizAsk(m: Message.QuizAsk) {
        endContest()
        cancelQuizTasks()
        val now = clock.now()
        val stage = when {
            now >= m.answersAtLocal -> QuizStage.ANSWERS
            now >= m.questionAtLocal -> QuizStage.QUESTION
            else -> QuizStage.WAITING
        }
        log("Frage ${m.number}/${m.total}, erscheint in ${m.questionAtLocal - now} ms")
        val q = QuizView(
            number = m.number, total = m.total, category = m.category, level = m.level, text = m.text,
            answers = m.answers, questionAt = m.questionAtLocal, answersAt = m.answersAtLocal,
            durationMs = m.durationMs, deadlineMs = m.durationMs, stage = stage,
        )
        // Während der Frage lenkt die Rangliste nur ab.
        update { it.copy(quiz = q, showBoard = false, kind = GameKind.QUIZ) }
        fun to(stage: QuizStage, at: Long) {
            quizTasks += scheduler.schedule((at - clock.now()).coerceAtLeast(0)) {
                val cur = view.quiz
                if (cur != null && cur.number == m.number && cur.stage.ordinal < stage.ordinal) update { it.copy(quiz = cur.copy(stage = stage)) }
            }
        }
        to(QuizStage.QUESTION, m.questionAtLocal)
        to(QuizStage.ANSWERS, m.answersAtLocal)
        scheduleQuizClose()
    }

    private var closeTask: Cancellable? = null

    private fun scheduleQuizClose() {
        closeTask?.cancel()
        val q = view.quiz ?: return
        val number = q.number
        closeTask = scheduler.schedule((q.answersAt + q.deadlineMs - clock.now()).coerceAtLeast(0)) {
            val cur = view.quiz
            if (cur != null && cur.number == number && cur.stage.ordinal < QuizStage.CLOSED.ordinal) {
                update { it.copy(quiz = cur.copy(stage = QuizStage.CLOSED)) }
            }
        }
    }

    private fun cancelQuizTasks() {
        quizTasks.forEach { it.cancel() }
        quizTasks.clear()
        closeTask?.cancel()
        closeTask = null
    }

    fun sendChat(to: String, text: String) = send(Message.ChatSend(to, text.take(MAX_CHAT_LENGTH)))

    fun setReceiveEmojis(receive: Boolean) = send(Message.Prefs(receive))

    private fun buzz(eventTime: Long) {
        val start = goAt ?: return
        endContest()
        update { it.copy(timer = null) }
        if (eventTime < start) {
            log("Frühstart (${start - eventTime} ms zu früh)")
            send(Message.Buzz(round, attempt, 0, falseStart = true))
            show(Display.GRAY, "Frühstart")
        } else {
            log("Gebuzzert: ${eventTime - start} ms")
            send(Message.Buzz(round, attempt, eventTime - start, falseStart = false))
            show(Display.PRESSED, "Gedrückt – Auswertung läuft")
        }
    }

    private fun startContest(round: Int, attempt: Int, mode: StartMode, suddenDeath: Boolean) {
        endContest()
        lastLoggedDisplay = null
        this.round = round
        this.attempt = attempt
        this.mode = mode
        this.suddenDeath = suddenDeath
        goAt = null
        active = true
        // Während des Buzzerns lenkt die Rangliste nur ab.
        view = view.copy(showBoard = false, timer = null)
        onArmed?.invoke()
    }

    private fun endContest() {
        active = false
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun showIdle() {
        val display = if (suddenDeath) Display.PINK else Display.BLACK
        val hint = when {
            mode == StartMode.HOLD_RELEASE -> "Finger auflegen und halten"
            suddenDeath -> "Sudden Death – gleich geht's los"
            else -> "Achtung …"
        }
        show(display, hint)
    }

    private fun update(change: (View) -> View) {
        view = change(view)
        onViewChanged?.invoke(view)
    }

    private fun show(display: Display, hint: String, place: Int = 0, score: String = view.score) =
        update { it.copy(display = display, place = place, score = score, hint = hint) }

    private fun hintFor(display: Display) = when (display) {
        Display.GREEN -> "Du bist dran!"
        Display.RED -> "Platz"
        Display.PINK -> "Gleichstand – Sudden Death"
        Display.GRAY -> "Ausgeschieden"
        else -> ""
    }
}
