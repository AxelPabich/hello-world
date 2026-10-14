package de.pabich.buzzer.core

import kotlin.random.Random

/**
 * Spiellogik des Masters (Spezifikation, Abschnitte 3–7). Nicht threadsicher: alle Aufrufe,
 * auch die des [Scheduler], müssen auf demselben Thread laufen.
 */
class MasterEngine(
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
    private val send: (PlayerId, Message) -> Unit,
) {
    enum class Phase {
        READY, WAIT_HOLD, COUNTDOWN, RUNNING, SUDDEN_DEATH_PREP, ANSWERING,

        /** Antwortzeit abgelaufen, der Master tippt „Weiter“. */
        ANSWER_TIMEOUT,
        ROUND_OVER,

        /** Quiz: Frage sichtbar, Antworten kommen gleich. */
        QUIZ_QUESTION,

        /** Quiz: Antworten offen, Timer läuft. */
        QUIZ_ANSWERS,

        /** Quiz: Auflösung – Pause bis zur nächsten Frage. */
        QUIZ_REVEAL,
    }

    data class PlayerRow(
        val id: PlayerId,
        val name: String,
        val display: Display,
        val place: Int,
        val reactionMs: Long?,
        val score: String,
        val scoreValue: Long,
        val rttMs: Long?,
        val connected: Boolean,
    )

    data class Snapshot(
        val phase: Phase,
        val round: Int,
        val settings: Settings,
        val scoringLocked: Boolean,
        val timeLimitLocked: Boolean,
        val players: List<PlayerRow>,
        /** Ende der Antwortzeit (Master-Uhr) oder `null`. */
        val answerDeadline: Long?,
        val profileName: String,
        val canShowReplay: Boolean,
        /** Spiel pausiert (Overlay bei den Spielern). */
        val paused: Boolean,
        /** Letzte gewertete Runde lässt sich korrigieren. */
        val canCorrectLastRound: Boolean,
        /** Runde und Sieger der letzten gewerteten Runde (`null` = kein Sieger/storniert). */
        val lastRound: Int?,
        val lastRoundWinner: PlayerId?,
        /** Gewählte Spielart, `null` = noch nicht gewählt. */
        val kind: GameKind?,
        /** Laufendes bzw. zuletzt gespieltes Quiz. */
        val quiz: QuizSnapshot?,
        /** Wertung, nach der Punkte angezeigt werden (Quiz: aus der Quiz-Wertung). */
        val scoring: ScoringMode,
        /** Ein Teilnehmer tippt gerade eine Kurznachricht. */
        val someoneTyping: Boolean,
        /** Laufende „Bereit?“-Abfrage, sonst `null`. */
        val ready: ReadyState?,
        /** Der Spielleiter prüft gerade die Auflösung (Spieler sehen ein Laufband). */
        val reviewing: Boolean,
    )

    /** „Bereit?“-Abfrage: wer hat bestätigt, wer fehlt noch. */
    data class ReadyState(val readyNames: List<String>, val waitingNames: List<String>) {
        val allReady: Boolean get() = waitingNames.isEmpty()
    }

    data class QuizRow(
        val id: PlayerId,
        val name: String,
        val answered: Boolean,
        val elapsedMs: Long?,
        /** Gewählte Antwort (Paket-Reihenfolge) – erst nach der Auflösung. */
        val chosen: Int?,
        val outcome: QuizOutcome?,
        val gained: Long,
    )

    data class QuizChallengeInfo(val playerId: PlayerId, val name: String, val reason: String)

    data class QuizSnapshot(
        val number: Int,
        val total: Int,
        val question: QuizQuestion,
        /** Einblenden der Antworten (Master-Uhr). */
        val answersAt: Long,
        /** Ende der Antwortzeit (Master-Uhr), ggf. durch den Countdown verkürzt. */
        val deadline: Long,
        val durationMs: Long,
        val revealed: Boolean,
        val annulled: Boolean,
        val rows: List<QuizRow>,
        val challenges: List<QuizChallengeInfo>,
        val decision: ChallengeDecision?,
        /** Alle Fragen gespielt (oder abgebrochen). */
        val finished: Boolean,
        val hasNext: Boolean,
    )

    /** Ergebnis einer beendeten Runde – Grundlage für nachträgliche Korrekturen. */
    private data class RoundResult(
        val round: Int,
        val winner: PlayerId?,
        val amount: Long,
        val reactions: Map<PlayerId, Long?>,
    )

    private enum class Status { IDLE, WAITING, HOLDING, BUZZED, OUT }

    private class Player(val id: PlayerId, val name: String, val local: Boolean) {
        val sync = ClockSync()
        var typingUntil = 0L
        var connected = true
        var receiveEmojis = true
        var score = 0L
        var status = Status.IDLE
        var roundReactionMs: Long? = null
        var contestReactionMs: Long? = null
    }

    /** Eine Quizfrage im Spiel. */
    private class QuizTurn(
        val number: Int,
        var question: QuizQuestion,
        val durationMs: Long,
        val participants: Set<PlayerId>,
        val answersAt: Long,
    ) {
        /** Je Spieler: angezeigte Position → Index in der Paket-Reihenfolge. */
        val perms = HashMap<PlayerId, List<Int>>()

        /** Je Spieler: gewählter Index (Paket-Reihenfolge) und Zeit ab Einblenden. */
        val answers = LinkedHashMap<PlayerId, Pair<Int, Long>>()
        var deadlineElapsed = durationMs
        val tasks = mutableListOf<Cancellable>()
        var revealTask: Cancellable? = null
        var revealed = false
        var annulled = false
        var awards: Map<PlayerId, Long> = emptyMap()
        val challenges = mutableListOf<QuizChallengeInfo>()
        var decision: ChallengeDecision? = null

        /** Die in der Statistik gezählte Bewertung (`null` = zählt nicht). */
        var counted: List<ParticipantResult>? = null
    }

    /** Ein Durchgang mit Freigabe: die Hauptrunde oder ein Sudden Death. */
    private class Contest(val attempt: Int, val participants: Set<PlayerId>, val suddenDeath: Boolean) {
        var goAtMaster: Long? = null
        val pending = mutableListOf<PlayerId>()
        val tasks = mutableListOf<Cancellable>()
        var publishTask: Cancellable? = null
        var finished = false
    }

    var onChanged: ((Snapshot) -> Unit)? = null

    /** Emoticon für die Anzeige auf dem Master-Gerät. */
    var onEmote: ((from: String, emoji: String) -> Unit)? = null

    /** Kurznachricht an den Master oder an alle. */
    var onChat: ((from: String, text: String, toAll: Boolean) -> Unit)? = null

    /** Klang auf dem Master-Gerät (bereits nach den Profil-Einstellungen gefiltert). */
    var onSound: ((SoundEvent) -> Unit)? = null

    /** Spielverlauf für die Anzeige auf dem Master-Gerät. */
    var onReplay: ((Message.Replay) -> Unit)? = null

    /** Quiz-Statistik: Frage aufgelöst mit den Ergebnissen aller Teilnehmer. */
    /** Alle haben „Bereit!“ bestätigt (Abfrage [readyCheckId]); die App startet im Quiz dann automatisch. */
    var onAllReady: ((id: Int) -> Unit)? = null

    /** Nummer der laufenden bzw. letzten Bereit-Abfrage. */
    val readyCheckId: Int get() = readyId

    var onQuizRevealed: ((QuizQuestion, List<ParticipantResult>) -> Unit)? = null

    /** Quiz-Statistik: Frage übersprungen – gesehen, aber nicht gewertet. */
    var onQuizSkipped: ((QuizQuestion, List<String>) -> Unit)? = null
    var onQuizChallenge: ((QuizQuestion, from: String, reason: String) -> Unit)? = null
    /**
     * Entscheidung über eine Auflösung. [before]: die bisher gezählte Bewertung (wie bei
     * [onQuizRevealed]), [after]: die neue – `null`, wenn die Frage nicht mehr zählt.
     */
    var onQuizDecision: ((QuizQuestion, ChallengeDecision, newCorrect: Int?, before: List<ParticipantResult>?, after: List<ParticipantResult>?) -> Unit)? = null

    /** Name des Spielleiters für Kurznachrichten und Emoticons. */
    var masterName = "Spielleiter"

    var profileName = ""
        private set

    var settings = Settings()
        private set

    private val players = LinkedHashMap<PlayerId, Player>()
    private var phase = Phase.READY
    private var round = 0
    private var attemptCounter = 0
    private var gameStarted = false
    private var contest: Contest? = null

    /** Platzierung als Gruppen; Gruppen mit mehr als einem Spieler sind Gleichstände. */
    private val order = mutableListOf<MutableList<PlayerId>>()
    private var answerIndex = -1
    private var pingSeq = 0
    private var pingTick = 0
    private var pingTask: Cancellable? = null
    private var answerTask: Cancellable? = null
    private var answerTimerPlayer: PlayerId? = null
    private var answerDeadline: Long? = null

    /** Punktestände nach jeder Runde; der erste Eintrag ist der Start (alle 0). */
    private val history = mutableListOf<Map<PlayerId, Long>>()
    private var recordedRound = -1
    private var lastResult: RoundResult? = null
    private var pendingWinner: Pair<PlayerId, Long>? = null

    var paused = false
        private set

    var kind: GameKind? = null
        private set

    private var readyId = 0
    private var readyOpen = false
    private val readyPlayers = mutableSetOf<PlayerId>()
    private var reviewing = false
    private var quizQuestions: List<QuizQuestion> = emptyList()
    private var quizIndex = -1
    private var turn: QuizTurn? = null
    private var quizFinished = false

    /** Wertung, nach der Punkte gezählt und angezeigt werden. */
    private val scoring: ScoringMode
        get() = if (kind == GameKind.QUIZ) {
            if (settings.quizScoring.usesTime) ScoringMode.REMAINING_TIME else ScoringMode.CORRECT_ANSWERS
        } else {
            settings.scoring
        }

    // ------------------------------------------------------------------ Spieler

    fun addPlayer(id: PlayerId, name: String, local: Boolean = false) {
        val player = Player(id, name, local)
        if (local) player.sync.setLocal()
        players[id] = player
        log("Spieler beigetreten: $name ($id)")
        if (!local) masterSound(SoundEvent.JOIN)
        send(id, configMessage())
        send(id, Message.Mode(kind))
        if (paused) send(id, Message.Pause(true))
        broadcastRoster()
        broadcastScoreboard()
        notifyChanged()
    }

    fun removePlayer(id: PlayerId) {
        val player = players[id] ?: return
        log("Spieler getrennt: ${player.name} ($id)")
        if (phase == Phase.READY || player.status == Status.IDLE) {
            players.remove(id)
            broadcastRoster()
            broadcastScoreboard()
            // Im Quiz wartet niemand mehr auf seine Antwort.
            turn?.takeIf { !it.revealed }?.let { checkAllAnswered(it) }
            notifyChanged()
            return
        }
        // Mitten in der Runde: bis zur nächsten Runde grau weiterführen.
        player.connected = false
        val wasAnswering = phase == Phase.ANSWERING && answeringPlayer() == id
        if (wasAnswering) cancelAnswerTimer()
        player.status = Status.OUT
        val c = contest
        when {
            c != null && id in c.participants -> {
                c.pending.remove(id)
                if (c.goAtMaster == null && settings.startMode == StartMode.HOLD_RELEASE) maybeStartAfterHold(c)
                checkAllDone(c)
            }
            wasAnswering -> advance(answerIndex + 1)
        }
        broadcastRoster()
        broadcastStates()
    }

    /** Beitritt in jeder Pause – auch mitten im Spiel (die Punkte davor fehlen dem Neuen dann). */
    fun canAcceptJoin(): Boolean = phase == Phase.READY || phase == Phase.ROUND_OVER || phase == Phase.QUIZ_REVEAL

    // ------------------------------------------------------------------ Einstellungen

    /** Nur zwischen den Runden. Wertung und (bei Zeitwertung) Zeitlimit sind nach Spielbeginn gesperrt. */
    fun updateSettings(newSettings: Settings, profileName: String = this.profileName): Boolean {
        if (phase != Phase.READY) return false
        this.profileName = profileName
        var s = newSettings.normalized()
        if (gameStarted && kind != GameKind.QUIZ) {
            s = s.copy(scoring = settings.scoring)
            if (settings.scoring == ScoringMode.REMAINING_TIME) s = s.copy(timeLimitMs = settings.timeLimitMs)
        }
        settings = s
        log("Einstellungen geändert" + if (profileName.isNotEmpty()) " (Profil $profileName)" else "")
        players.values.filter { it.connected }.forEach { send(it.id, configMessage()) }
        broadcastScoreboard()
        notifyChanged()
        return true
    }

    /** Punkte zurücksetzen und Sperren aufheben. */
    fun newGame() {
        if (phase != Phase.READY) return
        gameStarted = false
        history.clear()
        recordedRound = -1
        lastResult = null
        players.values.forEach { it.score = 0 }
        players.values.forEach { send(it.id, Message.Reset(scoreText(it))) }
        broadcastScoreboard()
        notifyChanged()
    }

    // ------------------------------------------------------------------ Uhrenabgleich

    /** Startet den regelmäßigen Uhrenabgleich: beim Beitritt schnell, danach etwa alle 2 s zwischen den Runden. */
    fun start() {
        if (pingTask != null) return
        fun tick() {
            pingTick++
            val idle = phase == Phase.READY || phase == Phase.ROUND_OVER
            for (p in players.values) {
                if (!p.connected) continue
                if (p.sync.sampleCount < 8 || (idle && pingTick % 8 == 0)) {
                    send(p.id, Message.Ping(++pingSeq, clock.now()))
                }
            }
            pingTask = scheduler.schedule(250) { tick() }
        }
        tick()
    }

    fun stop() {
        cancelAnswerTimer()
        turn?.let { cancelQuizTasks(it) }
        pingTask?.cancel()
        pingTask = null
        contest?.let { cancelTasks(it) }
    }

    // ------------------------------------------------------------------ Bedienung durch den Master

    /** „Buzzer frei“ (Modus B) bzw. „Buzzer scharf“ (Modus A). */
    fun release(): Boolean {
        if (phase != Phase.READY || paused || kind == GameKind.QUIZ) return false
        val participants = players.values.filter { it.connected }
        if (participants.isEmpty()) return false
        endReadyCheck()
        round++
        gameStarted = true
        order.clear()
        answerIndex = -1
        for (p in participants) {
            p.roundReactionMs = null
            p.contestReactionMs = null
        }
        log("Runde $round freigegeben; Funk-Laufzeiten: " +
            participants.joinToString { "${it.name} ${it.sync.recentMaxRttMs ?: "?"} ms" })
        for (p in participants.filter { it.sync.rttOutlier }) {
            log("Hinweis: Funk-Laufzeit ${p.name} ${p.sync.recentMaxRttMs} ms (sonst ~${p.sync.typicalRttMs} ms)")
        }
        startContest(participants.map { it.id }.toSet(), suddenDeath = false)
        return true
    }

    fun markCorrect(): Boolean {
        if (phase != Phase.ANSWERING) return false
        val player = players[answeringPlayer() ?: return false] ?: return false
        cancelAnswerTimer()
        log("Richtig: ${player.name}")
        val amount = award(player.roundReactionMs)
        player.score += amount
        pendingWinner = player.id to amount
        phase = Phase.ROUND_OVER
        sendSound(listOf(player.id), SoundEvent.RIGHT)
        broadcastStates(roundEndExcept = player.id)
        return true
    }

    fun markWrong(): Boolean {
        if (phase != Phase.ANSWERING) return false
        val player = players[answeringPlayer() ?: return false] ?: return false
        cancelAnswerTimer()
        log("Falsch: ${player.name}")
        sendSound(listOf(player.id), SoundEvent.WRONG)
        player.status = Status.OUT
        advance(answerIndex + 1)
        broadcastStates()
        return true
    }

    /** „Weiter“ nach abgelaufener Antwortzeit (wenn nicht automatisch weitergeschaltet wird). */
    fun proceed(): Boolean {
        if (phase != Phase.ANSWER_TIMEOUT) return false
        advance(answerIndex + 1)
        broadcastStates()
        return true
    }

    private fun startAnswerTimer(id: PlayerId) {
        val limit = settings.answerLimit ?: return
        answerTimerPlayer = id
        answerDeadline = clock.now() + limit
        answerTask = scheduler.schedule(limit) { onAnswerTimeout(id) }
    }

    private fun cancelAnswerTimer() {
        answerTask?.cancel()
        answerTask = null
        answerTimerPlayer = null
        answerDeadline = null
    }

    private fun onAnswerTimeout(id: PlayerId) {
        if (phase != Phase.ANSWERING || answeringPlayer() != id) return
        cancelAnswerTimer()
        val player = players[id] ?: return
        log("Antwortzeit abgelaufen: ${player.name} – zählt als falsch")
        sendSound(listOf(player.id), SoundEvent.TIMEOUT)
        if (!player.local) masterSound(SoundEvent.TIMEOUT)
        player.status = Status.OUT
        if (settings.autoAdvanceOnTimeout) {
            advance(answerIndex + 1)
        } else {
            phase = Phase.ANSWER_TIMEOUT
        }
        broadcastStates()
    }

    /** „Nächste Runde“: alle Displays schwarz. Bricht eine laufende Runde ab. */
    fun nextRound() {
        if (kind == GameKind.QUIZ) return
        log("Nächste Runde")
        cancelAnswerTimer()
        contest?.let { cancelTasks(it) }
        contest = null
        phase = Phase.READY
        order.clear()
        answerIndex = -1
        players.values.removeAll { !it.connected }
        for (p in players.values) {
            p.status = Status.IDLE
            p.roundReactionMs = null
            p.contestReactionMs = null
            send(p.id, Message.Reset(scoreText(p)))
        }
        broadcastScoreboard()
        notifyChanged()
    }

    // ------------------------------------------------------------------ Nachrichten

    fun onMessage(from: PlayerId, message: Message) {
        val player = players[from] ?: return
        when (message) {
            is Message.Pong -> player.sync.addSample(message.masterTime, message.playerTime, clock.now())
            is Message.Hold -> onHold(player, message)
            is Message.Buzz -> onBuzz(player, message)
            is Message.Prefs -> player.receiveEmojis = message.receiveEmojis
            is Message.Emote ->
                if (player.receiveEmojis && message.emoji in settings.emojis) broadcastEmote(player.name, message.emoji)
            is Message.ChatSend -> {
                setTyping(player, false)
                deliverChat(player.name, player.id, message.to, message.text)
            }
            is Message.Typing -> setTyping(player, message.on)
            is Message.QuizAnswer -> onQuizAnswer(player, message)
            is Message.QuizChallenge -> onQuizChallenge(player, message)
            is Message.Ready -> onReady(player, message)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ Emoticons, Nachrichten, Profil

    /** Emoticon des Spielleiters. */
    fun sendEmote(emoji: String) {
        if (emoji in settings.emojis) broadcastEmote(masterName, emoji)
    }

    private fun broadcastEmote(from: String, emoji: String) {
        if (!settings.emojisEnabled) return
        for (p in players.values) {
            if (p.connected && !p.local && p.receiveEmojis) send(p.id, Message.Emote(from, emoji))
        }
        onEmote?.invoke(from, emoji)
    }

    /** Kurznachrichten gehen nur in den Rundenpausen. */
    fun canChat(): Boolean = settings.messagesEnabled && isPause()

    /** Rundenpause: zwischen Buzzer-Runden bzw. bei der Auflösung einer Quizfrage. */
    private fun isPause() = phase == Phase.READY || phase == Phase.ROUND_OVER || phase == Phase.QUIZ_REVEAL

    /** Tipp-Hinweis: verschwindet nach [TYPING_TIMEOUT_MS] ohne neue Meldung von selbst. */
    private fun setTyping(player: Player, on: Boolean) {
        val was = player.typingUntil > clock.now()
        player.typingUntil = if (on) clock.now() + TYPING_TIMEOUT_MS else 0
        if (on) scheduler.schedule(TYPING_TIMEOUT_MS + 10) { notifyChanged() }
        if (was != on) notifyChanged()
    }

    /** Kurznachricht des Spielleiters an einen Spieler oder an alle ([ALL_ADDRESS]). */
    fun sendChat(to: String, text: String) = deliverChat(masterName, null, to, text)

    private fun deliverChat(from: String, senderId: PlayerId?, to: String, text: String) {
        val clean = text.trim().take(MAX_CHAT_LENGTH)
        if (!canChat() || clean.isEmpty()) return
        when (to) {
            ALL_ADDRESS -> {
                for (p in players.values) {
                    if (p.connected && !p.local && p.id != senderId) send(p.id, Message.Chat(from, clean, true))
                }
                if (senderId != null) onChat?.invoke(from, clean, true)
            }
            MASTER_ADDRESS -> if (senderId != null) onChat?.invoke(from, clean, false)
            else -> players[to]?.takeIf { it.connected && !it.local }?.let { send(it.id, Message.Chat(from, clean, false)) }
        }
    }

    /** Schickt das laufende Profil an alle Spieler. */
    fun shareProfile(extra: Map<String, String> = emptyMap()) {
        val values = SettingsCodec.toMap(settings) + extra
        players.values.filter { it.connected && !it.local }
            .forEach { send(it.id, Message.Profile(profileName.ifEmpty { "Profil von $masterName" }, values)) }
        log("Profil an Spieler gesendet")
    }

    /** Verabschiedet sich von allen Spielern, bevor die Gruppe geschlossen wird. */
    fun close() {
        players.values.filter { it.connected && !it.local }.forEach { send(it.id, Message.Bye) }
        stop()
    }

    private fun configMessage() = Message.Config(
        reactionLimitMs = settings.reactionLimitMs ?: 0,
        answerLimitMs = settings.answerLimit ?: 0,
        messagesEnabled = settings.messagesEnabled,
        emojis = if (settings.emojisEnabled) settings.emojis else emptyList(),
        profileName = profileName,
        sounds = settings.sounds,
    )

    // ------------------------------------------------------------------ Klänge und Spielverlauf

    private fun sendSound(to: Collection<PlayerId>, event: SoundEvent) {
        if (event !in settings.sounds) return
        for (id in to) players[id]?.takeIf { it.connected }?.let { send(it.id, Message.Sound(event)) }
    }

    private fun masterSound(event: SoundEvent) {
        if (event in settings.sounds) onSound?.invoke(event)
    }

    private fun recordRound() {
        if (history.isEmpty()) history += players.keys.associateWith { 0L }
        history += players.values.associate { it.id to it.score }
        recordedRound = round
        lastResult = RoundResult(
            round = round,
            winner = pendingWinner?.first,
            amount = pendingWinner?.second ?: 0,
            reactions = players.values.associate { it.id to it.roundReactionMs },
        )
        pendingWinner = null
    }

    /** Punkte für einen richtigen Antworter: 1 Punkt bzw. Restzeit bis zum Limit. */
    private fun award(reactionMs: Long?): Long = when (settings.scoring) {
        ScoringMode.CORRECT_ANSWERS -> 1
        ScoringMode.REMAINING_TIME -> (settings.timeLimitMs - (reactionMs ?: settings.timeLimitMs)).coerceAtLeast(0)
    }

    fun canCorrectLastRound(): Boolean = (phase == Phase.READY || phase == Phase.ROUND_OVER) && lastResult != null

    /**
     * Korrigiert die letzte gewertete Runde: [newWinner] wird Sieger (Punkt bzw. seine Restzeit aus
     * dieser Runde, ohne Buzz 0), `null` storniert die Wertung. Punkte- bzw. Zeitkonten und der
     * Spielverlauf werden angepasst.
     */
    fun correctLastRound(newWinner: PlayerId?): Boolean {
        val result = lastResult ?: return false
        if (!canCorrectLastRound()) return false
        if (newWinner != null && players[newWinner] == null) return false
        if (result.winner == newWinner) return true
        result.winner?.let { players[it] }?.let { it.score = (it.score - result.amount).coerceAtLeast(0) }
        val amount = if (newWinner != null) award(result.reactions[newWinner]) else 0
        newWinner?.let { players[it] }?.let { it.score += amount }
        lastResult = result.copy(winner = newWinner, amount = amount)
        if (history.isNotEmpty()) history[history.lastIndex] = players.values.associate { it.id to it.score }
        log(
            "Korrektur Runde ${result.round}: " +
                (newWinner?.let { "Sieger jetzt ${players[it]?.name} (+${formatScore(amount, settings.scoring)})" } ?: "Wertung storniert"),
        )
        if (phase == Phase.READY) {
            players.values.filter { it.connected }.forEach { send(it.id, Message.Reset(scoreText(it))) }
        } else {
            broadcastStates()
        }
        broadcastScoreboard()
        notifyChanged()
        return true
    }

    /** Spielpause nur zwischen den Runden; die Spieler sehen dann ein Pausen-Overlay. */
    fun setPaused(on: Boolean): Boolean {
        if (on && !isPause()) return false
        if (paused == on) return true
        paused = on
        log(if (on) "Spiel pausiert" else "Pause beendet")
        players.values.filter { it.connected }.forEach { send(it.id, Message.Pause(on)) }
        notifyChanged()
        return true
    }

    /** Spielverlauf geht nur in den Pausen und erst nach mindestens einer Runde. */
    fun canShowReplay(): Boolean = isPause() && history.size >= 2

    /** Zeigt auf allen Bildschirmen eine Animation des Spielverlaufs (höchstens 10 s). */
    fun showReplay(): Boolean {
        if (!canShowReplay()) return false
        val shown = players.values.filter { it.connected }
        val frames = history.map { frame -> shown.map { frame[it.id] ?: 0L } }
        val duration = (3_000L + 1_000L * (frames.size - 1)).coerceAtMost(MAX_REPLAY_MS)
        fun forViewer(viewer: PlayerId?) = Message.Replay(
            labels = shown.map { p ->
                when {
                    p.id == viewer -> "Du"
                    viewer == null || settings.boardShowNames -> p.name
                    else -> "???"
                }
            },
            selfIndex = shown.indexOfFirst { it.id == viewer },
            frames = frames,
            scoring = scoring,
            durationMs = duration,
        )
        for (p in shown) if (!p.local) send(p.id, forViewer(p.id))
        onReplay?.invoke(forViewer(null))
        log("Spielverlauf gezeigt (${frames.size - 1} Runden)")
        return true
    }

    private fun broadcastRoster() {
        val entries = listOf(MASTER_ADDRESS to masterName) +
            players.values.filter { it.connected && !it.local }.map { it.id to it.name }
        for (p in players.values) if (p.connected) send(p.id, Message.Roster(entries.filter { it.first != p.id }))
    }

    private fun onHold(player: Player, m: Message.Hold) {
        val c = contest ?: return
        if (phase != Phase.WAIT_HOLD || !matches(c, m.round, m.attempt) || player.id !in c.participants) return
        if (player.status != Status.WAITING && player.status != Status.HOLDING) return
        player.status = if (m.holding) Status.HOLDING else Status.WAITING
        notifyChanged()
        maybeStartAfterHold(c)
    }

    private fun onBuzz(player: Player, m: Message.Buzz) {
        val c = contest ?: return
        if (!matches(c, m.round, m.attempt) || c.finished || player.id !in c.participants) return
        if (player.status != Status.WAITING && player.status != Status.HOLDING) return
        if (m.falseStart) {
            log("Frühstart: ${player.name}")
            player.status = Status.OUT
            send(player.id, Message.State(Display.GRAY, 0, scoreText(player)))
        } else {
            if (m.reactionMs < 0 || m.reactionMs > (settings.reactionLimitMs ?: Long.MAX_VALUE)) return
            log("Buzz: ${player.name} ${m.reactionMs} ms")
            player.status = Status.BUZZED
            player.contestReactionMs = m.reactionMs
            if (!c.suddenDeath) player.roundReactionMs = m.reactionMs
            c.pending += player.id
            if (c.publishTask == null) c.publishTask = scheduler.schedule(collectWindowMs(c)) { publish(c) }
        }
        notifyChanged()
        checkAllDone(c)
    }

    // ------------------------------------------------------------------ Ablauf

    private fun startContest(ids: Set<PlayerId>, suddenDeath: Boolean) {
        val c = Contest(++attemptCounter, ids, suddenDeath)
        contest = c
        ids.forEach { id -> players[id]?.takeIf { it.connected }?.status = Status.WAITING }
        if (settings.startMode == StartMode.HOLD_RELEASE) {
            phase = Phase.WAIT_HOLD
            ids.forEach { send(it, Message.Arm(round, c.attempt, suddenDeath)) }
            notifyChanged()
        } else {
            scheduleGo(c)
        }
    }

    private fun maybeStartAfterHold(c: Contest) {
        if (phase != Phase.WAIT_HOLD || contest !== c) return
        val active = c.participants.mapNotNull { players[it] }.filter { it.status != Status.OUT }
        if (active.isEmpty()) {
            finishContest(c)
        } else if (active.all { it.status == Status.HOLDING }) {
            scheduleGo(c)
        }
    }

    /** Würfelt die Verzögerung und schickt jedem Spieler den Freigabezeitpunkt in seiner eigenen Uhrzeit. */
    private fun scheduleGo(c: Contest) {
        val (min, max) = if (c.suddenDeath) {
            settings.suddenDeathDelayMinMs to settings.suddenDeathDelayMaxMs
        } else {
            settings.delayMinMs to settings.delayMaxMs
        }
        val delay = if (max > min) random.nextLong(min, max + 1) else min
        val goAt = clock.now() + leadMs(c) + delay
        c.goAtMaster = goAt
        phase = Phase.COUNTDOWN
        for (id in c.participants) {
            val p = players[id] ?: continue
            if (p.status == Status.OUT) continue
            send(
                id,
                Message.GoAt(
                    round, c.attempt, settings.startMode, p.sync.toPlayerTime(goAt),
                    settings.reactionLimitMs ?: 0, c.suddenDeath,
                ),
            )
        }
        c.tasks += scheduler.schedule(goAt - clock.now()) {
            if (contest === c && phase == Phase.COUNTDOWN) {
                phase = Phase.RUNNING
                notifyChanged()
            }
        }
        // Ohne Reaktionszeit-Limit endet der Durchgang erst, wenn alle gedrückt haben (oder der Master abbricht).
        settings.reactionLimitMs?.let { limit ->
            c.tasks += scheduler.schedule(goAt + limit + collectWindowMs(c) - clock.now()) { finishContest(c) }
        }
        notifyChanged()
    }

    private fun checkAllDone(c: Contest) {
        if (contest !== c || c.finished || c.goAtMaster == null) return
        val allDone = c.participants.all { players[it]?.status.let { s -> s == Status.BUZZED || s == Status.OUT || s == null } }
        if (allDone && c.publishTask == null) finishContest(c)
    }

    /** Ende des Sammelfensters: Hauptrunde veröffentlicht die neuen Plätze sofort. */
    private fun publish(c: Contest) {
        c.publishTask = null
        if (contest !== c || c.finished) return
        if (!c.suddenDeath) {
            placePending(c)
            broadcastStates()
        }
        checkAllDone(c)
    }

    private fun placePending(c: Contest) {
        if (c.pending.isEmpty()) return
        val entries = c.pending.map { it to (players[it]?.contestReactionMs ?: Long.MAX_VALUE) }
        val groups = groupByReaction(entries, settings.tieThresholdMs)
        groups.filter { it.size > 1 }.forEach { logTie(it) }
        order.addAll(groups.map { it.toMutableList() })
        c.pending.clear()
    }

    /** Alle haben gebuzzert oder die Zeit ist abgelaufen: einfrieren und auswerten. */
    private fun finishContest(c: Contest) {
        if (c.finished || contest !== c) return
        c.finished = true
        cancelTasks(c)
        contest = null
        for (id in c.participants) {
            val p = players[id] ?: continue
            if (p.status == Status.WAITING || p.status == Status.HOLDING) {
                p.status = Status.OUT
                if (p.connected) log("Keine Reaktion: ${p.name}" + if (c.suddenDeath) " (Sudden Death)" else "")
            }
            if (p.connected) send(id, Message.Freeze(round))
        }
        if (!c.suddenDeath) {
            placePending(c)
            advance(0)
        } else {
            val groupIndex = answerIndex
            val members = order[groupIndex]
            val buzzed = members.filter { players[it]?.status == Status.BUZZED }
                .map { it to (players[it]?.contestReactionMs ?: Long.MAX_VALUE) }
            val resolvedGroups = groupByReaction(buzzed, settings.tieThresholdMs)
            resolvedGroups.filter { it.size > 1 }.forEach { logTie(it) }
            log("Sudden Death: " + buzzed.sortedBy { it.second }.joinToString { "${players[it.first]?.name} ${it.second} ms" })
            val resolved = resolvedGroups.map { it.toMutableList() } +
                members.filter { players[it]?.status != Status.BUZZED }.map { mutableListOf(it) }
            order.removeAt(groupIndex)
            order.addAll(groupIndex, resolved)
            advance(groupIndex)
        }
        broadcastStates()
    }

    /** Nächste antwortberechtigte Gruppe ab [from]; Gleichstand dort → Sudden Death. */
    private fun advance(from: Int) {
        val index = (from until order.size).firstOrNull { gi -> order[gi].any { players[it]?.status != Status.OUT } }
        if (index == null) {
            phase = Phase.ROUND_OVER
            return
        }
        answerIndex = index
        val active = order[index].filter { players[it]?.status != Status.OUT }
        if (active.size == 1) {
            phase = Phase.ANSWERING
            return
        }
        // Gleichstand: kurz pink zeigen, dann Sudden Death mit größerem Zufallsbereich.
        log("Sudden Death um Platz ${order.take(index).sumOf { it.size } + 1}: " + active.joinToString("/") { players[it]?.name ?: it })
        sendSound(players.values.filter { !it.local }.map { it.id }, SoundEvent.SUDDEN_DEATH)
        masterSound(SoundEvent.SUDDEN_DEATH)
        phase = Phase.SUDDEN_DEATH_PREP
        val r = round
        scheduler.schedule(SUDDEN_DEATH_PAUSE_MS) {
            if (phase != Phase.SUDDEN_DEATH_PREP || round != r) return@schedule
            // Während der Pause kann jemand die Verbindung verloren haben.
            val stillTied = order[answerIndex].filter { players[it]?.status != Status.OUT }
            if (stillTied.size > 1) {
                startContest(stillTied.toSet(), suddenDeath = true)
            } else {
                advance(answerIndex)
                broadcastStates()
            }
        }
    }

    // ------------------------------------------------------------------ Quiz

    /** Spielart wählen (nur zwischen den Runden). Ein Wechsel beginnt ein neues Spiel. */
    fun chooseKind(newKind: GameKind): Boolean {
        if (phase != Phase.READY) return false
        if (kind == newKind) return true
        newGame()
        kind = newKind
        quizQuestions = emptyList()
        quizIndex = -1
        turn = null
        quizFinished = false
        log("Spielart: ${newKind.label}")
        players.values.filter { it.connected }.forEach { send(it.id, Message.Mode(newKind)) }
        broadcastScoreboard()
        notifyChanged()
        return true
    }

    /** Startet ein Quiz mit den ausgewählten Fragen; die Punkte beginnen bei 0. */
    fun startQuiz(questions: List<QuizQuestion>): Boolean {
        if (kind != GameKind.QUIZ || phase != Phase.READY || paused || questions.isEmpty()) return false
        if (players.values.none { it.connected }) return false
        newGame()
        quizQuestions = questions
        quizIndex = -1
        quizFinished = false
        turn = null
        log("Quiz gestartet: ${questions.size} Fragen, Wertung ${settings.quizScoring.label}")
        return askNext()
    }

    /** „Nächste Frage“ nach der Auflösung; nach der letzten Frage endet das Quiz. */
    fun nextQuestion(): Boolean {
        if (kind != GameKind.QUIZ || phase != Phase.QUIZ_REVEAL || paused) return false
        turn?.let { t ->
            if (t.challenges.isNotEmpty()) log("Challenge ohne Entscheidung – Wertung bleibt")
            t.challenges.clear()
        }
        if (quizIndex + 1 < quizQuestions.size) return askNext()
        finishQuiz()
        return true
    }

    /** Sofort auflösen, ohne auf die übrigen Antworten zu warten. */
    fun revealNow(): Boolean {
        val t = turn ?: return false
        if (phase != Phase.QUIZ_QUESTION && phase != Phase.QUIZ_ANSWERS) return false
        log("Auflösung vorgezogen")
        reveal(t)
        return true
    }

    /** Frage überspringen: keine Wertung, weiter zur nächsten. */
    fun skipQuestion(): Boolean {
        val t = turn ?: return false
        if (phase != Phase.QUIZ_QUESTION && phase != Phase.QUIZ_ANSWERS) return false
        cancelQuizTasks(t)
        t.revealed = true
        log("Frage ${t.number} übersprungen")
        onQuizSkipped?.invoke(t.question, t.participants.mapNotNull { players[it]?.name })
        if (quizIndex + 1 < quizQuestions.size) askNext() else finishQuiz()
        return true
    }

    /** Quiz vorzeitig beenden. */
    fun stopQuiz(): Boolean {
        if (kind != GameKind.QUIZ || (turn == null && phase == Phase.READY)) return false
        turn?.let { cancelQuizTasks(it) }
        log("Quiz abgebrochen")
        finishQuiz()
        return true
    }

    /** Entscheidung über eine Challenge (oder eine eigene Korrektur) – nur bei der Auflösung. */
    fun decideChallenge(decision: ChallengeDecision, newCorrect: Int? = null): Boolean {
        val t = turn ?: return false
        if (phase != Phase.QUIZ_REVEAL || !t.revealed) return false
        val q = t.question
        val before = t.counted
        when (decision) {
            ChallengeDecision.KEEP -> Unit
            ChallengeDecision.REPLACE -> return false // über replaceQuestion()
            ChallengeDecision.ANNUL, ChallengeDecision.BLOCK -> if (!t.annulled) {
                takeBackAwards(t)
                t.annulled = true
            }
            ChallengeDecision.CORRECT -> {
                if (newCorrect == null || newCorrect !in q.answers.indices) return false
                takeBackAwards(t)
                t.annulled = false
                t.question = q.copy(correctIndex = newCorrect)
                if (settings.quizScoring == QuizScoring.COUNTDOWN) {
                    // Der Countdown beginnt bei der ersten jetzt richtigen Antwort.
                    val first = t.answers.values.filter { it.first == newCorrect }.minOfOrNull { it.second }
                    t.deadlineElapsed = first?.let { (it + settings.quizCountdownMs).coerceAtMost(t.durationMs) } ?: t.durationMs
                }
                t.awards = computeAwards(t)
                t.awards.forEach { (id, amount) -> players[id]?.let { it.score += amount } }
            }
        }
        t.counted = if (t.annulled) null else participantResults(t)
        log("Challenge-Entscheidung Frage ${t.number}: ${decision.label}" + if (newCorrect != null) " (${q.answers[newCorrect]})" else "")
        t.decision = decision
        t.challenges.clear()
        setReviewing(false)
        if (decision != ChallengeDecision.KEEP) {
            if (history.isNotEmpty()) history[history.lastIndex] = players.values.associate { it.id to it.score }
            for (id in t.participants) players[id]?.takeIf { it.connected }?.let { send(id, resultFor(t, it, corrected = true)) }
            broadcastScoreboard()
        }
        onQuizDecision?.invoke(q, decision, newCorrect, before, t.counted)
        notifyChanged()
        return true
    }

    /**
     * Ersatzfrage: Die aufgelöste Frage wird annulliert (Punkte zurück, zählt nicht in der
     * Statistik) und unter derselben Nummer durch [replacement] ersetzt.
     */
    fun replaceQuestion(replacement: QuizQuestion): Boolean {
        val t = turn ?: return false
        if (kind != GameKind.QUIZ || phase != Phase.QUIZ_REVEAL || !t.revealed || paused) return false
        val q = t.question
        val before = t.counted
        takeBackAwards(t)
        t.annulled = true
        t.decision = ChallengeDecision.REPLACE
        t.counted = null
        t.challenges.clear()
        // Die ersetzte Frage verschwindet auch aus dem Spielverlauf.
        if (history.size >= 2) history.removeAt(history.lastIndex)
        log("Challenge-Entscheidung Frage ${t.number}: Ersatzfrage (${replacement.id} statt ${q.id})")
        onQuizDecision?.invoke(q, ChallengeDecision.REPLACE, null, before, null)
        quizQuestions = quizQuestions.toMutableList().also { it[quizIndex] = replacement }
        quizIndex--
        round--
        return askNext()
    }

    /** Fragen dieses Quiz (für die Wahl einer Ersatzfrage, die noch nicht dabei ist). */
    val quizQuestionIds: Set<String> get() = quizQuestions.map { it.id }.toSet()

    private fun participantResults(t: QuizTurn): List<ParticipantResult> = t.participants.mapNotNull { id ->
        val p = players[id] ?: return@mapNotNull null
        val a = t.answers[id]
        ParticipantResult(
            name = p.name,
            correct = a?.let { it.first == t.question.correctIndex },
            timeShare = a?.let { it.second.toDouble() / t.durationMs } ?: 1.0,
        )
    }

    private fun askNext(): Boolean {
        val participants = players.values.filter { it.connected }
        if (participants.isEmpty()) {
            finishQuiz()
            return false
        }
        endReadyCheck()
        setReviewing(false)
        quizIndex++
        round++
        gameStarted = true
        val q = quizQuestions[quizIndex]
        val maxRtt = participants.maxOf { it.sync.recentMaxRttMs ?: UNKNOWN_RTT_MS }
        val questionAt = clock.now() + (maxRtt + 100).coerceIn(200, 1_000)
        val answersAt = questionAt + QUESTION_LEAD_MS
        val t = QuizTurn(quizIndex + 1, q, settings.quizAnswerMs, participants.map { it.id }.toSet(), answersAt)
        turn = t
        for (p in participants) {
            // Jeder bekommt seine eigene Reihenfolge – Abgucken bringt nichts.
            val perm = q.answers.indices.shuffled(random)
            t.perms[p.id] = perm
            send(
                p.id,
                Message.QuizAsk(
                    number = t.number,
                    total = quizQuestions.size,
                    category = q.category,
                    level = q.level,
                    text = q.text,
                    answers = perm.map { q.answers[it] },
                    questionAtLocal = p.sync.toPlayerTime(questionAt),
                    answersAtLocal = p.sync.toPlayerTime(answersAt),
                    durationMs = t.durationMs,
                ),
            )
        }
        phase = Phase.QUIZ_QUESTION
        log("Frage ${t.number}/${quizQuestions.size}: ${q.id}")
        t.tasks += scheduler.schedule(answersAt - clock.now()) {
            if (turn === t && phase == Phase.QUIZ_QUESTION) {
                phase = Phase.QUIZ_ANSWERS
                notifyChanged()
            }
        }
        scheduleReveal(t)
        broadcastScoreboard()
        notifyChanged()
        return true
    }

    /** Auflösung bei Zeitablauf – mit Sammelfenster für unterwegs befindliche Antworten. */
    private fun scheduleReveal(t: QuizTurn) {
        t.revealTask?.cancel()
        val grace = (t.participants.mapNotNull { players[it]?.sync?.recentMaxRttMs ?: UNKNOWN_RTT_MS }.maxOrNull() ?: 0) + 150
        val at = t.answersAt + t.deadlineElapsed + grace.coerceIn(250, 1_000)
        t.revealTask = scheduler.schedule(at - clock.now()) { reveal(t) }
    }

    private fun onQuizAnswer(player: Player, m: Message.QuizAnswer) {
        val t = turn ?: return
        if (m.number != t.number || t.revealed || player.id !in t.participants || player.id in t.answers) return
        if (phase != Phase.QUIZ_QUESTION && phase != Phase.QUIZ_ANSWERS) return
        val perm = t.perms[player.id] ?: return
        if (m.position !in perm.indices || m.elapsedMs < 0 || m.elapsedMs > t.durationMs + 1_000) return
        val chosen = perm[m.position]
        t.answers[player.id] = chosen to m.elapsedMs
        log("Antwort: ${player.name} nach ${m.elapsedMs} ms")
        if (settings.quizScoring == QuizScoring.COUNTDOWN && chosen == t.question.correctIndex) {
            // Nach der ersten richtigen Antwort läuft nur noch der Countdown.
            val cut = m.elapsedMs + settings.quizCountdownMs
            if (cut < t.deadlineElapsed) {
                t.deadlineElapsed = cut
                log("Countdown: Antwortzeit endet nach $cut ms")
                for (id in t.participants) players[id]?.takeIf { it.connected }?.let { send(id, Message.QuizDeadline(t.number, cut)) }
                scheduleReveal(t)
            }
        }
        notifyChanged()
        checkAllAnswered(t)
    }

    private fun checkAllAnswered(t: QuizTurn) {
        if (t.revealed || turn !== t) return
        if (t.participants.all { it in t.answers || players[it]?.connected != true }) reveal(t)
    }

    /** Punkte je Spieler für die aktuelle richtige Antwort. */
    private fun computeAwards(t: QuizTurn): Map<PlayerId, Long> {
        val correct = t.answers.filter { (id, a) -> a.first == t.question.correctIndex && players[id] != null }
            .mapValues { it.value.second }
        if (correct.isEmpty()) return emptyMap()
        val fastest = correct.values.min()
        val winners = correct.filterValues { it - fastest <= settings.tieThresholdMs }
        return when (settings.quizScoring) {
            QuizScoring.FASTEST -> winners.mapValues { 1L }
            QuizScoring.EVERYONE -> correct.mapValues { 1L }
            QuizScoring.COUNTDOWN -> correct.mapValues { (t.deadlineElapsed - it.value).coerceAtLeast(0) }
            QuizScoring.BATTLE -> winners.mapValues { (t.durationMs - it.value).coerceAtLeast(0) }
        }.filterValues { it > 0 }
    }

    private fun takeBackAwards(t: QuizTurn) {
        t.awards.forEach { (id, amount) -> players[id]?.let { it.score = (it.score - amount).coerceAtLeast(0) } }
        t.awards = emptyMap()
    }

    private fun outcomeOf(t: QuizTurn, id: PlayerId): QuizOutcome {
        if (t.annulled) return QuizOutcome.ANNULLED
        val a = t.answers[id] ?: return QuizOutcome.NONE
        return if (a.first == t.question.correctIndex) QuizOutcome.RIGHT else QuizOutcome.WRONG
    }

    private fun resultFor(t: QuizTurn, p: Player, corrected: Boolean): Message.QuizResult {
        val perm = t.perms[p.id] ?: emptyList()
        val gained = t.awards[p.id] ?: 0
        return Message.QuizResult(
            number = t.number,
            correctPosition = perm.indexOf(t.question.correctIndex),
            chosenPosition = t.answers[p.id]?.first?.let { perm.indexOf(it) } ?: -1,
            outcome = outcomeOf(t, p.id),
            gained = if (gained > 0) "+" + formatScore(gained, scoring) else "",
            score = scoreText(p),
            explanation = t.question.explanation,
            source = t.question.source,
            corrected = corrected,
        )
    }

    private fun reveal(t: QuizTurn) {
        if (t.revealed || turn !== t) return
        t.revealed = true
        cancelQuizTasks(t)
        t.awards = computeAwards(t)
        t.awards.forEach { (id, amount) -> players[id]?.let { it.score += amount } }
        phase = Phase.QUIZ_REVEAL
        if (history.isEmpty()) history += players.keys.associateWith { 0L }
        history += players.values.associate { it.id to it.score }
        recordedRound = round
        val q = t.question
        log(
            "Auflösung Frage ${t.number}: " + t.participants.mapNotNull { id ->
                val p = players[id] ?: return@mapNotNull null
                "${p.name} ${outcomeOf(t, id).name.lowercase()}" + (t.awards[id]?.let { " +${formatScore(it, scoring)}" } ?: "")
            }.joinToString(),
        )
        for (id in t.participants) {
            val p = players[id]?.takeIf { it.connected } ?: continue
            send(id, resultFor(t, p, corrected = false))
            sendSound(listOf(id), if (outcomeOf(t, id) == QuizOutcome.RIGHT) SoundEvent.TUSCH else SoundEvent.WRONG)
        }
        t.counted = participantResults(t)
        onQuizRevealed?.invoke(q, t.counted.orEmpty())
        broadcastScoreboard()
        notifyChanged()
    }

    private fun finishQuiz() {
        turn?.let { cancelQuizTasks(it) }
        setReviewing(false)
        quizFinished = true
        phase = Phase.READY
        log("Quiz beendet")
        for (p in players.values) if (p.connected) send(p.id, Message.Reset(scoreText(p)))
        sendSound(players.values.filter { !it.local }.map { it.id }, SoundEvent.ROUND_END)
        masterSound(SoundEvent.ROUND_END)
        broadcastScoreboard()
        notifyChanged()
    }

    private fun onQuizChallenge(player: Player, m: Message.QuizChallenge) {
        val t = turn ?: return
        if (phase != Phase.QUIZ_REVEAL || m.number != t.number || t.challenges.any { it.playerId == player.id }) return
        val reason = m.reason.trim().take(MAX_CHAT_LENGTH)
        t.challenges += QuizChallengeInfo(player.id, player.name, reason)
        log("Challenge von ${player.name}" + if (reason.isNotEmpty()) ": $reason" else "")
        masterSound(SoundEvent.CHAT)
        // Eine Challenge startet die Prüfung: alle sehen das Laufband bis zur Entscheidung.
        setReviewing(true)
        onQuizChallenge?.invoke(t.question, player.name, reason)
        notifyChanged()
    }

    private fun cancelQuizTasks(t: QuizTurn) {
        t.tasks.forEach { it.cancel() }
        t.tasks.clear()
        t.revealTask?.cancel()
        t.revealTask = null
    }

    private fun quizSnapshot(): QuizSnapshot? {
        if (kind != GameKind.QUIZ) return null
        val t = turn ?: return null
        return QuizSnapshot(
            number = t.number,
            total = quizQuestions.size,
            question = t.question,
            answersAt = t.answersAt,
            deadline = t.answersAt + t.deadlineElapsed,
            durationMs = t.durationMs,
            revealed = t.revealed,
            annulled = t.annulled,
            rows = t.participants.mapNotNull { id ->
                val p = players[id] ?: return@mapNotNull null
                val a = t.answers[id]
                QuizRow(
                    id = id,
                    name = p.name,
                    answered = a != null,
                    elapsedMs = a?.second,
                    chosen = if (t.revealed) a?.first else null,
                    outcome = if (t.revealed) outcomeOf(t, id) else null,
                    gained = t.awards[id] ?: 0,
                )
            },
            challenges = t.challenges.toList(),
            decision = t.decision,
            finished = quizFinished,
            hasNext = quizIndex + 1 < quizQuestions.size,
        )
    }

    // ------------------------------------------------------------------ Bereit? und Prüfung

    /** „Get Ready“: Spieler bekommen die Abfrage „Bereit?“. Nur in den Pausen. */
    fun askReady(): Boolean {
        // Läuft schon eine Abfrage, startet erneutes Tippen keine neue (Spieler müssten sonst nochmal bestätigen).
        if (!isPause() || paused || readyOpen) return false
        readyId++
        readyOpen = true
        readyPlayers.clear()
        // Der mitspielende Spielleiter ist selbst bereit.
        players.values.filter { it.local }.forEach { readyPlayers += it.id }
        log("Bereit-Abfrage gestartet")
        players.values.filter { it.connected && !it.local }.forEach { send(it.id, Message.ReadyCheck(readyId, true)) }
        checkAllReady()
        notifyChanged()
        return true
    }

    fun cancelReady() {
        if (!readyOpen) return
        endReadyCheck()
        notifyChanged()
    }

    private fun onReady(player: Player, m: Message.Ready) {
        if (!readyOpen || m.id != readyId || !readyPlayers.add(player.id)) return
        log("Bereit: ${player.name}")
        checkAllReady()
        notifyChanged()
    }

    private fun checkAllReady() {
        val state = readyState() ?: return
        if (state.allReady) {
            log("Alle bereit – Go!")
            masterSound(SoundEvent.GO)
            onAllReady?.invoke(readyId)
        }
    }

    private fun endReadyCheck() {
        if (!readyOpen) return
        readyOpen = false
        players.values.filter { it.connected && !it.local }.forEach { send(it.id, Message.ReadyCheck(readyId, false)) }
    }

    private fun readyState(): ReadyState? {
        if (!readyOpen) return null
        val active = players.values.filter { it.connected }
        return ReadyState(
            readyNames = active.filter { it.id in readyPlayers }.map { it.name },
            waitingNames = active.filter { it.id !in readyPlayers }.map { it.name },
        )
    }

    /** Spielleiter prüft die Auflösung: Spieler sehen ein Laufband, bis er entschieden hat. */
    fun startReview(): Boolean {
        if (phase != Phase.QUIZ_REVEAL || reviewing) return false
        // Nach einer Entscheidung nur noch bei einer neuen Challenge – sonst bliebe das Laufband stehen.
        val t = turn ?: return false
        if (t.decision != null && t.challenges.isEmpty()) return false
        setReviewing(true)
        notifyChanged()
        return true
    }

    /** Prüfung ohne Änderung beenden (entspricht „Wertung bleibt“). */
    fun endReview() {
        if (!reviewing) return
        val t = turn
        if (t != null && phase == Phase.QUIZ_REVEAL) decideChallenge(ChallengeDecision.KEEP) else setReviewing(false)
        notifyChanged()
    }

    private fun setReviewing(on: Boolean) {
        if (reviewing == on) return
        reviewing = on
        log(if (on) "Prüfung der Auflösung gestartet" else "Prüfung beendet")
        players.values.filter { it.connected && !it.local }.forEach { send(it.id, Message.Review(on)) }
        players.values.filter { it.local }.forEach { send(it.id, Message.Review(on)) }
    }

    // ------------------------------------------------------------------ Anzeige

    private fun displayOf(p: Player): Pair<Display, Int> {
        if (phase == Phase.READY || p.status == Status.IDLE) return Display.BLACK to 0
        if (p.status == Status.OUT) return Display.GRAY to 0
        val c = contest
        val groupIndex = order.indexOfFirst { p.id in it }
        val live = c != null && p.id in c.participants && (c.suddenDeath || groupIndex < 0)
        if (live || groupIndex < 0) {
            return when (p.status) {
                Status.HOLDING -> Display.BLUE
                Status.BUZZED -> Display.PRESSED
                else -> when {
                    phase == Phase.RUNNING -> Display.YELLOW
                    c?.suddenDeath == true -> Display.PINK
                    else -> Display.BLACK
                }
            } to 0
        }
        val place = order.take(groupIndex).sumOf { it.size } + 1
        val leadIndex = if (answerIndex >= 0) answerIndex else order.indexOfFirst { g -> g.any { players[it]?.status != Status.OUT } }
        if (groupIndex == leadIndex) {
            val tied = order[groupIndex].count { players[it]?.status != Status.OUT } > 1
            return (if (tied) Display.PINK else Display.GREEN) to place
        }
        return Display.RED to place
    }

    /** Schickt allen, die gerade nicht selbst im Durchgang stehen, ihre verbindliche Anzeige. */
    private fun broadcastStates(roundEndExcept: PlayerId? = null) {
        if (phase == Phase.ROUND_OVER && recordedRound != round) {
            recordRound()
            // Wer gerade „Richtig“ hört, bekommt keinen zweiten Klang darüber.
            sendSound(players.values.filter { !it.local && it.id != roundEndExcept }.map { it.id }, SoundEvent.ROUND_END)
            if (roundEndExcept?.let { players[it] }?.local != true) masterSound(SoundEvent.ROUND_END)
        }
        val c = contest
        for (p in players.values) {
            if (!p.connected || p.status == Status.IDLE) continue
            if (c != null && p.id in c.participants && p.status != Status.OUT && (c.suddenDeath || order.none { p.id in it })) continue
            val (display, place) = displayOf(p)
            send(p.id, Message.State(display, place, scoreText(p)))
        }
        if (phase == Phase.ANSWERING) {
            val id = answeringPlayer()
            if (id != null && id != answerTimerPlayer) {
                cancelAnswerTimer()
                log("Antwort von ${players[id]?.name}")
                startAnswerTimer(id)
            }
        }
        // Der Antwort-Timer wird nach jeder Anzeige erneut geschickt, weil der Spieler ihn bei State zurücksetzt.
        val timerPlayer = answerTimerPlayer
        val deadline = answerDeadline
        if (timerPlayer != null && deadline != null) {
            send(timerPlayer, Message.AnswerTimer((deadline - clock.now()).coerceAtLeast(1)))
        }
        if (phase == Phase.ROUND_OVER) broadcastScoreboard()
        notifyChanged()
    }

    /** Rangliste an alle; angezeigt wird sie nur zwischen den Runden. */
    private fun broadcastScoreboard() {
        val show = isPause()
        val inputs = players.values.filter { it.connected }.map { ScoreInput(it.id, it.name, it.score) }
        val maxScore = inputs.maxOfOrNull { it.score } ?: 0
        val boardSettings = settings.copy(scoring = scoring)
        for (p in players.values) {
            if (p.connected) send(p.id, Message.Scoreboard(buildScoreboard(inputs, p.id, boardSettings), maxScore, show))
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        phase = phase,
        round = round,
        settings = settings,
        scoringLocked = gameStarted,
        timeLimitLocked = gameStarted && settings.scoring == ScoringMode.REMAINING_TIME,
        players = players.values.map { p ->
            val (display, place) = displayOf(p)
            PlayerRow(p.id, p.name, display, place, p.roundReactionMs, scoreText(p), p.score, p.sync.recentMaxRttMs, p.connected)
        },
        answerDeadline = answerDeadline,
        profileName = profileName,
        canShowReplay = canShowReplay(),
        paused = paused,
        canCorrectLastRound = canCorrectLastRound(),
        lastRound = lastResult?.round,
        lastRoundWinner = lastResult?.winner,
        kind = kind,
        quiz = quizSnapshot(),
        scoring = scoring,
        someoneTyping = players.values.any { it.connected && it.typingUntil > clock.now() },
        ready = readyState(),
        reviewing = reviewing,
    )

    private fun notifyChanged() {
        onChanged?.invoke(snapshot())
    }

    // ------------------------------------------------------------------ Hilfen

    private fun answeringPlayer(): PlayerId? =
        order.getOrNull(answerIndex)?.firstOrNull { players[it]?.status != Status.OUT }

    private fun logTie(group: List<PlayerId>) {
        val times = group.mapNotNull { players[it]?.contestReactionMs }
        log("Gleichstand ${group.joinToString("/") { players[it]?.name ?: it }} (${times.max() - times.min()} ms)")
    }

    private fun matches(c: Contest, round: Int, attempt: Int) = round == this.round && attempt == c.attempt

    private fun maxRtt(c: Contest): Long =
        c.participants.mapNotNull { players[it]?.sync?.recentMaxRttMs ?: UNKNOWN_RTT_MS }.maxOrNull() ?: 0

    /** Vorlauf der Freigabe, damit die Nachricht überall vor dem Zeitpunkt T ankommt. */
    private fun leadMs(c: Contest) = (maxRtt(c) + 100).coerceIn(200, 1_000)

    /** Sammelfenster nach einem Buzz, bevor Ergebnisse angezeigt werden. */
    private fun collectWindowMs(c: Contest) = (maxRtt(c) + 150).coerceIn(250, 1_000)

    private fun cancelTasks(c: Contest) {
        c.tasks.forEach { it.cancel() }
        c.tasks.clear()
        c.publishTask?.cancel()
        c.publishTask = null
    }

    private fun scoreText(p: Player) = formatScore(p.score, scoring)

    companion object {
        /** Quiz: so lange steht die Frage allein, bevor Antworten und Timer erscheinen. */
        const val QUESTION_LEAD_MS = 3_000L
        const val TYPING_TIMEOUT_MS = 8_000L
        const val SUDDEN_DEATH_PAUSE_MS = 1_500L
        const val MAX_REPLAY_MS = 10_000L
        private const val UNKNOWN_RTT_MS = 300L
    }
}
