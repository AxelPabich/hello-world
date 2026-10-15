package de.pabich.buzzer

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import android.content.Context
import de.pabich.buzzer.core.ALL_ADDRESS
import de.pabich.buzzer.core.Cancellable
import de.pabich.buzzer.core.ChallengeDecision
import de.pabich.buzzer.core.ConfigFormat
import de.pabich.buzzer.core.DiagnoseTransfer
import de.pabich.buzzer.core.GameKind
import de.pabich.buzzer.core.Jingle
import de.pabich.buzzer.core.PeerInfo
import de.pabich.buzzer.core.QuestionPicker
import de.pabich.buzzer.core.QuizScoring
import de.pabich.buzzer.core.Question
import de.pabich.buzzer.core.ParticipantResult
import de.pabich.buzzer.core.MasterEngine
import de.pabich.buzzer.core.Message
import de.pabich.buzzer.core.PlayerEngine
import de.pabich.buzzer.core.Display
import de.pabich.buzzer.core.Settings
import de.pabich.buzzer.core.SettingsCodec
import de.pabich.buzzer.core.SoundEvent
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class Screen { START, DISCOVER, MASTER, PLAYER, TAKEOVER, UPDATE, SETTINGS, HELP, ABOUT }

/** Gefundene Gruppe; [peer] enthält Name und Version des Spielleiters. */
data class Group(val endpointId: String, val peer: PeerInfo) {
    val name: String get() = peer.name
}

data class JoinRequest(val endpointId: String, val peer: PeerInfo, val authCode: String) {
    val name: String get() = peer.name
}

/** Laufende Dateiübertragung einer App-Version. */
data class Transfer(val peerName: String, val incoming: Boolean, val build: Int, val progress: Float, val done: Boolean = false, val failed: Boolean = false)

/** Empfangene oder geladene neuere Version, bereit zur Installation. */
data class ReceivedUpdate(val file: File, val build: Int, val from: String)

/** Ein auffliegendes Emoticon. */
data class FlyingEmote(val id: Long, val from: String, val emoji: String)

data class ChatLine(val from: String, val text: String, val toAll: Boolean, val outgoing: Boolean)

/**
 * Spieler nach dem Wegfall des Spielleiters (Punkt 59): [expected] = bestimmter Nachfolger,
 * [taker] = wer übernommen hat und gerade verbunden wird, [deadline] = Spielende ohne Nachfolger.
 */
data class Takeover(
    val succession: Message.Succession,
    val deadline: Long,
    val expected: String?,
    val taker: String? = null,
    val reason: String,
)

/** Ein empfangenes Profil, das der Spieler ansehen und speichern kann. */
data class ReceivedProfile(val name: String, val values: Map<String, String>)

/** Ein geladenes Profil aus einem älteren Build: diese Felder sind neu und sollen geprüft werden. */
data class ProfileUpgrade(val name: String, val missingLabels: List<String>)

class BuzzerViewModel(app: Application) : AndroidViewModel(app), NearbyLink.Listener {
    private val store = ConfigStore(app)
    val quizStore = QuizStore(app)
    val diagnoseUpload = DiagnoseUpload(app)

    /** Token für die Diagnose-Ablage gespeichert (für die Anzeige). */
    var tokenSaved by mutableStateOf(diagnoseUpload.hasToken)
        private set

    var screen by mutableStateOf(Screen.START)
        private set
    var myName by mutableStateOf(store.config.playerName)
    private var statusState by mutableStateOf<String?>(null)

    /** Sichtbare Fehler- oder Hinweismeldung; jede Meldung landet auch im Diagnose-Protokoll. */
    var status: String?
        get() = statusState
        set(value) {
            if (value != null) Diagnostics.log("Meldung: $value")
            statusState = value
        }
    var connectingTo by mutableStateOf<String?>(null)
        private set

    val groups = mutableStateListOf<Group>()
    val joinRequests = mutableStateListOf<JoinRequest>()

    var masterSnapshot by mutableStateOf<MasterEngine.Snapshot?>(null)
        private set

    /** Anzeige des Spielers – auf dem Master-Gerät die des mitspielenden Masters. */
    var playerView by mutableStateOf<PlayerEngine.View?>(null)
        private set

    var receiveEmojis by mutableStateOf(store.config.receiveEmojis)
        private set
    var soundOn by mutableStateOf(store.config.soundOn)
        private set
    var designId by mutableStateOf(store.config.design)
        private set

    /** Laufender Spielverlauf (Animation), sonst `null`. */
    var replay by mutableStateOf<Message.Replay?>(null)
        private set

    /** Zählt hoch, wenn auf diesem Gerät „Richtig“ gefeiert wird (Konfetti). */
    var confettiSeq by mutableIntStateOf(0)
        private set

    /** Splash-Screen beim App-Start (zufällige Variante); `null` = vorbei. */
    var splash by mutableStateOf<Int?>(kotlin.random.Random.nextInt(de.pabich.buzzer.ui.SPLASH_VARIANTS))
        private set

    /** Laufende Spielstart-Animation (Rennszene); `null` = keine. */
    var intro by mutableStateOf<Int?>(null)
        private set
    var introSeq by mutableIntStateOf(0)
        private set

    /** Spieler: letzter Stand des Spielleiters – damit kann ein Spieler das Spiel weiterführen. */
    private var succession: Message.Succession? = null

    /** Spieler: Der Spielleiter ist weg, die Übernahme läuft. */
    var takeover by mutableStateOf<Takeover?>(null)
        private set
    private var takeoverTask: Cancellable? = null

    /** Neuer Spielleiter: Stand des Vorgängers (für Rangfolge und automatische Aufnahme), solange die Übernahme läuft. */
    private var takenOver: Message.Succession? = null
    private var takenOverUntil = 0L

    /** Spielleiter: Zeitpunkte der letzten Verbindungsabbrüche (alle auf einmal weg = eigene Funkstörung). */
    private val recentLosses = mutableListOf<Long>()

    /** Quiz startet erst nach der Spielstart-Animation – solange keine zweite Anfrage. */
    private var quizStartPending: Cancellable? = null

    fun splashDone(skipped: Boolean = false) {
        if (splash != null && skipped) Diagnostics.log("Splash übersprungen")
        splash = null
    }

    private fun showIntro(variant: Int) {
        Diagnostics.log("Spielstart-Animation: ${RACE_NAMES[variant % RACE_NAMES.size]}")
        intro = variant
        introSeq++
        if (soundOn) sounds.play(Jingle.entries[Jingle.RACE_FABLE.ordinal + variant % de.pabich.buzzer.ui.RACE_VARIANTS])
    }

    fun introDone() {
        intro = null
    }
    val emotes = mutableStateListOf<FlyingEmote>()
    val chat = mutableStateListOf<ChatLine>()
    var receivedProfile by mutableStateOf<ReceivedProfile?>(null)
    var profiles by mutableStateOf(store.config.profiles)
        private set
    var profileUpgrade by mutableStateOf<ProfileUpgrade?>(null)
        private set
    var importContent by mutableStateOf<ConfigFormat.Content?>(null)

    /** Master: Auswahl der Spielart ist offen (beim Start und über „Spielart wechseln“). */
    var choosingKind by mutableStateOf(true)
        private set

    /** Master: Fragenpakete wurden neu geladen (für die Anzeige der Zahlen). */
    var quizDataVersion by mutableIntStateOf(0)
        private set

    // ------------------------------------------------------------------ Versionen
    /** Spieler: Name und Version des Spielleiters, mit dem er verbunden ist. */
    var masterPeer by mutableStateOf<PeerInfo?>(null)
        private set

    /** Master: Build-Nummern der verbundenen Spieler (`null` = Build 11 oder älter). */
    val peerBuilds = mutableStateMapOf<String, Int?>()

    var transfer by mutableStateOf<Transfer?>(null)
        private set
    var receivedUpdate by mutableStateOf<ReceivedUpdate?>(null)

    /** Neueste Build-Nummer auf der Release-Seite, sobald abgefragt. */
    var repoBuild by mutableStateOf<Int?>(null)
        private set
    var downloadProgress by mutableStateOf<Float?>(null)
        private set

    /** Spieler: Verbindung nur zum Aktualisieren (Screen UPDATE). */
    var updatePeerName by mutableStateOf<String?>(null)
        private set
    private var updatePeer: PeerInfo? = null

    /** Master: Verbindungen nur zum Aktualisieren. */
    private val updateEndpoints = mutableMapOf<String, PeerInfo>()

    /** Angekündigte Dateien: Payload-ID → (Absender, Build). */
    private val expectedApks = mutableMapOf<Long, Pair<String, Int>>()
    private val receivedFiles = mutableMapOf<Long, Pair<Uri?, File?>>()
    private var lastTypingSent = 0L

    // ------------------------------------------------------------------ Sicherheit und Diagnose
    /** Spieler: Prüfcode der Beitrittsanfrage – muss beim Spielleiter gleich sein. */
    var joinCode by mutableStateOf<String?>(null)
        private set

    /** Die Suche braucht den eingeschalteten Standort: Hinweis anzeigen. */
    var locationOff by mutableStateOf(false)

    /**
     * Auf diesem Gerät gespeicherte Diagnoseberichte, die noch nicht in GitHub liegen: eigene
     * und (beim Spielleiter) von Spielern empfangene.
     */
    val pendingReports = mutableStateListOf<DiagnoseTransfer.Report>()

    /** Spieler: Der Spielleiter hat einen GitHub-Zugang und übernimmt gespeicherte Diagnosen. */
    var masterCollects by mutableStateOf(false)
        private set

    /** Spieler: Rückfrage „gespeicherte Diagnosen an den Spielleiter senden?“ anzeigen. */
    var diagnoseOffer by mutableStateOf(false)
        private set

    /** Spieler: Der Spielleiter bittet um einen Diagnosebericht – Rückfrage anzeigen. */
    var diagnoseRequest by mutableStateOf(false)
        private set
    private val diagPrefs = app.getSharedPreferences("diagnose-request", android.content.Context.MODE_PRIVATE)

