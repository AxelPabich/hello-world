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
    private val send: (Message) -> Unit,
) {
    data class View(
        val display: Display,
        val place: Int,
        val score: String,
        val hint: String,
        val board: List<ScoreEntry> = emptyList(),
        val boardMaxScore: Long = 0,
        val showBoard: Boolean = false,
    )

    var onViewChanged: ((View) -> Unit)? = null

    var view = View(Display.BLACK, 0, "0", "Warte auf den Spielleiter")
        private set

    private var round = -1
    private var attempt = -1
    private var mode = StartMode.TAP
    private var suddenDeath = false
    private var active = false
    private var goAt: Long? = null
    private var fingerDown = false
    private val tasks = mutableListOf<Cancellable>()

    fun onMessage(message: Message) {
        when (message) {
            is Message.Ping -> send(Message.Pong(message.seq, message.masterTime, clock.now()))
            is Message.Arm -> {
                startContest(message.round, message.attempt, StartMode.HOLD_RELEASE, message.suddenDeath)
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
                show(message.display, hintFor(message.display), message.place, message.score)
            }
            is Message.Freeze -> endContest()
            is Message.Reset -> {
                endContest()
                show(Display.BLACK, "Warte auf die nächste Frage", 0, message.score)
            }
            is Message.Scoreboard -> {
                view = view.copy(board = message.entries, boardMaxScore = message.maxScore, showBoard = message.show)
                onViewChanged?.invoke(view)
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
        if (mode == StartMode.HOLD_RELEASE && !fingerDown) {
            // Nach dem Abheben wurde die Freigabe schon ausgelöst: Frühstart.
            buzz(m.goAtLocal - 1)
            return
        }
        tasks += scheduler.schedule((m.goAtLocal - clock.now()).coerceAtLeast(0)) {
            if (active) show(Display.YELLOW, if (mode == StartMode.TAP) "Jetzt tippen!" else "Loslassen!")
        }
        tasks += scheduler.schedule((m.goAtLocal + m.timeLimitMs - clock.now()).coerceAtLeast(0)) { endContest() }
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

    private fun buzz(eventTime: Long) {
        val start = goAt ?: return
        endContest()
        if (eventTime < start) {
            send(Message.Buzz(round, attempt, 0, falseStart = true))
            show(Display.GRAY, "Frühstart")
        } else {
            send(Message.Buzz(round, attempt, eventTime - start, falseStart = false))
            show(Display.PRESSED, "Gedrückt – Auswertung läuft")
        }
    }

    private fun startContest(round: Int, attempt: Int, mode: StartMode, suddenDeath: Boolean) {
        endContest()
        this.round = round
        this.attempt = attempt
        this.mode = mode
        this.suddenDeath = suddenDeath
        goAt = null
        active = true
        // Während des Buzzerns lenkt die Rangliste nur ab.
        view = view.copy(showBoard = false)
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

    private fun show(display: Display, hint: String, place: Int = 0, score: String = view.score) {
        view = view.copy(display = display, place = place, score = score, hint = hint)
        onViewChanged?.invoke(view)
    }

    private fun hintFor(display: Display) = when (display) {
        Display.GREEN -> "Du bist dran!"
        Display.RED -> "Platz"
        Display.PINK -> "Gleichstand – Sudden Death"
        Display.GRAY -> "Ausgeschieden"
        else -> ""
    }
}
