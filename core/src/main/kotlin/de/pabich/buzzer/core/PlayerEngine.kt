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
                log("Runde beendet")
                update { it.copy(timer = null) }
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