    /** Spieler hat „Nein“ gesagt – bis zum Neustart der App nicht mehr fragen. */
    private var offerDeclined = false

    /** Spielleiter: gespeicherte Berichte von Spielern → Endpunkt, der nach dem Hochladen die Bestätigung bekommt. */
    private val confirmAfterUpload = mutableMapOf<String, String>()
    var uploading by mutableStateOf(false)
        private set

    /** Master: nach „Quiz“ zuerst eine Quiz-Vorlage wählen. */
    var choosingTemplate by mutableStateOf(false)

    /** Anleitung zum Token beim ersten Hochladen. */
    var showTokenGuide by mutableStateOf(false)
    private val reportCollector = DiagnoseTransfer.Collector()

    /** Noch nicht hochgeladene Berichte überstehen das Verlassen und Neustarts. */
    private val pendingDir = File(app.filesDir, "diagnose-pending").apply { mkdirs() }

    /** Buzzer-Profile und Quiz-Vorlagen liegen in einer Liste, unterschieden per Kennzeichen. */
    val buzzerProfiles get() = profiles.filter { it.values[ConfigStore.KIND_KEY] != ConfigStore.KIND_QUIZ }
    val quizTemplates get() = profiles.filter { it.values[ConfigStore.KIND_KEY] == ConfigStore.KIND_QUIZ }

    private val link = NearbyLink(app, this)
    private val sounds = SoundPlayer(app)
    private var tickDeadline: Long? = null
    private var tickTask: Cancellable? = null
    private var chaseStream = 0
    private val scheduler = MainThreadScheduler()
    private var master: MasterEngine? = null
    private var player: PlayerEngine? = null
    private var masterEndpoint: String? = null
    private var pendingStop: Cancellable? = null
    private var emoteSeq = 0L

