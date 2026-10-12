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
    )

    private enum class Status { IDLE, WAITING, HOLDING, BUZZED, OUT }

    private class Player(val id: PlayerId, val name: String, val local: Boolean) {
        val sync = ClockSync()
        var connected = true
        var receiveEmojis = true
        var score = 0L
        var status = Status.IDLE
        var roundReactionMs: Long? = null
        var contestReactionMs: Long? = null
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

    // ------------------------------------------------------------------ Spieler

    fun addPlayer(id: PlayerId, name: String, local: Boolean = false) {
        val player = Player(id, name, local)
        if (local) player.sync.setLocal()
        players[id] = player
        log("Spieler beigetreten: $name ($id)")
        send(id, configMessage())
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

    fun canAcceptJoin(): Boolean = phase == Phase.READY

    // ------------------------------------------------------------------ Einstellungen

    /** Nur zwischen den Runden. Wertung und (bei Zeitwertung) Zeitlimit sind nach Spielbeginn gesperrt. */
    fun updateSettings(newSettings: Settings, profileName: String = this.profileName): Boolean {
        if (phase != Phase.READY) return false
        this.profileName = profileName
        var s = newSettings.normalized()
        if (gameStarted) {
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
        pingTask?.cancel()
        pingTask = null
        contest?.let { cancelTasks(it) }
    }

    // ------------------------------------------------------------------ Bedienung durch den Master

    /** „Buzzer frei“ (Modus B) bzw. „Buzzer scharf“ (Modus A). */
    fun release(): Boolean {
        if (phase != Phase.READY) return false
        val participants = players.values.filter { it.connected }
        if (participants.isEmpty()) return false
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
        player.score += when (settings.scoring) {
            ScoringMode.CORRECT_ANSWERS -> 1
            ScoringMode.REMAINING_TIME -> (settings.timeLimitMs - (player.roundReactionMs ?: settings.timeLimitMs)).coerceAtLeast(0)
        }
        phase = Phase.ROUND_OVER
        broadcastStates()
        return true
    }

    fun markWrong(): Boolean {
        if (phase != Phase.ANSWERING) return false
        val player = players[answeringPlayer() ?: return false] ?: return false
        cancelAnswerTimer()
        log("Falsch: ${player.name}")
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
            is Message.ChatSend -> deliverChat(player.name, player.id, message.to, message.text)
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
    fun canChat(): Boolean = settings.messagesEnabled && (phase == Phase.READY || phase == Phase.ROUND_OVER)

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
    fun shareProfile() {
        val values = SettingsCodec.toMap(settings)
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
    )

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
    private fun broadcastStates() {
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
        val show = phase == Phase.READY || phase == Phase.ROUND_OVER
        val inputs = players.values.filter { it.connected }.map { ScoreInput(it.id, it.name, it.score) }
        val maxScore = inputs.maxOfOrNull { it.score } ?: 0
        for (p in players.values) {
            if (p.connected) send(p.id, Message.Scoreboard(buildScoreboard(inputs, p.id, settings), maxScore, show))
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

    private fun scoreText(p: Player) = formatScore(p.score, settings.scoring)

    companion object {
        const val SUDDEN_DEATH_PAUSE_MS = 1_500L
        private const val UNKNOWN_RTT_MS = 300L
    }
}