    init {
        // Klang zum Splash-Screen (SoundPlayer wartet kurz, bis er geladen ist).
        splash?.let {
            Diagnostics.log("Splash: ${SPLASH_NAMES[it % SPLASH_NAMES.size]}" + if (soundOn) "" else " (ohne Ton)")
            if (soundOn) sounds.play(Jingle.entries[Jingle.SPLASH_HOP.ordinal + it])
        }
        // Zwischengespeicherte Diagnoseberichte wieder aufnehmen.
        pendingDir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }.forEach { f ->
            runCatching {
                val text = f.readText()
                val header = text.substringBefore('\n')
                val (from, build) = header.split('|', limit = 2)
                pendingReports += DiagnoseTransfer.Report(f.nameWithoutExtension, from, build.toInt(), text.substringAfter('\n'))
            }.onFailure { f.delete() }
        }
        if (Diagnostics.previousCrash != null) {
            status = "Die App wurde beim letzten Mal unerwartet beendet. Bitte „Diagnose senden“ antippen."
        }
    }

    val isMaster: Boolean get() = master != null

    /**
     * Spielleiter zwischen den Runden (bzw. bei der Auflösung): Erst dann erscheinen Beitritts- und
     * Update-Anfragen, damit sie das laufende Spiel nicht stören.
     */
    val masterIdle: Boolean
        get() = masterSnapshot?.phase.let { it == null || it == MasterEngine.Phase.READY || it == MasterEngine.Phase.ROUND_OVER || it == MasterEngine.Phase.QUIZ_REVEAL }

    // ------------------------------------------------------------------ Start

    fun openGroup() = openGroup(null)

    /** Gruppe eröffnen; mit [taken] als Nachfolger eines Spielleiters (Punkt 59). */
    private fun openGroup(taken: Message.Succession?) {
        finishPendingStop()
        store.setPlayerName(myName.trim())
        choosingKind = taken?.kind == null
        val engine = MasterEngine(UptimeClock, scheduler, log = Diagnostics::log) { to, message ->
            if (to == LOCAL_PLAYER_ID) {
                // Gleicher Weg wie über Funk, nur ohne Laufzeit.
                scheduler.schedule(0) { player?.onMessage(message) }
            } else {
                link.send(to, message)
            }
        }
        engine.masterName = myName.trim()
        engine.onChanged = { snap ->
            masterSnapshot = snap
            // Tickende Uhr auf dem Master-Gerät, solange jemand antwortet.
            val deadline = snap.answerDeadline.takeIf { snap.phase == MasterEngine.Phase.ANSWERING }
            updateTicking(deadline.takeIf { SoundEvent.TICK in snap.settings.sounds })
        }
        // Quiz: Nach „Alle bereit – Go!“ startet die nächste Frage kurz nach dem Go-Klang von selbst.
        engine.onAllReady = { id ->
            scheduler.schedule(AUTO_START_MS) {
                val snap = engine.snapshot()
                val stillReady = master === engine && engine.readyCheckId == id && snap.ready?.allReady == true && !snap.paused
                if (stillReady && snap.kind == GameKind.QUIZ && !choosingKind && !choosingTemplate) {
                    when (snap.phase) {
                        MasterEngine.Phase.QUIZ_REVEAL -> if (snap.quiz?.hasNext == true) nextQuestion()
                        MasterEngine.Phase.READY -> startQuiz()
                        else -> Unit
                    }
                }
            }
        }
        engine.onEmote = { from, emoji -> showEmote(from, emoji) }
        engine.onChat = { from, text, toAll -> addChat(ChatLine(from, text, toAll, outgoing = false)) }
        engine.onSound = { sound(it) }
        engine.onReplay = { startReplay(it) }
        engine.onIntro = { showIntro(it) }
        // Im Testspiel landet alles in der getrennten Test-Statistik (ohne Einfluss auf die Auswahl).
        fun List<ParticipantResult>.named() = map { it.copy(name = statsName(it.name)) }
        engine.onQuizRevealed = { q, results -> quizStore.recordResult(q.id, results.named(), test = testGame) }
        engine.onQuizSkipped = { q, names -> quizStore.recordSeen(q.id, names.map { statsName(it) }, test = testGame) }
        engine.onQuizChallenge = { q, from, reason -> quizStore.recordChallenge(q.id, statsName(from), reason, test = testGame) }
        engine.onQuizDecision = { q, decision, newCorrect, before, after ->
            quizStore.recordDecision(q.id, decision, newCorrect, before?.named(), after?.named(), test = testGame)
        }
        master = engine
        if (taken != null) {
            // Fragen des laufenden Quiz aus den eigenen Paketen; fehlende fallen weg.
            val byId = quizStore.questions.associateBy { it.id }
            val found = taken.quiz.map { byId[it] }
            val next = found.take(taken.quizNext.coerceAtLeast(0)).count { it != null }
            if (found.any { it == null }) Diagnostics.log("Übernahme: ${found.count { it == null }} Fragen fehlen in den eigenen Paketen")
            engine.takeOver(taken, found.filterNotNull().map(quizStore::toQuiz), next)
            testGame = taken.test
            choosingTemplate = false
            takenOver = taken
            takenOverUntil = UptimeClock.now() + TAKEN_OVER_ACCEPT_MS
        } else {
            takenOver = null
            engine.testGame = testGame
            engine.updateSettings(store.config.lastSettings, store.config.lastProfileName)
        }
        syncLocalPlayer(engine)
        masterSnapshot = engine.snapshot()
        engine.start()
        Diagnostics.log(if (taken == null) "Gruppe eröffnet" else "Gruppe als Nachfolger von ${taken.master} eröffnet (Spiel ${taken.game})")
        link.startAdvertising(PeerInfo.format(myName.trim(), AppUpdate.ownBuild))
        // Nachfolger suchen kurz mit: Hat ein Vorrangiger zugleich übernommen, tritt man ihm bei.
        if (taken != null) {
            link.startDiscovery()
            scheduler.schedule(TAKEOVER_MS) { if (master === engine) link.stopDiscovery() }
        }
        GameService.start(getApplication(), "Du leitest eine Buzzer-Runde")
        screen = Screen.MASTER
    }

    /** Standort eingeschaltet? Manche Geräte verweigern die Suche sonst (Fehler 8034). */
    fun locationEnabled(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return true
        val lm = getApplication<Application>().getSystemService(android.location.LocationManager::class.java)
        return lm?.isLocationEnabled ?: true
    }

    fun searchGroups(ignoreLocation: Boolean = false) {
        if (!ignoreLocation && !locationEnabled()) {
            Diagnostics.log("Suche: Standort ist ausgeschaltet – Hinweis angezeigt")
            locationOff = true
            return
        }
        locationOff = false
        finishPendingStop()
        store.setPlayerName(myName.trim())
        groups.clear()
        connectingTo = null
        Diagnostics.log("Suche gestartet")
        link.startDiscovery()
        screen = Screen.DISCOVER
    }

    fun join(group: Group) {
        joinCode = null
        connectingTo = group.endpointId
        masterPeer = group.peer
        updatePeer = null
        Diagnostics.log("Beitritt angefragt bei ${group.endpointId} (Build ${group.peer.build ?: "alt"})")
        link.requestConnection(PeerInfo.format(myName.trim(), AppUpdate.ownBuild), group.endpointId)
    }

    /** Nur zum Aktualisieren verbinden: wer die ältere Version hat, bekommt die neuere. */
    fun joinForUpdate(group: Group) {
        joinCode = null
        connectingTo = group.endpointId
        masterPeer = group.peer
        updatePeer = group.peer
        Diagnostics.log("Verbindung zum Aktualisieren bei ${group.endpointId} (Build ${group.peer.build})")
        link.requestConnection(PeerInfo.format(myName.trim(), AppUpdate.ownBuild, updateOnly = true), group.endpointId)
    }

    /** Reguläres Beenden; auf Wunsch werden danach alle Diagnosedaten gelöscht. */
    fun leave(deleteDiagnostics: Boolean = false, openForTakeover: Boolean = false) {
        Diagnostics.log(if (openForTakeover) "Verlassen – Spieler dürfen übernehmen" else "Verlassen")
        GameService.stop(getApplication())
        choosingTemplate = false
        updateTicking(null)
        replayDone()
        val engine = master
        if (engine != null) {
            // Erst verabschieden, dann trennen – sonst kommt die Nachricht nicht mehr an.
            if (openForTakeover) engine.leaveOpen() else engine.close()
            pendingStop = scheduler.schedule(500) { pendingStop = null; link.stopAll() }
        } else {
            link.stopAll()
        }
        quizStartPending?.cancel()
        quizStartPending = null
        takeoverTask?.cancel()
        takeoverTask = null
        takeover = null
        succession = null
        takenOver = null
        diagnoseRequest = false
        intro = null
        master = null
        player = null
        masterEndpoint = null
        masterCollects = false
        diagnoseOffer = false
        confirmAfterUpload.clear()
        joinCode = null
        masterPeer = null
        updatePeer = null
        updatePeerName = null
        updateEndpoints.clear()
        peerBuilds.clear()
        expectedApks.clear()
        receivedFiles.clear()
        transfer = null
        masterSnapshot = null
        playerView = null
        connectingTo = null
        groups.clear()
        joinRequests.clear()
        emotes.clear()
        chat.clear()
        receivedProfile = null
        profileUpgrade = null
        screen = Screen.START
        if (deleteDiagnostics) {
            Diagnostics.clear(getApplication())
            status = null
        }
    }

    private fun finishPendingStop() {
        pendingStop?.let {
            it.cancel()
            link.stopAll()
        }
        pendingStop = null
    }

    // ------------------------------------------------------------------ Master-Bedienung

    fun acceptJoin(request: JoinRequest) {
        joinRequests.remove(request)
        val peer = request.peer
        when {
            // Nur zum Aktualisieren: geht jederzeit, spielt aber nicht mit.
            peer.updateOnly || (!peer.compatible && !peer.legacy) -> {
                updateEndpoints[request.endpointId] = peer
                link.accept(request.endpointId)
            }
            peer.legacy -> link.reject(request.endpointId)
            master?.canAcceptJoin() == true -> link.accept(request.endpointId)
            else -> link.reject(request.endpointId)
        }
    }

    fun rejectJoin(request: JoinRequest) {
        joinRequests.remove(request)
        link.reject(request.endpointId)
    }

    // ------------------------------------------------------------------ Spielart und Quiz

    fun chooseKind(kind: GameKind) {
        val engine = master ?: return
        if (engine.chooseKind(kind)) {
            choosingKind = false
            choosingTemplate = kind == GameKind.QUIZ
        } else {
            status = "Die Spielart lässt sich nur zwischen den Runden wechseln."
        }
    }

    fun openKindChoice() {
        if (masterSnapshot?.phase == MasterEngine.Phase.READY) choosingKind = true
    }

    /** Auswahl für das nächste Quiz – auch für die Vorschau „x neue von y passenden Fragen“. */
    fun pickQuestions(settings: Settings): QuestionPicker.Result {
        val names = masterSnapshot?.players.orEmpty().filter { it.connected }.map { statsName(it.name) }.toSet()
        return QuestionPicker.pick(
            quizStore.questions,
            quizStore.stats,
            QuestionPicker.Request(
                categories = settings.quizCategories,
                levels = settings.quizLevels,
                count = settings.quizCount,
                players = names,
                today = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date()),
            ),
        )
    }

    /** Quiz starten: erst die Rennszene auf allen Geräten, dann die erste Frage. */
    fun startQuiz() {
        val engine = master ?: return
        if (quizStartPending != null) return
        if (pickQuestions(engine.settings).questions.isEmpty() || engine.snapshot().players.none { it.connected }) {
            startQuizNow()
            return
        }
        engine.playIntro(kotlin.random.Random.nextInt(de.pabich.buzzer.ui.RACE_VARIANTS))
        quizStartPending = scheduler.schedule(de.pabich.buzzer.ui.INTRO_TOTAL_MS + 100L) {
            quizStartPending = null
            if (master === engine) startQuizNow()
        }
    }

    private fun startQuizNow() {
        val engine = master ?: return
        val picked = pickQuestions(engine.settings)
        if (picked.questions.isEmpty()) {
            status = "Keine passenden Fragen – bitte Kategorien oder Stufen erweitern."
            return
        }
        if (!engine.startQuiz(picked.questions.map(quizStore::toQuiz))) {
            status = "Das Quiz kann erst starten, wenn Spieler verbunden sind."
            return
        }
        if (picked.questions.size < engine.settings.quizCount) {
            status = "Nur ${picked.questions.size} passende Fragen vorhanden."
        }
    }

    /** Nächste Frage – bei offener Challenge erst die Rückfrage. */
    fun nextQuestion() {
        if (guardOpenChallenge { master?.nextQuestion() }) master?.nextQuestion()
    }

    /**
     * Rückfrage „Offene Challenge – Wertung bleibt?“: [pendingAfterChallenge] wird nach
     * „Wertung bleibt“ ausgeführt. Liefert `true`, wenn nichts offen ist.
     */
    private fun guardOpenChallenge(action: () -> Unit): Boolean {
        val snap = master?.snapshot() ?: return true
        val quiz = snap.quiz ?: return true
        if (snap.phase != MasterEngine.Phase.QUIZ_REVEAL || (quiz.challenges.isEmpty() && !snap.reviewing)) return true
        openChallengeNames = quiz.challenges.joinToString { it.name }
        pendingAfterChallenge = action
        return false
    }

    /** Namen der offenen Challenge(s) für die Rückfrage; `null` = keine Rückfrage. */
    var openChallengeNames by mutableStateOf<String?>(null)
        private set
    private var pendingAfterChallenge: (() -> Unit)? = null

    /** Rückfrage beantwortet: [keep] = „Wertung bleibt“ und weiter, sonst zurück zur Prüfung. */
    fun answerOpenChallenge(keep: Boolean) {
        val action = pendingAfterChallenge
        openChallengeNames = null
        pendingAfterChallenge = null
        if (!keep) return
        master?.decideChallenge(ChallengeDecision.KEEP)
        action?.invoke()
    }

    /** Ersatzfrage aus demselben Filter, die in diesem Quiz noch nicht vorkommt (ungespielte zuerst). */
    fun replacementQuestion(): Question? {
        val engine = master ?: return null
        val used = engine.quizQuestionIds
        val all = pickQuestions(engine.settings.copy(quizCount = quizStore.questions.size)).questions
        return all.firstOrNull { it.id !in used }
    }

    fun replaceQuestion() {
        val engine = master ?: return
        val q = replacementQuestion() ?: run {
            status = "Keine Ersatzfrage verfügbar."
            return
        }
        engine.replaceQuestion(quizStore.toQuiz(q))
    }
    fun revealNow() = master?.revealNow()
    fun skipQuestion() = master?.skipQuestion()
    fun stopQuiz() = master?.stopQuiz()
    fun decideChallenge(decision: ChallengeDecision, newCorrect: Int? = null) = master?.decideChallenge(decision, newCorrect)

    fun refreshQuestions() {
        status = null
        quizStore.refresh { message ->
            quizDataVersion++
            status = message
        }
    }

    fun shareQuizStats(context: Context) = runCatching { quizStore.shareStats(context) }
        .onFailure { status = "Statistik konnte nicht geteilt werden: ${it.message}" }

    /** Spieler: Antwort angetippt (eigene Reihenfolge). */
    fun tapAnswer(position: Int, eventTime: Long) = player?.tapAnswer(position, eventTime)

    fun challenge(reason: String) = player?.challenge(reason)

    /** Name in der Statistik: ohne den Zusatz des mitspielenden Masters. */
    private fun statsName(name: String) = name.removeSuffix(" (Master)")

    fun release() = master?.release()
    fun markCorrect() = master?.markCorrect()
    fun markWrong() = master?.markWrong()
    fun proceed() = master?.proceed()
    fun nextRound() = master?.nextRound()
    /** Neues Spiel (Punkte auf 0); im Buzzer-Modus mit Rennszene auf allen Geräten. */
    fun newGame() {
        val engine = master ?: return
        if (engine.snapshot().phase != MasterEngine.Phase.READY) return
        engine.newGame()
        val others = engine.snapshot().players.any { it.connected && it.id != LOCAL_PLAYER_ID }
        if (engine.snapshot().kind == GameKind.BUZZER && others) engine.playIntro(kotlin.random.Random.nextInt(de.pabich.buzzer.ui.RACE_VARIANTS))
    }

    fun updateSettings(settings: Settings, profileName: String = masterSnapshot?.profileName.orEmpty()) {
        val engine = master ?: return
        if (!engine.updateSettings(settings, profileName)) {
            status = "Einstellungen lassen sich nur zwischen den Runden ändern."
            return
        }
        store.setLastSettings(engine.settings, profileName)
        // Ein Profil aus einem älteren Build ist jetzt vollständig geprüft: ergänzt speichern.
        profileUpgrade?.takeIf { it.name == profileName }?.let {
            val kind = store.profile(it.name)?.values?.get(ConfigStore.KIND_KEY) ?: ConfigStore.KIND_BUZZER
            store.saveProfile(it.name, engine.settings, kind)
            profiles = store.config.profiles
            profileUpgrade = null
        }
        syncLocalPlayer(engine)
    }

    /** Master spielt mit: lokaler Spieler auf dem Master-Gerät, gleiche Uhr, keine Laufzeit. */
    private fun syncLocalPlayer(engine: MasterEngine) {
        val wanted = engine.settings.masterPlays
        if (wanted && player == null) {
            val local = PlayerEngine(UptimeClock, scheduler) { message ->
                scheduler.schedule(0) { master?.onMessage(LOCAL_PLAYER_ID, message) }
            }
            local.onViewChanged = { onPlayerView(it, remote = false) }
            local.onSound = { playSoundEvent(it) }
            local.onArmed = { if (SoundEvent.HUM in local.view.rules.sounds) sound(SoundEvent.HUM) }
            player = local
            playerView = local.view
            engine.addPlayer(LOCAL_PLAYER_ID, "${myName.trim()} (Master)", local = true)
        } else if (!wanted && player != null) {
            engine.removePlayer(LOCAL_PLAYER_ID)
            player = null
            playerView = null
        }
    }

    // ------------------------------------------------------------------ Profile

    /** Speichert die Einstellungen als Profil und macht es zum laufenden Profil. */
    fun saveProfileAs(name: String, settings: Settings) {
        val clean = name.trim().ifEmpty { return }
        store.saveProfile(clean, settings.normalized())
        profiles = store.config.profiles
        if (profileUpgrade?.name == clean) profileUpgrade = null
        updateSettings(settings, clean)
        Diagnostics.log("Profil gespeichert: $clean")
    }

    /**
     * Lädt ein Profil in den Einstellungsdialog. Stammt es aus einem älteren Build, merkt sich die
     * App die neuen Felder; beim Übernehmen wird das Profil damit ergänzt.
     */
    fun loadProfile(name: String): Settings? {
        val profile = store.profile(name) ?: return null
        val decoded = profile.decode()
        val missing = decoded.missingKeys.mapNotNull { SettingsCodec.LABELS[it] }
        profileUpgrade = if (missing.isNotEmpty()) ProfileUpgrade(name, missing) else null
        Diagnostics.log("Profil geladen: $name" + if (missing.isNotEmpty()) " (neue Felder: ${missing.joinToString()})" else "")
        // Zu langer Countdown aus älteren Vorlagen wurde beim Einlesen begrenzt.
        val storedCountdown = profile.values["quizCountdownMs"]?.toLongOrNull()
        if (storedCountdown != null && storedCountdown > decoded.settings.quizCountdownMs) {
            val sec = decoded.settings.quizCountdownMs / 1000
            Diagnostics.log("Countdown in „$name“ von ${storedCountdown / 1000} s auf $sec s begrenzt")
            status = "Countdown auf $sec s begrenzt (höchstens die halbe Antwortzeit)."
        }
        return decoded.settings
    }

    fun renameProfile(old: String, new: String) {
        val clean = new.trim().ifEmpty { return }
        store.renameProfile(old, clean)
        profiles = store.config.profiles
        if (masterSnapshot?.profileName == old) master?.updateSettings(master!!.settings, clean)
    }

    fun deleteProfile(name: String) {
        store.deleteProfile(name)
        profiles = store.config.profiles
    }

    fun shareProfile() = master?.shareProfile(
        if (masterSnapshot?.kind == GameKind.QUIZ) mapOf(ConfigStore.KIND_KEY to ConfigStore.KIND_QUIZ) else emptyMap(),
    )

    // ------------------------------------------------------------------ Quiz-Vorlagen

    /** Vorlage übernehmen; `null` = mit den aktuellen Einstellungen weiter. */
    fun useQuizTemplate(name: String?) {
        if (name != null) loadProfile(name)?.let { updateSettings(it, name) }
        choosingTemplate = false
    }

    /** Aktuelle Quiz-Einstellungen als Vorlage speichern und aktiv machen. */
    fun saveQuizTemplate(name: String) {
        val engine = master ?: return
        val clean = name.trim().ifEmpty { return }
        store.saveProfile(clean, engine.settings, ConfigStore.KIND_QUIZ)
        profiles = store.config.profiles
        updateSettings(engine.settings, clean)
        status = "Quiz-Vorlage „$clean“ gespeichert."
        Diagnostics.log("Quiz-Vorlage gespeichert: $clean")
    }

    // ------------------------------------------------------------------ Bereit? und Prüfung

    fun askReady() {
        if (guardOpenChallenge { master?.askReady() }) master?.askReady()
    }
    fun cancelReady() = master?.cancelReady()
    fun confirmMasterReady() = master?.confirmMasterReady()
    fun confirmReady() = player?.confirmReady()
    /** Testspiel: nichts fließt in die Quiz-Statistik; gilt bis zum Neustart der App. */
    var testGame by mutableStateOf(false)
        private set

    fun toggleTestGame() {
        testGame = !testGame
        master?.testGame = testGame
        Diagnostics.log(if (testGame) "Testspiel an – keine Statistik" else "Testspiel aus")
        status = if (testGame) "Testspiel: Antworten zählen nur in der getrennten Test-Statistik." else "Testspiel beendet – die Statistik zählt wieder."
    }

    fun startReview() = master?.startReview()
    fun endReview() = master?.endReview()

    fun saveReceivedProfile(name: String) {
        val received = receivedProfile ?: return
        val clean = name.trim().ifEmpty { received.name }
        store.saveProfileValues(clean, received.values)
        profiles = store.config.profiles
        receivedProfile = null
        Diagnostics.log("Empfangenes Profil gespeichert: $clean")
    }

    fun freeProfileName(base: String) = store.freeProfileName(base)

    // ------------------------------------------------------------------ Emoticons und Nachrichten

    fun sendEmote(emoji: String) {
        if (!receiveEmojis) return
        master?.sendEmote(emoji) ?: player?.sendEmote(emoji)
    }

    // ------------------------------------------------------------------ Klänge, Spielverlauf, Design

    private fun sound(event: SoundEvent): Int = if (soundOn) sounds.play(event) else 0

    /** Klang, den der Master für dieses Gerät geschickt hat. */
    private fun playSoundEvent(event: SoundEvent) {
        sound(event)
        if (event == SoundEvent.RIGHT || event == SoundEvent.TUSCH) confettiSeq++
    }

    /** Eingeschaltete Klänge des laufenden Profils (Master: eigene Einstellungen). */
    private val activeSounds: Set<SoundEvent>
        get() = master?.settings?.sounds ?: playerView?.rules?.sounds ?: emptySet()

    /** Neue Spieleranzeige: Klänge bei Zustandswechseln, tickende Uhr beim Antworten. */
    private fun onPlayerView(new: PlayerEngine.View, remote: Boolean) {
        val old = playerView
        val enabled = new.rules.sounds
        fun play(e: SoundEvent) { if (e in enabled) sound(e) }
        if (old != null) {
            if (new.display == Display.PRESSED && old.display != Display.PRESSED) play(SoundEvent.BUZZ)
            if (new.display == Display.GREEN && old.display != Display.GREEN) play(SoundEvent.FIRST)
            if (new.display == Display.RED && old.display != Display.RED) play(SoundEvent.RED)
            if (new.display == Display.GRAY && old.display != Display.GRAY && new.hint == "Frühstart") play(SoundEvent.FALSE_START)
        }
        if (remote) {
            val timer = new.timer
            val answering = new.display == Display.GREEN && timer?.kind == PlayerEngine.TimerKind.ANSWER
            updateTicking(if (answering && SoundEvent.TICK in enabled) timer!!.startAt + timer.durationMs else null)
        }
        playerView = new
    }

    /** Tickt jede Sekunde, in den letzten 5 Sekunden doppelt so schnell. */
    private fun updateTicking(deadline: Long?) {
        val current = tickDeadline
        // Der Master schickt den Antwort-Timer wiederholt; kleine Abweichungen sind dieselbe Frist.
        if (deadline != null && current != null && abs(deadline - current) < 300) return
        if (deadline == null && current == null) return
        tickTask?.cancel()
        tickTask = null
        tickDeadline = deadline
        if (deadline == null) return
        fun tick() {
            val left = deadline - UptimeClock.now()
            if (left <= 0 || tickDeadline != deadline) return
            sound(SoundEvent.TICK)
            tickTask = scheduler.schedule(if (left > 5_000) 1_000 else 500) { tick() }
        }
        tick()
    }

    private fun startReplay(r: Message.Replay) {
        replayDone()
        replay = r
        if (SoundEvent.CHASE in activeSounds) chaseStream = sound(SoundEvent.CHASE)
    }

    /** Ende der Animation: Musik stoppen (der Endstand bleibt noch kurz stehen). */
    fun replayMusicEnd() {
        sounds.stop(chaseStream)
        chaseStream = 0
    }

    /** Spielverlauf geschlossen. */
    fun replayDone() {
        replayMusicEnd()
        replay = null
    }

    fun showReplay() = master?.showReplay()

    fun setPaused(on: Boolean) = master?.setPaused(on)

    /** Letzte Runde: anderer Sieger ([winner]) oder storniert (`null`). */
    fun correctLastRound(winner: String?) = master?.correctLastRound(winner)

    fun changeSoundOn(on: Boolean) {
        soundOn = on
        store.setSoundOn(on)
        if (!on) replayDone()
    }

    fun changeDesign(id: String) {
        designId = id
        store.setDesign(id)
    }

    // ------------------------------------------------------------------ Versionen und Updates

    /** Master: Spieler mit älterem Build (kompatibel) – „Alle auf Build N bringen“. */
    val outdatedPlayers: List<String>
        get() = masterSnapshot?.players.orEmpty().filter { it.connected && it.id != LOCAL_PLAYER_ID }
            .filter { (peerBuilds[it.id] ?: Int.MAX_VALUE) < AppUpdate.ownBuild }.map { it.id }

    /** Master: ein Spieler hat eine neuere Version (Endpunkt, Name, Build). */
    val newerPlayer: Triple<String, String, Int>?
        get() = masterSnapshot?.players.orEmpty().filter { it.connected }
            .mapNotNull { p -> peerBuilds[p.id]?.takeIf { it > AppUpdate.ownBuild }?.let { Triple(p.id, p.name, it) } }
            .maxByOrNull { it.third }

    /** Master: eigene Version an alle Spieler mit älterem Build schicken. */
    fun pushUpdateToAll() {
        val targets = outdatedPlayers
        if (targets.isEmpty()) return
        Diagnostics.log("Update: Build ${AppUpdate.ownBuild} an ${targets.size} Spieler")
        targets.forEach { sendOwnApk(it, masterSnapshot?.players?.firstOrNull { p -> p.id == it }?.name ?: it) }
    }

    /** Master: die neuere Version eines Spielers holen. */
    fun fetchUpdateFrom(endpointId: String) {
        Diagnostics.log("Update: neuere Version von $endpointId angefordert")
        link.send(endpointId, Message.ApkRequest)
    }

    private fun sendOwnApk(endpointId: String, name: String) {
        val apk = AppUpdate.ownApk(getApplication())
        runCatching {
            link.sendFile(endpointId, apk) { id -> link.send(endpointId, Message.ApkInfo(id, AppUpdate.ownBuild, apk.length())) }
        }.onSuccess {
            transfer = Transfer(name, incoming = false, build = AppUpdate.ownBuild, progress = 0f)
        }.onFailure {
            Diagnostics.log("Update: Senden nicht möglich: ${it.message}")
            status = "Die App-Version konnte nicht gesendet werden."
        }
    }

    /** Neueste Version auf der Release-Seite abfragen. */
    fun checkRepoUpdate() {
        AppUpdate.latestBuild { build ->
            repoBuild = build
            if (build == null) status = "Die Release-Seite ist nicht erreichbar (Internet?)."
        }
    }

    fun downloadFromRepo() {
        if (downloadProgress != null) return
        downloadProgress = 0f
        AppUpdate.download(getApplication(), onProgress = { downloadProgress = it }) { file ->
            downloadProgress = null
            if (file == null) {
                status = "Download fehlgeschlagen (Internet?)."
                return@download
            }
            offerUpdate(file, "der Release-Seite")
        }
    }

    private fun offerUpdate(file: File, from: String) {
        val check = AppUpdate.check(getApplication(), file)
        Diagnostics.log("Update von $from: Build ${check.build}, ok=${check.ok} ${check.reason}")
        if (check.ok) {
            receivedUpdate = ReceivedUpdate(file, check.build, from)
        } else {
            file.delete()
            status = check.reason
        }
    }

    /** Installation starten; fehlt die Erlaubnis, zuerst die Einstellung öffnen. */
    fun installUpdate(context: Context) {
        val update = receivedUpdate ?: return
        if (!AppUpdate.canInstall(context)) {
            status = "Bitte „Unbekannte Apps installieren“ für Buzzer erlauben und dann erneut „Installieren“ tippen."
            AppUpdate.openInstallPermission(context)
            return
        }
        if (!AppUpdate.install(context, update.file)) status = "Auf diesem Gerät gibt es keinen Installer."
    }

    fun dismissTransfer() {
        transfer = null
    }

    fun navigate(target: Screen) {
        if (target in listOf(Screen.START, Screen.SETTINGS, Screen.HELP, Screen.ABOUT)) screen = target
    }

    fun changeReceiveEmojis(receive: Boolean) {
        receiveEmojis = receive
        store.setReceiveEmojis(receive)
        if (master == null) player?.setReceiveEmojis(receive)
        if (!receive) emotes.clear()
    }

    private fun showEmote(from: String, emoji: String) {
        if (!receiveEmojis) return
        emotes += FlyingEmote(++emoteSeq, from, emoji)
        if (SoundEvent.EMOTE in activeSounds) sound(SoundEvent.EMOTE)
    }

    fun emoteDone(id: Long) {
        emotes.removeAll { it.id == id }
    }

    /** Empfänger: Spieler-ID, [ALL_ADDRESS] oder (für Spieler) die Adresse des Spielleiters. */
    fun sendChat(to: String, toName: String, text: String) {
        val clean = text.trim().ifEmpty { return }
        master?.sendChat(to, clean) ?: player?.sendChat(to, clean)
        lastTypingSent = 0
        addChat(ChatLine(if (to == ALL_ADDRESS) "Du → alle" else "Du → $toName", clean, to == ALL_ADDRESS, outgoing = true))
    }

    /** Spieler tippt eine Nachricht: Hinweis an den Master, höchstens alle 3 Sekunden. */
    fun chatTyping() {
        if (master != null) return
        val now = UptimeClock.now()
        if (now - lastTypingSent < 3_000) return
        lastTypingSent = now
        player?.sendTyping(true)
    }

    /** Nachrichtenfenster geschlossen oder gesendet. */
    fun chatTypingDone() {
        if (master != null || lastTypingSent == 0L) return
        lastTypingSent = 0
        player?.sendTyping(false)
    }

    private fun addChat(line: ChatLine) {
        chat += line
        if (!line.outgoing && SoundEvent.CHAT in activeSounds) sound(SoundEvent.CHAT)
        while (chat.size > 30) chat.removeAt(0)
    }

    /** Kurznachrichten sind nur in den Rundenpausen möglich. */
    val canChat: Boolean
        get() = master?.canChat()
            ?: playerView?.let { it.rules.messagesEnabled && it.showBoard } ?: false

    // ------------------------------------------------------------------ Export / Import

    fun exportConfig(uri: Uri, parts: ConfigFormat.Parts) {
        store.setPlayerName(myName.trim())
        runCatching { store.export(uri, parts) }
            .onSuccess { status = "Export gespeichert." }
            .onFailure { status = "Export fehlgeschlagen: ${it.message}" }
    }

    fun readImport(uri: Uri) {
        runCatching { store.readImport(uri) }
            .onSuccess { importContent = it }
            .onFailure { status = "Datei konnte nicht gelesen werden: ${it.message}" }
    }

    fun profileExists(name: String) = store.profile(name) != null

    fun applyImport(name: Boolean, settings: Boolean, choices: List<ConfigStore.ProfileChoice>) {
        val content = importContent ?: return
        store.applyImport(content, name, settings, choices)
        myName = store.config.playerName
        receiveEmojis = store.config.receiveEmojis
        profiles = store.config.profiles
        importContent = null
        status = "Import abgeschlossen."
    }

    // ------------------------------------------------------------------ Spieler-Eingaben

    fun onTouchDown(eventTime: Long) = player?.onTouchDown(eventTime)
    fun onTouchUp(eventTime: Long) = player?.onTouchUp(eventTime)

    // ------------------------------------------------------------------ Nearby

    override fun onEndpointFound(endpointId: String, name: String) {
        val peer = PeerInfo.parse(name)
        Diagnostics.log("Gruppe gefunden: $endpointId (Build ${peer.build ?: "alt"}, Protokoll ${peer.protocol})")
        if (groups.none { it.endpointId == endpointId }) groups += Group(endpointId, peer)
        checkSuccessor(Group(endpointId, peer))
    }

    override fun onEndpointLost(endpointId: String) {
        groups.removeAll { it.endpointId == endpointId }
    }

    override fun onConnectionCode(endpointId: String, authCode: String) {
        Diagnostics.log("Prüfcode der Beitrittsanfrage: $authCode")
        if (connectingTo == endpointId) joinCode = authCode
    }

    override fun onConnectionRequest(endpointId: String, name: String, authCode: String) {
        val peer = PeerInfo.parse(name)
        Diagnostics.log("Beitrittsanfrage von $endpointId, Prüfcode $authCode (Build ${peer.build ?: "alt"}, Protokoll ${peer.protocol}${if (peer.updateOnly) ", Update" else ""})")
        // Nach einer Übernahme kommen die bisherigen Mitspieler ohne Rückfrage wieder hinein.
        val taken = takenOver
        if (master?.canAcceptJoin() == true && taken != null && UptimeClock.now() < takenOverUntil && peer.compatible && !peer.updateOnly &&
            peer.name != myName.trim() && (peer.name == taken.master || taken.scores.any { it.first == peer.name })
        ) {
            Diagnostics.log("Übernahme: ${peer.name} kommt zurück – ohne Rückfrage aufgenommen")
            link.accept(endpointId)
            return
        }
        // Während einer laufenden Runde wartet die Anfrage; angezeigt wird sie erst in der nächsten Pause.
        joinRequests += JoinRequest(endpointId, peer, authCode)
        if (!masterIdle) Diagnostics.log("Beitrittsanfrage wartet bis zur nächsten Pause")
    }

    override fun onConnected(endpointId: String, name: String) {
        Diagnostics.log("Verbunden mit $endpointId")
        val engine = master
        if (engine != null) {
            val update = updateEndpoints[endpointId]
            if (update != null) {
                // Nur zum Aktualisieren: wer neuer ist, schickt seine Version.
                val theirs = update.build ?: 0
                if (theirs < AppUpdate.ownBuild) sendOwnApk(endpointId, update.name) else if (theirs > AppUpdate.ownBuild) fetchUpdateFrom(endpointId)
                return
            }
            val peer = PeerInfo.parse(name)
            peerBuilds[endpointId] = peer.build
            engine.addPlayer(endpointId, peer.name)
            // Mit GitHub-Zugang: Spieler dürfen ihre gespeicherten Diagnosen abgeben.
            if (diagnoseUpload.hasToken) link.send(endpointId, Message.DiagnoseCollect)
            return
        }
        masterEndpoint = endpointId
        connectingTo = null
        joinCode = null
        link.stopDiscovery()
        val forUpdate = updatePeer
        if (forUpdate != null) {
            updatePeerName = forUpdate.name
            screen = Screen.UPDATE
            // Der Spielleiter entscheidet: Er schickt seine neuere Version oder fordert unsere an.
            return
        }
        takeover?.let { t ->
            Diagnostics.log("Übernahme: verbunden mit ${t.taker ?: masterPeer?.name}")
            status = "${t.taker ?: masterPeer?.name ?: "Ein Mitspieler"} leitet jetzt das Spiel."
            takeoverTask?.cancel()
            takeoverTask = null
            takeover = null
        }
        val engineForPlayer = PlayerEngine(UptimeClock, scheduler, log = Diagnostics::log) { link.send(endpointId, it) }
        engineForPlayer.onViewChanged = { onPlayerView(it, remote = true) }
        engineForPlayer.onSound = { playSoundEvent(it) }
        engineForPlayer.onReplay = { startReplay(it) }
        engineForPlayer.onIntro = { showIntro(it) }
        engineForPlayer.onArmed = { if (SoundEvent.HUM in engineForPlayer.view.rules.sounds) sound(SoundEvent.HUM) }
        engineForPlayer.onEmote = { from, emoji -> showEmote(from, emoji) }
        engineForPlayer.onChat = { from, text, toAll -> addChat(ChatLine(from, text, toAll, outgoing = false)) }
        engineForPlayer.onProfile = { profileName, values -> receivedProfile = ReceivedProfile(profileName, values) }
        engineForPlayer.onBye = {
            leave()
            status = "Der Spielleiter hat die Gruppe geschlossen."
        }
        player = engineForPlayer
        playerView = engineForPlayer.view
        startWatchdog(engineForPlayer, endpointId)
        engineForPlayer.setReceiveEmojis(receiveEmojis)
        GameService.start(getApplication(), "Du spielst bei ${masterPeer?.name ?: "einer Buzzer-Runde"} mit")
        screen = Screen.PLAYER
    }

    override fun onConnectionFailed(endpointId: String, reason: String) {
        Diagnostics.log("Verbindung mit $endpointId fehlgeschlagen: $reason")
        joinRequests.removeAll { it.endpointId == endpointId }
        if (master == null && connectingTo == endpointId) {
            connectingTo = null
            joinCode = null
            val t = takeover
            if (t != null) {
                // Nach einer Übernahme: kurz warten und erneut versuchen.
                takeover = t.copy(taker = null)
                scheduler.schedule(1_500) { groups.firstOrNull { it.endpointId == endpointId }?.let { checkSuccessor(it) } }
                return
            }
            status = "Der Beitritt wurde abgelehnt oder ist fehlgeschlagen."
        }
    }

    override fun onDisconnected(endpointId: String) {
        joinRequests.removeAll { it.endpointId == endpointId }
        val engine = master
        if (engine != null && updateEndpoints.remove(endpointId) != null) {
            Diagnostics.log("Update-Verbindung beendet: $endpointId")
            return
        }
        if (master == null && screen == Screen.UPDATE && endpointId == masterEndpoint) {
            Diagnostics.log("Update-Verbindung beendet")
            masterEndpoint = null
            if (transfer?.done != true && receivedUpdate == null) status = "Die Verbindung wurde getrennt."
            return
        }
        if (engine != null) {
            peerBuilds.remove(endpointId)
            Diagnostics.log("Getrennt von $endpointId")
            engine.removePlayer(endpointId)
            val now = UptimeClock.now()
            recentLosses.removeAll { now - it > 15_000 }
            recentLosses += now
            // Mehrere fast gleichzeitig weg, keiner mehr da – nicht, wenn nur der Letzte regulär gegangen ist.
            if (peerBuilds.isEmpty() && recentLosses.size >= 2 && engine.snapshot().kind != null) {
                // Alle weg – oft war die eigene Funkverbindung kurz aus. Neu ankündigen, damit Beitritte wieder klappen.
                Diagnostics.log("Alle Spieler getrennt – Gruppe wird neu angekündigt")
                status = "Alle Spieler sind getrennt. War deine Verbindung weg, hat vielleicht schon jemand übernommen – dann „Spiel verlassen“ und über „Gruppe suchen“ beitreten."
                scheduler.schedule(3_000) { if (master === engine) link.restartAdvertising(PeerInfo.format(myName.trim(), AppUpdate.ownBuild)) }
            }
        } else if (endpointId == masterEndpoint) {
            val silence = player?.lastMessageAt?.let { UptimeClock.now() - it }
            Diagnostics.log("Verbindung zum Spielleiter abgebrochen; letzte Nachricht vor ${silence ?: "?"} ms")
            val s = succession
            if (s != null && s.kind != null && takeover == null) {
                startTakeover(s, if (s.to.isNotEmpty()) "${s.master} übergibt die Spielleitung an ${s.to}." else "Die Verbindung zum Spielleiter ${s.master} ist abgebrochen.")
                return
            }
            if (takeover != null) return
            leave()
            status = "Die Verbindung zum Spielleiter ist abgebrochen."
        }
    }

    override fun onMessage(endpointId: String, message: Message) {
        // Versionsabgleich läuft neben dem Spiel, unabhängig von Master/Spieler.
        when (message) {
            Message.ApkRequest -> {
                val name = masterSnapshot?.players?.firstOrNull { it.id == endpointId }?.name ?: updateEndpoints[endpointId]?.name ?: masterPeer?.name ?: "Mitspieler"
                Diagnostics.log("Update: $name bittet um Build ${AppUpdate.ownBuild}")
                sendOwnApk(endpointId, name)
                return
            }
            is Message.DiagnoseChunk -> {
                if (master == null) return
                reportCollector.add(message)?.let { report ->
                    Diagnostics.log("Diagnosebericht von ${report.from} erhalten (Build ${report.build}, ${report.text.length} Zeichen)")
                    // Gespeicherte Berichte erst nach dem Hochladen bestätigen – der Spieler löscht sie dann.
                    if (report.saved && diagnoseUpload.hasToken) {
                        confirmAfterUpload[report.id] = endpointId
                    } else {
                        link.send(endpointId, Message.DiagnoseReceived(report.id))
                    }
                    addPending(report.copy(saved = false))
                    if (diagnoseUpload.hasToken) uploadReports() else status = "Diagnosebericht von ${report.from} erhalten – über ⋮ „Gespeicherte Diagnosen“ in GitHub ablegen."
                }
                return
            }
            is Message.DiagnoseReceived -> {
                val saved = pendingReports.firstOrNull { it.id == message.reportId }
                if (saved != null) {
                    removePending(saved)
                    Diagnostics.log("Gespeicherte Diagnose ${saved.id} liegt im Repository des Spielleiters – hier gelöscht")
                    if (pendingReports.isEmpty()) status = "Deine gespeicherten Diagnosen liegen jetzt beim Spielleiter in GitHub."
                } else {
                    Diagnostics.log("Spielleiter hat den Diagnosebericht erhalten")
                    status = "Der Spielleiter hat deinen Diagnosebericht erhalten."
                }
                return
            }
            is Message.Succession -> {
                if (master != null || endpointId != masterEndpoint) return
                succession = message
                if (message.to.isNotEmpty()) {
                    Diagnostics.log("Spielleitung geht an ${message.to}")
                    if (message.to == myName.trim()) becomeSuccessor(message)
                }
                return
            }
            Message.MasterGone -> {
                if (master != null || endpointId != masterEndpoint) return
                val s = succession
                Diagnostics.log("Spielleiter geht – Übernahme möglich")
                if (s == null || s.kind == null) {
                    leave()
                    status = "Der Spielleiter hat die Gruppe geschlossen."
                } else {
                    link.disconnect(endpointId)
                    startTakeover(s, "${s.master} hat das Spiel verlassen.")
                }
                return
            }
            Message.DiagnoseRequest -> {
                if (master != null || endpointId != masterEndpoint) return
                Diagnostics.log("Spielleiter bittet um Diagnose")
                val choice = savedDiagnoseChoice()
                when (choice) {
                    null -> diagnoseRequest = true
                    false -> Diagnostics.log("Diagnose nicht gesendet (so eingestellt bis zum nächsten Build)")
                    true -> {
                        sendDiagnoseToMaster(Diagnostics.buildReport(getApplication(), status, diagPrefs.getBoolean(KEY_SYSTEM, false)))
                        Diagnostics.log("Diagnose automatisch gesendet (so eingestellt bis zum nächsten Build)")
                    }
                }
                return
            }
            Message.DiagnoseCollect -> {
                if (master != null) return
                masterCollects = true
                if (pendingReports.isNotEmpty() && !offerDeclined) diagnoseOffer = true
                return
            }
            is Message.ApkInfo -> {
                expectedApks[message.payloadId] = endpointId to message.build
                transfer = Transfer(senderName(endpointId), incoming = true, build = message.build, progress = 0f)
                receivedFiles.remove(message.payloadId)?.let { (uri, file) -> takeApk(message.payloadId, uri, file) }
                return
            }
            else -> Unit
        }
        val engine = master
        if (engine != null) {
            engine.onMessage(endpointId, message)
        } else if (endpointId == masterEndpoint) {
            player?.onMessage(message)
        }
    }

    override fun onError(text: String) {
        status = if ("8034" in text) {
            "Die Suche braucht den eingeschalteten Standort (Schnelleinstellungen → „Standort“). Er wird nur für die Suche genutzt, nicht ausgewertet."
        } else {
            text
        }
    }

    // ------------------------------------------------------------------ Herzschlag

    /**
     * Spieler: Ab Build [HEARTBEAT_BUILD] pingt der Spielleiter in jeder Phase etwa alle 2 s. Bleibt er
     * [MASTER_SILENCE_MS] still, gilt die Verbindung als abgebrochen – Nearby merkt das sonst erst nach ~1 Minute.
     */
    private fun startWatchdog(engine: PlayerEngine, endpointId: String) {
        val since = UptimeClock.now()
        fun check() {
            if (player !== engine || masterEndpoint != endpointId) return
            val build = masterPeer?.build ?: 0
            val silence = UptimeClock.now() - (engine.lastMessageAt ?: since)
            if (build >= HEARTBEAT_BUILD && silence > MASTER_SILENCE_MS) {
                Diagnostics.log("Spielleiter seit $silence ms still – Verbindung gilt als abgebrochen")
                link.disconnect(endpointId)
                onDisconnected(endpointId)
                return
            }
            scheduler.schedule(2_000) { check() }
        }
        scheduler.schedule(2_000) { check() }
    }

    // ------------------------------------------------------------------ Übernahme der Spielleitung

    /** Spielleiter weg: Spieler suchen den Nachfolger bzw. können selbst übernehmen; nach 1 Minute ist Schluss. */
    private fun startTakeover(s: Message.Succession, reason: String) {
        Diagnostics.log("Übernahme: $reason")
        player = null
        playerView = null
        masterEndpoint = null
        masterPeer = null
        connectingTo = null
        joinCode = null
        updateTicking(null)
        replayDone()
        intro = null
        groups.clear()
        joinRequests.clear()
        val deadline = UptimeClock.now() + TAKEOVER_MS
        takeover = Takeover(s, deadline, s.to.ifEmpty { null }, reason = reason)
        screen = Screen.TAKEOVER
        sound(SoundEvent.CHAT)
        link.stopDiscovery()
        link.startDiscovery()
        armTakeoverTimeout(deadline)
    }

    private fun armTakeoverTimeout(deadline: Long) {
        takeoverTask?.cancel()
        takeoverTask = scheduler.schedule(deadline - UptimeClock.now()) {
            takeoverTask = null
            if (takeover == null) return@schedule
            Diagnostics.log("Übernahme: niemand hat übernommen – Spiel beendet")
            leave()
            status = "Niemand hat die Spielleitung übernommen – das Spiel ist beendet."
        }
    }

    /** Rang in der Reihenfolge des Vorgängers (wer früher dabei war, hat Vorrang). */
    private fun rankIn(s: Message.Succession, name: String): Int =
        s.scores.indexOfFirst { it.first == name }.let { if (it < 0) Int.MAX_VALUE else it }

    /** Gefundene Gruppe: Ist das der Nachfolger, dem wir beitreten? */
    private fun checkSuccessor(group: Group) {
        val name = group.peer.name
        if (!group.peer.compatible || name == myName.trim()) return
        val t = takeover
        if (t != null && master == null) {
            val s = t.succession
            val fits = if (t.expected != null) name == t.expected else name != s.master && s.scores.any { it.first == name }
            if (!fits || connectingTo != null) return
            Diagnostics.log("Übernahme: $name leitet jetzt – Beitritt")
            // Wer gerade verbunden wird, bekommt noch etwas Zeit.
            val deadline = maxOf(t.deadline, UptimeClock.now() + TAKEOVER_CONNECT_MS)
            takeover = t.copy(taker = name, deadline = deadline)
            armTakeoverTimeout(deadline)
            join(group)
            return
        }
        // Zwei haben gleichzeitig übernommen: Der später Beigetretene gibt ab und tritt bei.
        val taken = takenOver ?: return
        val engine = master ?: return
        // Der Vorgänger selbst zählt nicht: Er ist wieder da, aber das Spiel liegt jetzt bei uns.
        if (name == taken.master || UptimeClock.now() > takenOverUntil || rankIn(taken, name) >= rankIn(taken, myName.trim())) return
        Diagnostics.log("Übernahme: $name hat ebenfalls übernommen und hat Vorrang – gebe ab")
        val s = engine.succession(name)
        engine.leaveOpen()
        link.stopAll()
        master = null
        masterSnapshot = null
        player = null
        playerView = null
        takenOver = null
        startTakeover(s, "$name hat die Spielleitung zugleich übernommen.")
    }

    /** „Ich übernehme die Spielleitung.“ */
    fun takeOver() {
        val t = takeover ?: return
        if (t.expected != null || t.taker != null) return
        becomeSuccessor(t.succession)
    }

    private fun becomeSuccessor(s: Message.Succession) {
        Diagnostics.log("Übernahme: ich leite jetzt das Spiel")
        takeoverTask?.cancel()
        takeoverTask = null
        takeover = null
        succession = null
        masterEndpoint = null
        masterPeer = null
        player = null
        playerView = null
        groups.clear()
        link.stopAll()
        openGroup(s)
        status = "Du leitest jetzt das Spiel – die anderen verbinden sich gleich wieder."
    }

    /** Spielleiter: Leitung an [name] übergeben und selbst als Spieler weitermachen. */
    fun handOver(name: String) {
        val engine = master ?: return
        if (!engine.handOver(name)) {
            status = "Übergeben geht nur zwischen den Runden bzw. bei der Auflösung."
            return
        }
        val s = engine.succession(name)
        updateTicking(null)
        scheduler.schedule(500) {
            if (master !== engine) return@schedule
            link.stopAll()
            master = null
            masterSnapshot = null
            player = null
            playerView = null
            joinRequests.clear()
            peerBuilds.clear()
            takenOver = null
            startTakeover(s, "Du hast die Spielleitung an $name übergeben.")
        }
    }

    /** Spieler, an die der Spielleiter übergeben kann. */
    val handOverCandidates: List<String>
        get() = masterSnapshot?.players.orEmpty().filter { it.connected && it.id != LOCAL_PLAYER_ID && it.id in peerBuilds }.map { it.name }

    // ------------------------------------------------------------------ Diagnoseberichte

    /** Spielleiter: alle Spieler um ihren Diagnosebericht bitten (jeder entscheidet selbst). */
    fun requestDiagnoses() {
        val ids = peerBuilds.keys.toList()
        if (ids.isEmpty()) return
        ids.forEach { link.send(it, Message.DiagnoseRequest) }
        Diagnostics.log("Diagnosen bei ${ids.size} Spielern angefragt")
        status = "Diagnosen bei ${ids.size} Spielern angefragt – sie kommen, sobald sie zustimmen."
    }

    /** Gespeicherte Antwort auf „Diagnose an den Spielleiter?“: `true` senden, `false` nicht, `null` fragen. Gilt bis zum nächsten Build. */
    private fun savedDiagnoseChoice(): Boolean? =
        if (diagPrefs.getInt(KEY_BUILD, 0) == AppUpdate.ownBuild) diagPrefs.getBoolean(KEY_SEND, false) else null

    /** Antwort auf die Anfrage des Spielleiters; [remember] = bis zum nächsten Build nicht erneut fragen. */
    fun answerDiagnoseRequest(send: Boolean, includeSystem: Boolean, remember: Boolean) {
        diagnoseRequest = false
        if (remember) {
            diagPrefs.edit().putInt(KEY_BUILD, AppUpdate.ownBuild).putBoolean(KEY_SEND, send).putBoolean(KEY_SYSTEM, includeSystem).apply()
            val what = when {
                !send -> "nie senden"
                includeSystem -> "immer senden (mit Systemdaten)"
                else -> "immer senden"
            }
            Diagnostics.log("Diagnose-Anfragen: bis zum nächsten Build $what")
        }
        if (send && canSendToMaster) {
            sendDiagnoseToMaster(Diagnostics.buildReport(getApplication(), status, includeSystem))
        } else if (!send) {
            Diagnostics.log("Diagnose-Anfrage abgelehnt")
        }
    }

    /** Ist dieses Gerät als Spieler mit einem Spielleiter verbunden? */
    val canSendToMaster: Boolean get() = master == null && player != null && masterEndpoint != null

    /** Spieler: Bericht in Teilen an den Spielleiter schicken. */
    fun sendDiagnoseToMaster(report: String) {
        val endpoint = masterEndpoint ?: return
        val id = System.currentTimeMillis().toString(36)
        DiagnoseTransfer.split(id, myName.trim().ifEmpty { "Spieler" }, AppUpdate.ownBuild, report).forEach { link.send(endpoint, it) }
        Diagnostics.log("Diagnosebericht an den Spielleiter gesendet")
        status = "Diagnosebericht wird an den Spielleiter gesendet …"
    }

    /** Eigenen Bericht speichern und mit Token gleich in GitHub ablegen (ohne Token: Anleitung). */
    fun addOwnReport(report: String) {
        addPending(ownReport(report))
        uploadReports()
    }

    /** Eigenen Bericht nur auf dem Gerät speichern; mit Token wird er sofort hochgeladen. */
    fun saveReport(report: String) {
        addPending(ownReport(report))
        if (diagnoseUpload.hasToken) {
            uploadReports()
        } else {
            status = "Diagnose auf dem Gerät gespeichert. Ein Spielleiter mit GitHub-Zugang kann sie beim nächsten Beitritt übernehmen."
        }
    }

    private fun ownReport(text: String) = DiagnoseTransfer.Report(
        System.currentTimeMillis().toString(36),
        myName.trim().ifEmpty { if (isMaster) "Spielleiter" else "Spieler" },
        AppUpdate.ownBuild,
        text,
    )

    fun deleteReport(report: DiagnoseTransfer.Report) {
        removePending(report)
        Diagnostics.log("Gespeicherte Diagnose ${report.id} gelöscht")
    }

    fun shareReport(context: Context, report: DiagnoseTransfer.Report) = Diagnostics.share(context, report.text)

    /** Spieler: alle gespeicherten Berichte an den Spielleiter; gelöscht wird erst nach seiner Bestätigung. */
    fun sendSavedToMaster() {
        diagnoseOffer = false
        val endpoint = masterEndpoint ?: return
        for (r in pendingReports.toList()) {
            DiagnoseTransfer.split(r.id, r.from, r.build, r.text, saved = true).forEach { link.send(endpoint, it) }
        }
        Diagnostics.log("${pendingReports.size} gespeicherte Diagnose(n) an den Spielleiter gesendet")
        status = "Gespeicherte Diagnosen werden an den Spielleiter gesendet …"
    }

    /** Spieler: Rückfrage schließen; [never] = bis zum Neustart nicht mehr fragen. */
    fun dismissDiagnoseOffer(never: Boolean) {
        diagnoseOffer = false
        if (never) offerDeclined = true
    }

    private fun addPending(report: DiagnoseTransfer.Report) {
        if (pendingReports.any { it.id == report.id && it.from == report.from }) return
        pendingReports += report
        runCatching { File(pendingDir, "${report.id}.txt").writeText("${report.from}|${report.build}\n${report.text}") }
            .onFailure { Diagnostics.log("Diagnosebericht nicht zwischengespeichert: ${it.message}") }
    }

    private fun removePending(report: DiagnoseTransfer.Report) {
        pendingReports.remove(report)
        File(pendingDir, "${report.id}.txt").delete()
    }

    /** Quiz-Statistik in das private Repository hochladen (für die Pflege der Fragenpakete). */
    fun uploadStats() {
        if (!diagnoseUpload.hasToken) {
            showTokenGuide = true
            return
        }
        val now = Date()
        val name = myName.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').ifEmpty { "Spielleiter" }
        val base = "statistik/${SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(now)}/$name-${SimpleDateFormat("HH-mm-ss", Locale.GERMANY).format(now)}"
        val path = "$base.buzzstats"
        status = "Statistik wird hochgeladen …"
        diagnoseUpload.upload(path, quizStore.statsJson(), "Quiz-Statistik von $name (Build ${AppUpdate.ownBuild})") { error ->
            if (error != null) {
                status = "Statistik-Upload fehlgeschlagen: $error"
                return@upload
            }
            // Test-Statistik als eigene Datei; danach auf dem Gerät leeren.
            val test = quizStore.testStatsJson()
            if (test == null) {
                status = "Statistik in GitHub abgelegt: $path"
                return@upload
            }
            diagnoseUpload.upload("$base-TEST.buzzstats", test, "Test-Statistik von $name (Build ${AppUpdate.ownBuild})") { testError ->
                if (testError == null) quizStore.clearTestStats()
                status = if (testError == null) "Statistik und Test-Statistik in GitHub abgelegt: $path" else "Test-Statistik-Upload fehlgeschlagen: $testError"
            }
        }
    }

    /** Alle offenen Berichte nacheinander in GitHub ablegen; ohne Token erst die Anleitung. */
    fun uploadReports() {
        if (uploading || pendingReports.isEmpty()) return
        if (!diagnoseUpload.hasToken) {
            showTokenGuide = true
            return
        }
        uploading = true
        val report = pendingReports.first()
        // Pfad nach der Erstellungszeit (Berichts-ID), damit gespeicherte Berichte richtig einsortiert werden.
        val now = report.id.toLongOrNull(36)?.takeIf { it in 1_600_000_000_000L..System.currentTimeMillis() + 86_400_000L }?.let(::Date) ?: Date()
        val path = DiagnoseTransfer.path(
            SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(now),
            SimpleDateFormat("HH-mm-ss", Locale.GERMANY).format(now),
            report.build,
            report.from,
        )
        diagnoseUpload.upload(path, report.text, "Diagnose von ${report.from} (Build ${report.build})") { error ->
            uploading = false
            // Gleicher Name = derselbe Bericht (Erstellungszeit) liegt schon dort, z. B. doppelt eingesammelt.
            if (error == null || error == DiagnoseUpload.ALREADY_EXISTS) {
                removePending(report)
                confirmAfterUpload.remove(report.id)?.let { link.send(it, Message.DiagnoseReceived(report.id)) }
                status = "Diagnose von ${report.from} in GitHub abgelegt: $path"
                if (pendingReports.isNotEmpty()) uploadReports()
            } else {
                status = "Hochladen fehlgeschlagen: $error"
            }
        }
    }

    /** Token aus der Anleitung speichern, prüfen und dann hochladen. */
    fun saveTokenAndUpload(token: String, repo: String) {
        diagnoseUpload.repo = repo
        diagnoseUpload.saveToken(token)
        tokenSaved = true
        diagnoseUpload.check { error ->
            if (error == null) {
                showTokenGuide = false
                status = "GitHub-Zugang geprüft – Berichte werden abgelegt."
                uploadReports()
            } else {
                status = "GitHub-Zugang: $error"
            }
        }
    }

    fun deleteToken() {
        diagnoseUpload.deleteToken()
        tokenSaved = false
        status = "GitHub-Token gelöscht."
    }

    private fun senderName(endpointId: String): String =
        masterSnapshot?.players?.firstOrNull { it.id == endpointId }?.name ?: updateEndpoints[endpointId]?.name
            ?: masterPeer?.name ?: "Mitspieler"

    override fun onTransferProgress(endpointId: String, payloadId: Long, bytes: Long, total: Long, incoming: Boolean) {
        val t = transfer ?: return
        if (t.incoming != incoming || total <= 0) return
        transfer = t.copy(progress = (bytes.toFloat() / total).coerceIn(0f, 1f))
    }

    override fun onFileReceived(endpointId: String, payloadId: Long, uri: Uri?, file: File?) {
        if (uri == null && file == null) {
            Diagnostics.log("Update: Empfang abgebrochen")
            transfer = transfer?.copy(failed = true)
            expectedApks.remove(payloadId)
            return
        }
        if (expectedApks.containsKey(payloadId)) takeApk(payloadId, uri, file) else receivedFiles[payloadId] = uri to file
    }

    private fun takeApk(payloadId: Long, uri: Uri?, file: File?) {
        val (from, build) = expectedApks.remove(payloadId) ?: return
        val apk = AppUpdate.takeReceived(getApplication(), uri, file, build)
        transfer = transfer?.copy(progress = 1f, done = true)
        if (apk == null) {
            status = "Die empfangene Version konnte nicht gespeichert werden."
            return
        }
        offerUpdate(apk, senderName(from))
        // Reine Update-Verbindung danach trennen.
        if (master != null && updateEndpoints.containsKey(from)) link.disconnect(from)
    }

    override fun onFileSent(endpointId: String, payloadId: Long, ok: Boolean) {
        Diagnostics.log("Update: Senden an $endpointId ${if (ok) "fertig" else "abgebrochen"}")
        transfer = transfer?.copy(progress = if (ok) 1f else transfer?.progress ?: 0f, done = ok, failed = !ok)
        if (ok && master != null && updateEndpoints.containsKey(endpointId)) link.disconnect(endpointId)
    }

    override fun onCleared() {
        GameService.stop(getApplication())
        // App weggewischt: Die Spieler dürfen übernehmen (Punkt 59), sobald eine Spielart läuft.
        master?.let { if (it.snapshot().kind != null) it.leaveOpen() else it.close() }
        link.stopAll()
        sounds.release()
    }

    companion object {
        /** Nearby-Endpunkt-IDs sind vierstellig, eine Verwechslung ist ausgeschlossen. */
        const val LOCAL_PLAYER_ID = "local-master"

        /** Ab diesem Build pingt der Spielleiter in jeder Phase (Herzschlag). */
        const val HEARTBEAT_BUILD = 27

        /** So lange darf der Spielleiter still sein, bevor die Verbindung als abgebrochen gilt. */
        const val MASTER_SILENCE_MS = 12_000L

        private const val KEY_BUILD = "build"
        private const val KEY_SEND = "send"
        private const val KEY_SYSTEM = "system"

        /** Ohne Nachfolger endet das Spiel nach dieser Zeit (Punkt 59). */
        const val TAKEOVER_MS = 60_000L

        /** Beitritt beim Nachfolger: so lange mindestens noch. */
        const val TAKEOVER_CONNECT_MS = 30_000L

        /** Der Nachfolger nimmt die bisherigen Mitspieler so lange ohne Rückfrage auf. */
        const val TAKEN_OVER_ACCEPT_MS = 180_000L

        /** Pause zwischen „Go!“ und dem automatischen Start der nächsten Quiz-Frage. */
        const val AUTO_START_MS = 1_500L

        /** Namen der Start-Animationen fürs Diagnose-Protokoll. */
        val SPLASH_NAMES = listOf("A Hüpfer", "B Vorhang auf", "C Hau drauf")
        val RACE_NAMES = listOf("1 Die Fabel", "2 Verfolgungsjagd", "3 Startschuss")
    }
}

/** Kurzbeschreibung eines Profils für die Ansicht beim Empfänger. */
fun describeProfile(values: Map<String, String>): List<Pair<String, String>> {
    val s = SettingsCodec.fromMap(values).settings
    fun sec(ms: Long) = String.format(java.util.Locale.GERMANY, "%.1f s", ms / 1000.0)
    // Quiz-Vorlage: nur, was im Quiz wirkt.
    if (values[ConfigStore.KIND_KEY] == ConfigStore.KIND_QUIZ) {
        return listOfNotNull(
            "Fragen" to "${s.quizCount}",
            "Kategorien" to s.quizCategories.ifEmpty { setOf("alle") }.joinToString(),
            "Stufen" to s.quizLevels.sortedBy { it.ordinal }.joinToString { it.label },
            "Antwortzeit" to sec(s.quizAnswerMs),
            "Wertung" to s.quizScoring.label,
            ("Countdown" to sec(s.quizCountdownMs)).takeIf { s.quizScoring == QuizScoring.COUNTDOWN },
            "Bereit-Abfrage automatisch" to if (s.quizAutoReadyMs > 0) "nach ${sec(s.quizAutoReadyMs)}" else "aus",
            "Gleichstand" to "${s.tieThresholdMs} ms",
            "Rangliste" to (if (s.boardShowNames) "mit Namen" else "ohne Namen") +
                ", " + (if (s.boardPlaces == 0) "alle Plätze" else "${s.boardPlaces} Plätze"),
            "Kurznachrichten" to if (s.messagesEnabled) "an" else "aus",
            "Emoticons" to if (s.emojisEnabled) s.emojis.joinToString(" ") else "aus",
        )
    }
    return listOf(
        "Startmodus" to if (s.startMode.name == "TAP") "Tippen" else "Halten und Loslassen",
        "Max. Reaktionszeit" to (s.reactionLimitMs?.let(::sec) ?: "aus"),
        "Max. Antwortzeit" to (s.answerLimit?.let(::sec) ?: "aus"),
        "Nach Zeitablauf" to if (s.autoAdvanceOnTimeout) "automatisch weiter" else "Master tippt „Weiter“",
        "Zufallsverzögerung" to "${sec(s.delayMinMs)} – ${sec(s.delayMaxMs)}",
        "Sudden Death" to "${sec(s.suddenDeathDelayMinMs)} – ${sec(s.suddenDeathDelayMaxMs)}",
        "Gleichstand" to "${s.tieThresholdMs} ms",
        "Wertung" to if (s.scoring.name == "CORRECT_ANSWERS") "Richtige Antworten" else "Restzeit",
        "Rangliste" to (if (s.boardShowNames) "mit Namen" else "ohne Namen") +
            ", " + (if (s.boardPlaces == 0) "alle Plätze" else "${s.boardPlaces} Plätze"),
        "Kurznachrichten" to if (s.messagesEnabled) "an" else "aus",
        "Emoticons" to if (s.emojisEnabled) s.emojis.joinToString(" ") else "aus",
        "Quiz" to "${s.quizCount} Fragen, ${sec(s.quizAnswerMs)} Antwortzeit, ${s.quizScoring.label}",
    )
}
