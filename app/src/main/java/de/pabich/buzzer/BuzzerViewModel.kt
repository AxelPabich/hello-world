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
import de.pabich.buzzer.core.SelfTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
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

/** Rückmeldung eines Spielers im Intensivtest. */
data class TestAckEntry(val name: String, val action: String, val ok: Boolean, val detail: String)

data class ChatLine(
    val from: String,
    val text: String,
    val toAll: Boolean,
    val outgoing: Boolean,
    val id: Long = 0,
    /** [System]-Meldung für den Spielleiter: Fehler, Verbindungsabbrüche, nicht erreichbare Spieler. */
    val system: Boolean = false,
) {
    /** Anzeigetext einer Zeile im Chat. */
    val label: String
        get() = if (system) "$from $text" else "$from${if (toAll && !outgoing) " (an alle)" else ""}: $text"
}

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
    /** Sync-Verfahren (Punkt 118): Zeitpunkt, zu dem dieses Gerät selbst übernimmt, falls sich niemand meldet. */
    val autoAt: Long? = null,
    /** Platz in der Nachfolger-Reihenfolge (0 = erster). */
    val rank: Int = 0,
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
            if (value != null) {
                Diagnostics.log("Meldung: $value")
                if (looksLikeProblem(value)) systemNote(value)
            }
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
    var splash by mutableStateOf<Int?>(nextVariant(app, "lastSplash", de.pabich.buzzer.ui.SPLASH_VARIANTS))
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
    private var autoTakeoverTask: Cancellable? = null

    /** Neuer Spielleiter: Der bisherige ist zurück – Leitung zurückgeben? (Punkt 118) */
    var returnOffer by mutableStateOf<String?>(null)
        private set

    /** Neuer Spielleiter: Stand des Vorgängers (für Rangfolge und automatische Aufnahme), solange die Übernahme läuft. */
    private var takenOver: Message.Succession? = null
    private var takenOverUntil = 0L
    private var takenOverAt = 0L

    /** Spielleiter: Name → Zeitpunkt des Verbindungsabbruchs; diese Spieler kommen im ganzen Spiel ohne Rückfrage wieder hinein. */
    private val lostPlayers = mutableMapOf<String, Long>()

    /** Spielleiter: wegen Stille getrennte oder durch eine neue Verbindung ersetzte Endpunkte – ihre spätere Meldung von Nearby nicht zählen. */
    private val droppedEndpoints = mutableSetOf<String>()

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
    /** Bereitliegende neuere Version (empfangen, geladen oder vom letzten Mal) – bis sie installiert ist. */
    var receivedUpdate by mutableStateOf<ReceivedUpdate?>(null)
        private set

    /** Rückfrage „Jetzt installieren?“ anzeigen; „Später“ blendet nur sie aus, die Version bleibt bereit. */
    var showUpdateDialog by mutableStateOf(false)
        private set

    fun postponeUpdate() {
        showUpdateDialog = false
        receivedUpdate?.let { Diagnostics.log("Update: Build ${it.build} liegt bereit – später installieren") }
    }

    fun openUpdateDialog() {
        if (receivedUpdate != null) showUpdateDialog = true
    }

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

    /** Spieler: Der Spielleiter fragt, ob er einen Selbsttest starten darf (ohne GitHub-Zugang). */
    data class SelfTestConsent(val id: Int, val intensive: Boolean, val master: String)
    var selfTestConsent by mutableStateOf<SelfTestConsent?>(null)
        private set

    /** Spielleiter: Mitspieler mit GitHub-Zugang (Endpunkte), die gespeicherte Diagnosen hochladen können. */
    private val diagCouriers = mutableSetOf<String>()

    /** Spielleiter ohne Zugang: laufende Zustimmungsrunde für einen Selbsttest. */
    private var consentId = 0
    private var consentAnswers: MutableMap<String, Boolean?>? = null
    private var consentStart: (() -> Unit)? = null
        private set

    /** Spieler: automatischer Testmodus (Selbsttest des Spielleiters) – tippt, antwortet, bestätigt. */
    private var testMode = false

    // Intensivtest (Spieler): absichtlich offline / verstummt, Frühstart, Leitung zurückgeben.
    private var testOfflineUntil = 0L
    private var testMuteUntil = 0L
    private var testEarly = false
    private var testHandBackTo: String? = null
    private var testLastAsk: Message.QuizAsk? = null
    private var testAnswerTask: Cancellable? = null

    /** Intensivtest „lange Fragen“: so lange (ms nach dem Einblenden der Antworten) wartet die App mit der Antwort; 0 = normal. */
    private var testSlowMs = 0L

    /** Intensivtest (Spielleiter): Rückmeldungen der Spieler. */
    private val testAcks = mutableListOf<TestAckEntry>()

    /** Intensivtest (Spielleiter): Wer hat ein Emoticon geschickt? */
    private val testEmotes = mutableListOf<String>()

    /** Intensivtest: Übersicht Funktion × Spieler für den Bericht. */
    private var intensiveMatrix: String? = null

    /** Intensivtest: Abbruch durch den Spielleiter. */
    private var selfTestCancel = false

    fun cancelSelfTest() {
        if (selfTestRunning) selfTestCancel = true
    }

    /** Geschätzte Dauer des Intensivtests in Minuten (für die Sicherheitsabfrage). */
    fun intensiveTestMinutes(): Int {
        val n = masterSnapshot?.players?.count { it.connected && it.id != LOCAL_PLAYER_ID } ?: 0
        return ((INTENSIVE_BASE_S + INTENSIVE_PER_PLAYER_S * n.coerceAtLeast(1)) + 59) / 60
    }

    /** Spielleiter: Selbsttest läuft; [selfTestStep] = aktueller Schritt. */
    var selfTestRunning by mutableStateOf(false)
        private set
    var selfTestStep by mutableStateOf<String?>(null)
        private set

    /** Ergebnis des letzten Selbsttests (für die Anzeige), `null` = keins. */
    var selfTestResults by mutableStateOf<List<SelfTest.Result>?>(null)

    /** Spielleiter: Zähler empfangener Diagnoseberichte (für den Selbsttest). */
    private var diagReportsReceived = 0

    /** Spieler: Spielregeln der Runde (beim Beitritt und zu Spielbeginn); `null` = ausgeblendet. */
    var rules by mutableStateOf<Message.Rules?>(null)
        private set

    /** Spieler: zuletzt erhaltene Spielregeln – über ⋮ erneut ansehen. */
    var lastRules by mutableStateOf<Message.Rules?>(null)
        private set

    fun dismissRules() {
        rules = null
    }

    /** Spieler: Spielregeln mit OK bestätigen (Abfrage der Kennung [id]); danach wartet er auf die anderen. */
    fun confirmRules(id: Int) {
        if (readMsPerChar > 0) player?.reportReadSpeed(readMsPerChar)
        player?.confirmRules(id)
        rules = null
        rulesWaiting = true
    }

    /** Spieler: hat bestätigt und wartet, bis alle bestätigt haben. */
    var rulesWaiting by mutableStateOf(false)
        private set

    /** Spieler: das Profil des laufenden Spiels kopieren (der Spielleiter schickt es; dann „Speichern“). */
    fun requestProfile() {
        player?.requestProfile()
        status = "Profil wird beim Spielleiter angefordert …"
    }

    /** Spieler: gemessenes Lesetempo (ms je Zeichen); 0 = nicht gemessen. */
    var readMsPerChar by mutableStateOf(app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getInt("readMsPerChar", 0))
        private set

    /** Lesetest abgeschlossen: speichern und dem Spielleiter mitteilen. */
    fun saveReadSpeed(msPerChar: Int) {
        readMsPerChar = msPerChar
        getApplication<Application>().getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().putInt("readMsPerChar", msPerChar).apply()
        Diagnostics.log("Lesetempo gemessen: $msPerChar ms je Zeichen")
        player?.reportReadSpeed(msPerChar)
    }

    /** Spieler: vom Endergebnis zurück zur Wartesicht. */
    fun dismissFinalResult() { player?.dismissFinalResult() }

    /** Spielleiter: bestätigt die Spielregeln selbst. */
    fun confirmMasterRules() = master?.confirmMasterRules()

    /** Spielleiter: Bestätigung abbrechen oder trotzdem beginnen. */
    fun endRulesConfirmation(proceed: Boolean) = master?.endRulesConfirmation(proceed)

    fun showRulesAgain() {
        rules = lastRules
    }

    /** Emoticon als Avatar im Spiel (leer = keins); bleibt auf dem Gerät gespeichert. */
    var avatar by mutableStateOf(app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getString("avatar", "").orEmpty())
        private set

    fun changeAvatar(emoji: String) {
        avatar = emoji
        getApplication<Application>().getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().putString("avatar", emoji).apply()
        Diagnostics.log("Avatar: ${emoji.ifEmpty { "–" }}")
        val endpoint = masterEndpoint
        when {
            master != null -> master?.setAvatar(LOCAL_PLAYER_ID, emoji)
            endpoint != null -> link.send(endpoint, Message.Avatar(emoji))
        }
    }

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

    /** „Was ist neu?“: Änderungen der letzten Builds; `null` = ausgeblendet. */
    var whatsNew by mutableStateOf<List<Changelog.Entry>?>(null)
        private set

    fun showChangelog() {
        whatsNew = Changelog.load(getApplication()).take(6).ifEmpty { null }
        if (whatsNew == null) status = "Keine Änderungsliste in diesem Build."
    }

    fun dismissWhatsNew() {
        whatsNew = null
    }
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
        AppUpdate.onInstallResult = { ok, text -> if (!ok) status = text }
        AppUpdate.checkPendingDialogInstall(app)
        findStoredUpdate()
        // Installationen im Protokoll: Wechsel der Build-Nummer seit dem letzten Start.
        val appPrefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        val lastBuild = appPrefs.getInt("lastBuild", 0)
        if (lastBuild != AppUpdate.ownBuild) {
            if (lastBuild != 0) {
                val what = if (lastBuild < AppUpdate.ownBuild) "aktualisiert" else "ZURÜCKGESETZT (ältere Version installiert)"
                Diagnostics.log("App $what: Build $lastBuild → Build ${AppUpdate.ownBuild}")
                // Erster Start nach einem Update: zeigen, was neu ist.
                if (lastBuild < AppUpdate.ownBuild) whatsNew = Changelog.since(app, lastBuild, AppUpdate.ownBuild).ifEmpty { null }
            }
            appPrefs.edit().putInt("lastBuild", AppUpdate.ownBuild).apply()
        }
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
        val engine = MasterEngine(UptimeClock, scheduler, log = { Diagnostics.log(it); noteFromMasterLog(it) }) { to, message ->
            if (to == LOCAL_PLAYER_ID) {
                // Gleicher Weg wie über Funk, nur ohne Laufzeit.
                scheduler.schedule(0) { player?.onMessage(message) }
            } else {
                link.send(to, message)
            }
        }
        engine.masterName = myName.trim()
        engine.profileExtra = {
            if (masterSnapshot?.kind == GameKind.QUIZ) mapOf(ConfigStore.KIND_KEY to ConfigStore.KIND_QUIZ) else emptyMap()
        }
        engine.onChanged = { snap ->
            masterSnapshot = snap
            acceptReturning()
            // Macht der Spielleiter weiter (Frage, Freigabe, Bereit-Abfrage), schließt der Spielverlauf.
            val playing = snap.phase != MasterEngine.Phase.READY && snap.phase != MasterEngine.Phase.ROUND_OVER &&
                snap.phase != MasterEngine.Phase.QUIZ_REVEAL
            if (replay != null && (playing || snap.ready != null)) replayDone()
            // Tickende Uhr auf dem Master-Gerät, solange jemand antwortet.
            val deadline = snap.answerDeadline.takeIf { snap.phase == MasterEngine.Phase.ANSWERING }
            updateTicking(deadline.takeIf { SoundEvent.TICK in snap.settings.sounds })
        }
        engine.onPlayerSilent = { id ->
            // Während einer Dateiübertragung (Update, Diagnose) kommen Antworten oft verspätet – dann nicht trennen.
            if (link.hasFileTransfer(id)) {
                false
            } else if (UptimeClock.now() < diagQuietUntil) {
                // Diagnosen werden gerade übertragen – der Funk ist dann oft kurz verstopft (Punkt 115).
                Diagnostics.log("Spieler still, aber Diagnose-Schonfrist läuft – nicht getrennt")
                false
            } else {
                link.disconnect(id)
                onDisconnected(id)
                droppedEndpoints += id
                true
            }
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
        engine.onEmote = { from, emoji ->
            if (selfTestRunning) testEmotes += from
            showEmote(from, emoji)
        }
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
        engine.syncCapable = { peerBuilds.isNotEmpty() && peerBuilds.values.all { (it ?: 0) >= SYNC_BUILD } }
        master = engine
        if (taken != null) {
            // Fragen des laufenden Quiz: mitgeschickte zuerst (genau wie beim Vorgänger), sonst aus den
            // eigenen Paketen. Schon gespielte, die hier fehlen, bleiben als Platzhalter – die Zählung stimmt.
            val sent = taken.questions.associateBy { it.id }
            val own = quizStore.questions.associateBy { it.id }
            val found = taken.quiz.mapIndexed { i, id ->
                sent[id] ?: own[id]?.let(quizStore::toQuiz) ?: if (i < taken.quizNext - 1) playedPlaceholder(id) else null
            }
            val next = found.take(taken.quizNext.coerceAtLeast(0)).count { it != null }
            if (found.any { it == null }) Diagnostics.log("Übernahme: ${found.count { it == null }} Fragen fehlen")
            Diagnostics.log("Übernahme: ${sent.size} Fragen mitgeschickt, ${found.count { it != null }} von ${taken.quiz.size} vorhanden")
            engine.takeOver(taken, found.filterNotNull(), next)
            testGame = taken.test
            choosingTemplate = false
            takenOver = taken
            takenOverUntil = UptimeClock.now() + TAKEN_OVER_ACCEPT_MS
            takenOverAt = UptimeClock.now()
            // Sync-Verfahren: Bleibt es hier leer, die gefundenen Gruppen noch einmal prüfen (Vorgänger mit Spielern?).
            if (taken.sync) scheduler.schedule(SYNC_LONELY_MS + 500) { if (master === engine && peerBuilds.isEmpty()) groups.toList().forEach { checkSuccessor(it) } }
        } else {
            takenOver = null
            engine.testGame = testGame
            engine.updateSettings(store.config.lastSettings, store.config.lastProfileName)
        }
        syncLocalPlayer(engine)
        masterSnapshot = engine.snapshot()
        engine.start()
        Diagnostics.log(if (taken == null) "Gruppe eröffnet" else "Gruppe als Nachfolger von ${taken.master} eröffnet (Spiel ${taken.game})")
        link.startAdvertising(advertName(engine))
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
        autoTakeoverTask?.cancel()
        autoTakeoverTask = null
        returnOffer = null
        takeover = null
        succession = null
        takenOver = null
        lostPlayers.clear()
        diagnoseRequest = false
        testMode = false
        rules = null
        lastRules = null
        rulesWaiting = false
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
        // Eine bereitliegende Version nach dem Spiel noch einmal anbieten.
        if (receivedUpdate != null) showUpdateDialog = true
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
            if (kind == GameKind.BUZZER) sendRules(engine)
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
        if (quizStartPending != null || engine.snapshot().rules != null) return
        if (pickQuestions(engine.settings).questions.isEmpty() || engine.snapshot().players.none { it.connected }) {
            startQuizNow()
            return
        }
        // Erst bestätigen alle die Spielregeln, dann Rennszene und erste Frage.
        if (engine.settings.rulesConfirm) {
            val (title, lines) = rulesFor(engine) ?: return startQuizIntro(engine)
            engine.onRulesConfirmed = { if (master === engine) startQuizIntro(engine, sendRules = false) }
            val exempt = engine.snapshot().players.filter { (peerBuilds[it.id] ?: 0) < RULES_CONFIRM_BUILD }.map { it.id }.toSet()
            if (engine.requestRulesConfirmation(title, lines, exempt)) return
        }
        startQuizIntro(engine)
    }

    private fun startQuizIntro(engine: MasterEngine, sendRules: Boolean = true) {
        if (sendRules) sendRules(engine)
        engine.playIntro(nextVariant(getApplication(), "lastRace", de.pabich.buzzer.ui.RACE_VARIANTS))
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
    fun restartQuestion() = master?.restartQuestion()
    fun abortQuestion() = master?.abortQuestion()
    fun challengeQuestion() = master?.challengeQuestion()
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
        if (engine.snapshot().kind == GameKind.BUZZER && others) {
            sendRules(engine)
            engine.playIntro(nextVariant(getApplication(), "lastRace", de.pabich.buzzer.ui.RACE_VARIANTS))
        }
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
            engine.setAvatar(LOCAL_PLAYER_ID, avatar)
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

    /** Ende der Animation: Jagdmusik stoppen, krönender Abschluss zum Endstand. */
    fun replayMusicEnd() {
        if (chaseStream == 0) return
        sounds.stop(chaseStream)
        chaseStream = 0
        if (soundOn) sounds.play(Jingle.FINALE)
    }

    private fun stopChase() {
        sounds.stop(chaseStream)
        chaseStream = 0
    }

    /** Spielverlauf geschlossen. */
    fun replayDone() {
        stopChase()
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

    // ------------------------------------------------------------------ WLAN für Update (Punkt 116)

    enum class NetAction { CHECK, DOWNLOAD }

    /** WLAN ist aus: Abfrage, ob es für Suche bzw. Download eingeschaltet werden soll. */
    var wifiPrompt by mutableStateOf<NetAction?>(null)
        private set

    /** Internet über mobile Daten wäre schon da (dann bietet der Dialog „Ohne WLAN weiter“ an). */
    var wifiPromptMobile by mutableStateOf(false)
        private set

    private var wifiWait: Cancellable? = null

    private fun withWifi(action: NetAction) {
        val app = getApplication<Application>()
        if (WifiHelper.isEnabled(app)) {
            runNet(action)
        } else {
            wifiPromptMobile = WifiHelper.hasInternet(app)
            wifiPrompt = action
            Diagnostics.log("Update: WLAN ist aus – Abfrage angezeigt" + if (wifiPromptMobile) " (mobile Daten vorhanden)" else "")
        }
    }

    private fun runNet(action: NetAction) = when (action) {
        NetAction.CHECK -> doCheckRepoUpdate()
        NetAction.DOWNLOAD -> doDownloadFromRepo()
    }

    /** Antwort auf die WLAN-Abfrage: einschalten (`true`) oder ohne WLAN weitermachen (`false`). */
    fun answerWifiPrompt(enable: Boolean) {
        val action = wifiPrompt ?: return
        wifiPrompt = null
        if (!enable) {
            Diagnostics.log("Update: ohne WLAN weiter (mobile Daten)")
            runNet(action)
            return
        }
        val app = getApplication<Application>()
        if (!WifiHelper.enable(app)) {
            Diagnostics.log("Update: WLAN ließ sich nicht einschalten")
            status = "WLAN ließ sich nicht einschalten – bitte in den Schnelleinstellungen einschalten."
            return
        }
        Diagnostics.log("Update: WLAN wird eingeschaltet, warte auf Internet")
        status = "WLAN wird eingeschaltet – sobald Internet da ist, geht es los …"
        val until = UptimeClock.now() + 60_000
        fun poll() {
            wifiWait = null
            when {
                WifiHelper.isEnabled(app) && WifiHelper.hasInternet(app, wifiOnly = true) -> {
                    Diagnostics.log("Update: WLAN verbunden")
                    status = null
                    runNet(action)
                }
                UptimeClock.now() > until -> {
                    Diagnostics.log("Update: nach 60 s kein Internet über WLAN")
                    status = "Kein Internet über WLAN – bitte mit einem WLAN verbinden und noch einmal tippen."
                }
                else -> wifiWait = scheduler.schedule(1_000) { poll() }
            }
        }
        wifiWait?.cancel()
        wifiWait = scheduler.schedule(1_000) { poll() }
    }

    fun cancelWifiPrompt() {
        wifiPrompt = null
    }

    /** Neueste Version auf der Release-Seite abfragen (vorher ggf. WLAN einschalten lassen). */
    fun checkRepoUpdate() = withWifi(NetAction.CHECK)

    fun downloadFromRepo() {
        if (downloadProgress != null) return
        withWifi(NetAction.DOWNLOAD)
    }

    private fun doCheckRepoUpdate() {
        AppUpdate.latestBuild { build ->
            repoBuild = build
            if (build == null) status = "Die Release-Seite ist nicht erreichbar (Internet?)."
        }
    }

    private fun doDownloadFromRepo() {
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
            // Unter fester Name ablegen und ältere Dateien wegräumen: Es liegt immer nur eine Version bereit.
            val target = File(AppUpdate.updatesDir(getApplication()), "buzzer-build-${check.build}.apk")
            val kept = if (file == target || file.renameTo(target)) target else file
            AppUpdate.updatesDir(getApplication()).listFiles().orEmpty().filter { it != kept }.forEach { it.delete() }
            receivedUpdate = ReceivedUpdate(kept, check.build, from)
            showUpdateDialog = true
        } else {
            file.delete()
            status = check.reason
        }
    }

    /** Beim Start: liegt noch eine neuere Version vom letzten Mal bereit? Veraltete Dateien werden gelöscht. */
    private fun findStoredUpdate() {
        val app = getApplication<Application>()
        kotlin.concurrent.thread(name = "buzzer-stored-update", isDaemon = true) {
            var best: Pair<File, Int>? = null
            for (f in AppUpdate.updatesDir(app).listFiles().orEmpty()) {
                val check = runCatching { AppUpdate.check(app, f) }.getOrNull()
                if (check != null && check.ok && check.build > (best?.second ?: 0)) {
                    best?.first?.delete()
                    best = f to check.build
                } else {
                    f.delete()
                }
            }
            best?.let { (file, build) ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (receivedUpdate == null) {
                        Diagnostics.log("Update: Build $build liegt noch bereit")
                        receivedUpdate = ReceivedUpdate(file, build, "dem letzten Mal")
                    }
                }
            }
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
        // Erst das Spiel sauber verlassen: Eine laufende Verbindung samt Hintergrund-Dienst ließ die
        // Installation auf manchen Geräten (Galaxy Tab S6) hängen.
        if (master != null || player != null || takeover != null) {
            Diagnostics.log("Update: Spiel wird für die Installation verlassen")
            leave(openForTakeover = master != null)
        }
        receivedUpdate = null
        showUpdateDialog = false
        status = "Build ${update.build} wird installiert …"
        AppUpdate.install(context, update.file) { started ->
            if (!started) {
                receivedUpdate = update
                showUpdateDialog = true
                status = "Auf diesem Gerät gibt es keinen Installer."
            }
        }
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
        chat += line.copy(id = ++chatCounter)
        if (!line.outgoing && !line.system && SoundEvent.CHAT in activeSounds) sound(SoundEvent.CHAT)
        while (chat.size > 30) chat.removeAt(0)
    }

    private var chatCounter = 0L

    /**
     * [System]-Meldung im Chat des Spielleiters (Wunsch vom 06.11.2026): Fehler, getrennte und nicht erreichbare
     * Spieler, fehlgeschlagene Verbindungen und Uploads, rote Prüfungen im Selbsttest. Nur der Spielleiter sieht sie;
     * gleiche Meldungen kurz hintereinander erscheinen nur einmal.
     */
    private fun systemNote(text: String) {
        if (master == null) return
        val now = UptimeClock.now()
        if (text == lastSystemNote && now - lastSystemNoteAt < 5_000) return
        lastSystemNote = text
        lastSystemNoteAt = now
        addChat(ChatLine("[System]", text, toAll = false, outgoing = false, system = true))
    }

    private var lastSystemNote = ""
    private var lastSystemNoteAt = 0L

    /** Spieler, die als getrennt gemeldet wurden – kommen sie zurück, meldet das System es ebenfalls. */
    private val missingPlayers = mutableSetOf<String>()

    private fun looksLikeProblem(text: String): Boolean {
        val t = text.lowercase()
        return PROBLEM_WORDS.any { it in t }
    }

    /** Übersetzt Protokollzeilen der Spiellogik in [System]-Meldungen (läuft auf dem Faden der Spiellogik). */
    private fun noteFromMasterLog(line: String) {
        val note = when {
            line.startsWith("Spieler getrennt: ") -> {
                val name = line.removePrefix("Spieler getrennt: ").substringBeforeLast(" (")
                missingPlayers += name
                "$name ist nicht mehr verbunden"
            }
            " antwortet seit " in line && line.endsWith("getrennt") -> line
            line.startsWith("Spieler beigetreten: ") -> {
                val name = line.removePrefix("Spieler beigetreten: ").substringBeforeLast(" (")
                if (missingPlayers.remove(name)) "$name ist wieder verbunden" else null
            }
            else -> null
        } ?: return
        scheduler.schedule(0) { systemNote(note) }
    }

    /** Bis zu welcher Nachricht der Spielleiter den Chat schon gesehen hat. */
    private var chatSeenId by mutableStateOf(0L)

    /** Neue, noch nicht gesehene Nachrichten anderer – der Spielleiter bekommt sie in jeder Phase angezeigt. */
    val unreadChat: List<ChatLine> get() = chat.filter { !it.outgoing && it.id > chatSeenId }

    fun markChatRead() {
        chatSeenId = chatCounter
    }

    /** Kurznachrichten sind in den Rundenpausen und während einer Spielpause möglich. */
    val canChat: Boolean
        get() = master?.canChat()
            ?: playerView?.let { it.rules.messagesEnabled && (it.showBoard || it.paused) } ?: false

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
        Diagnostics.log("Gruppe gefunden: $endpointId (Build ${peer.build ?: "alt"}, Protokoll ${peer.protocol}" + (peer.game?.let { ", Spiel $it, Leitung ${peer.epoch}" } ?: "") + ")")
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
        // Wer schon mitgespielt hat (Funkabbruch, Übernahme), kommt sofort und ohne Rückfrage wieder hinein –
        // auch mitten in einer Runde; eine laufende Quizfrage kann er noch beantworten (Punkt 76).
        if (master != null && isReturning(peer)) {
            Diagnostics.log("${peer.name} kommt zurück – ohne Rückfrage aufgenommen")
            link.accept(endpointId)
            // Sync-Verfahren: Der bisherige Spielleiter ist zurück – anbieten, ihm die Leitung zurückzugeben.
            val taken = takenOver
            if (taken != null && taken.sync && taken.to.isEmpty() && peer.name == taken.master && !testGame) {
                val engine = master
                scheduler.schedule(3_000) {
                    if (master === engine && engine?.snapshot()?.players?.any { it.connected && it.name == peer.name } == true) returnOffer = peer.name
                }
            }
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
            // Hängt noch eine alte Verbindung desselben Spielers, wird sie ersetzt (Punkte bleiben).
            engine.snapshot().players.firstOrNull { it.connected && it.id != endpointId && it.id != LOCAL_PLAYER_ID && it.name == peer.name }?.let { old ->
                Diagnostics.log("${peer.name} war noch über ${old.id} verbunden – alte Verbindung ersetzt")
                peerBuilds.remove(old.id)
                engine.removePlayer(old.id)
                // Nicht aktiv trennen: Das riss im Test (19.10., 21:45) die neue Verbindung zum selben Gerät mit –
                // sie blieb in Richtung Spieler stumm. Die alte räumt der Funk selbst auf (Punkt 80).
                droppedEndpoints += old.id
            }
            // Wer nur zurückkommt (Übernahme, Funkabbruch), kennt die Regeln schon.
            val returning = isReturning(peer)
            engine.addPlayer(endpointId, peer.name)
            if (!returning) rulesFor(engine)?.let { (title, lines) -> engine.sendRules(title, lines, endpointId) }
            // Mit GitHub-Zugang: Spieler dürfen ihre gespeicherten Diagnosen abgeben.
            if (diagnoseUpload.hasToken) link.send(endpointId, Message.DiagnoseCollect)
            return
        }
        masterEndpoint = endpointId
        connectingTo = null
        joinCode = null
        link.stopDiscovery()
        // Mit GitHub-Zugang: anbieten, Diagnosen eines Spielleiters ohne Zugang hochzuladen.
        if (diagnoseUpload.hasToken) link.send(endpointId, Message.DiagnoseCourier)
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
        // Im Intensivtest kann der Spieler kurz „verstummen“ (Funkloch): dann geht nichts an den Spielleiter.
        val engineForPlayer = PlayerEngine(UptimeClock, scheduler, log = Diagnostics::log) { if (UptimeClock.now() >= testMuteUntil) link.send(endpointId, it) }
        engineForPlayer.onViewChanged = { onPlayerView(it, remote = true) }
        engineForPlayer.onSound = { playSoundEvent(it) }
        engineForPlayer.onReplay = { startReplay(it) }
        engineForPlayer.onIntro = { showIntro(it) }
        engineForPlayer.onArmed = { if (SoundEvent.HUM in engineForPlayer.view.rules.sounds) sound(SoundEvent.HUM) }
        engineForPlayer.onEmote = { from, emoji -> showEmote(from, emoji) }
        engineForPlayer.onChat = { from, text, toAll ->
            addChat(ChatLine(from, text, toAll, outgoing = false))
            if (testMode && text.startsWith(TEST_CHAT_PREFIX)) link.send(endpointId, Message.TestAck("chat", true, text))
        }
        engineForPlayer.onProfile = { profileName, values ->
            if (testMode) {
                link.send(endpointId, Message.TestAck("profile", values.isNotEmpty(), profileName))
            } else {
                receivedProfile = ReceivedProfile(profileName, values)
            }
        }
        engineForPlayer.onBye = {
            leave()
            status = "Der Spielleiter hat die Gruppe geschlossen."
        }
        player = engineForPlayer
        playerView = engineForPlayer.view
        startWatchdog(engineForPlayer, endpointId)
        if (avatar.isNotEmpty()) link.send(endpointId, Message.Avatar(avatar))
        engineForPlayer.setReceiveEmojis(receiveEmojis)
        GameService.start(getApplication(), "Du spielst bei ${masterPeer?.name ?: "einer Buzzer-Runde"} mit")
        screen = Screen.PLAYER
    }

    override fun onConnectionFailed(endpointId: String, reason: String) {
        Diagnostics.log("Verbindung mit $endpointId fehlgeschlagen: $reason")
        val who = joinRequests.firstOrNull { it.endpointId == endpointId }?.peer?.name ?: endpointId
        systemNote("Verbindung mit $who fehlgeschlagen ($reason)")
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
        // Schon beim Verstummen getrennt oder ersetzt; das ist nur die nachträgliche Meldung.
        if (droppedEndpoints.remove(endpointId)) {
            Diagnostics.log("Alte Verbindung $endpointId beendet")
            return
        }
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
            engine.snapshot().players.firstOrNull { it.id == endpointId }?.let { lostPlayers[it.name] = UptimeClock.now() }
            engine.removePlayer(endpointId)
            val now = UptimeClock.now()
            recentLosses.removeAll { now - it > 15_000 }
            recentLosses += now
            // Mehrere fast gleichzeitig weg, keiner mehr da – nicht, wenn nur der Letzte regulär gegangen ist.
            if (peerBuilds.isEmpty() && recentLosses.size >= 2 && engine.snapshot().kind != null) {
                // Alle weg – oft war die eigene Funkverbindung kurz aus. Neu ankündigen, damit Beitritte wieder klappen.
                Diagnostics.log("Alle Spieler getrennt – Gruppe wird neu angekündigt")
                status = "Alle Spieler sind getrennt. War deine Verbindung weg, hat vielleicht schon jemand übernommen – dann „Spiel verlassen“ und über „Gruppe suchen“ beitreten."
                scheduler.schedule(3_000) { if (master === engine) link.restartAdvertising(advertName(engine)) }
                // Sync-Verfahren: Hat inzwischen jemand übernommen, findet der bisherige Spielleiter ihn und tritt bei.
                if (engine.syncGame) {
                    link.startDiscovery()
                    scheduler.schedule(TAKEOVER_MS) { if (master === engine) link.stopDiscovery() }
                }
            }
        } else if (endpointId == masterEndpoint) {
            testMuteUntil = 0
            // Nach dem Wiedereinstieg kommt dieselbe Frage noch einmal – das ist kein „neu stellen“.
            testLastAsk = null
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
                // Spieler nur als Bote: mit GitHub-Zugang nimmt er Berichte seines Spielleiters zum Hochladen an.
                if (master == null && !(diagnoseUpload.hasToken && endpointId == masterEndpoint)) return
                // Jeder Berichtsteil ist ein Lebenszeichen: Schonfrist verlängern.
                diagQuietUntil = maxOf(diagQuietUntil, UptimeClock.now() + DIAG_CHUNK_QUIET_MS)
                reportCollector.add(message)?.let { report ->
                    diagReportsReceived++
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
            is Message.Rules -> {
                if (master != null || endpointId != masterEndpoint) return
                Diagnostics.log("Spielregeln erhalten: ${message.title}")
                rules = message
                lastRules = message
                if (readMsPerChar > 0) player?.reportReadSpeed(readMsPerChar)
                if (testMode && message.confirmId != 0) scheduler.schedule(700) { if (rules?.confirmId == message.confirmId) confirmRules(message.confirmId) }
                return
            }
            is Message.RulesDone -> {
                if (master != null || endpointId != masterEndpoint) return
                rulesWaiting = false
                if (rules?.confirmId == message.id) {
                    Diagnostics.log("Alle haben die Spielregeln bestätigt – es geht los")
                    rules = null
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
            is Message.TestCommand -> {
                if (master != null || endpointId != masterEndpoint || !testMode) return
                runTestCommand(message, endpointId)
                return
            }
            Message.DiagnoseCourier -> {
                if (master == null) return
                diagCouriers += endpointId
                Diagnostics.log("${senderName(endpointId)} hat einen GitHub-Zugang und kann Diagnosen hochladen")
                return
            }
            is Message.SelfTestReply -> {
                if (master == null || message.id != consentId) return
                onConsentReply(endpointId, message.ok)
                return
            }
            is Message.SelfTestAsk -> {
                if (master != null || endpointId != masterEndpoint) return
                selfTestConsent = if (message.on) SelfTestConsent(message.id, message.intensive, masterPeer?.name ?: "Der Spielleiter") else null
                Diagnostics.log(if (message.on) "Spielleiter fragt, ob er einen Selbsttest starten darf" else "Spielleiter hat die Selbsttest-Frage zurückgezogen")
                return
            }
            is Message.TestAck -> {
                val name = master?.snapshot()?.players?.firstOrNull { it.id == endpointId }?.name ?: return
                Diagnostics.log("Intensivtest: $name meldet ${message.action} ${if (message.ok) "ok" else "FEHLER"} ${message.detail}".trim())
                testAcks += TestAckEntry(name, message.action, message.ok, message.detail)
                return
            }
            is Message.TestMode -> {
                if (master != null || endpointId != masterEndpoint) return
                testMode = message.on
                if (!message.on) testSlowMs = 0
                Diagnostics.log(if (message.on) "Testmodus an – die App spielt selbst" else "Testmodus aus")
                if (message.on) rules = null
                status = if (message.on) "Selbsttest des Spielleiters läuft – die App tippt und antwortet von selbst." else null
                return
            }
            Message.DiagnoseRequest -> {
                if (master != null || endpointId != masterEndpoint) return
                diagQuietUntil = UptimeClock.now() + DIAG_QUIET_MS
                Diagnostics.log("Spielleiter bittet um Diagnose")
                // Im Testmodus (Selbsttest des Spielleiters) immer mit Systemdaten senden.
                if (testMode) {
                    sendDiagnoseToMaster(Diagnostics.buildReport(getApplication(), status, true))
                    Diagnostics.log("Diagnose im Testmodus gesendet")
                    return
                }
                val choice = savedDiagnoseChoice()
                when (choice) {
                    null -> diagnoseRequest = true
                    false -> Diagnostics.log("Diagnose nicht gesendet (so eingestellt)")
                    true -> {
                        sendDiagnoseToMaster(Diagnostics.buildReport(getApplication(), status, diagPrefs.getBoolean(KEY_SYSTEM, false)))
                        Diagnostics.log("Diagnose automatisch gesendet (so eingestellt)")
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
            // Sobald gespielt wird, verschwinden Spielregeln und Spielverlauf – sie blockieren sonst den Buzzer.
            if (message is Message.QuizAsk || message is Message.Arm || message is Message.GoAt ||
                (message is Message.ReadyCheck && message.on)
            ) {
                rules = null
                if (replay != null) replayDone()
            }
            if (testMode) autoPlay(message)
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

    // ------------------------------------------------------------------ Selbsttest (Punkt 75)

    /** Spieler im Testmodus: tippt nach zufälliger Reaktionszeit, antwortet zufällig, bestätigt „Bereit?“. */
    private fun autoPlay(message: Message) {
        val p = player ?: return
        val r = kotlin.random.Random
        fun later(ms: Long, block: () -> Unit) = scheduler.schedule(ms.coerceAtLeast(0)) { if (player === p && testMode) block() }
        fun answerLater(answersAtLocal: Long, count: Int) {
            testAnswerTask?.cancel()
            // Hat ein Mensch schon getippt, verwirft die Spiellogik die zweite Antwort – der Test läuft einfach weiter.
            val delay = if (testSlowMs > 0) testSlowMs + r.nextLong(1_000) else 800 + r.nextLong(1_700)
            testAnswerTask = later(answersAtLocal + delay - UptimeClock.now()) { p.tapAnswer(r.nextInt(count), UptimeClock.now()) }
        }
        when (message) {
            is Message.Arm -> later(200) { p.onTouchDown(UptimeClock.now()) }
            // Intensivtest „Frühstart“: kurz vor dem Startsignal tippen.
            is Message.GoAt -> if (testEarly) {
                testEarly = false
                later(message.goAtLocal - 400 - UptimeClock.now()) {
                    val t = UptimeClock.now()
                    if (message.mode == de.pabich.buzzer.core.StartMode.HOLD_RELEASE) p.onTouchUp(t) else { p.onTouchDown(t); p.onTouchUp(t + 40) }
                }
            } else later(message.goAtLocal + 150 + r.nextLong(500) - UptimeClock.now()) {
                val t = UptimeClock.now()
                if (message.mode == de.pabich.buzzer.core.StartMode.HOLD_RELEASE) {
                    p.onTouchUp(t)
                } else {
                    p.onTouchDown(t)
                    p.onTouchUp(t + 40)
                }
            }
            is Message.QuizAsk -> {
                // „Frage neu stellen“: dieselbe Nummer mit anderer Reihenfolge?
                val last = testLastAsk
                if (last != null && last.number == message.number) {
                    masterEndpoint?.let { link.send(it, Message.TestAck("reshuffle", last.answers != message.answers, message.answers.first())) }
                }
                testLastAsk = message
                answerLater(message.answersAtLocal, message.answers.size)
                // Lange Fragen: melden, ob Frage und Antworten auf diesen Bildschirm passen.
                if (testSlowMs > 0) later(message.answersAtLocal + 1_500 - UptimeClock.now()) {
                    val q = de.pabich.buzzer.ui.QuizFitMonitor.question(message.text)
                    val a = de.pabich.buzzer.ui.QuizFitMonitor.answers(message.answers)
                    val detail = "Frage ${message.number}: " + when {
                        q == null || a == null -> "nicht angezeigt (App im Hintergrund?)"
                        else -> "Frage ${if (q.fits) "passt" else "zu lang"} (${q.fontSp.toInt()} sp), " +
                            "Antworten ${if (a.fits) "passen" else "nur mit Scrollen"} (${a.fontSp.toInt()} sp)"
                    }
                    masterEndpoint?.let { link.send(it, Message.TestAck("fit", q?.fits == true && a?.fits == true, detail)) }
                }
            }
            // Pause in der Frage: Die Antworten erscheinen später – neu planen.
            is Message.QuizShift -> testLastAsk?.takeIf { it.number == message.number }?.let { ask ->
                testLastAsk = ask.copy(answersAtLocal = ask.answersAtLocal + message.shiftMs)
                answerLater(ask.answersAtLocal + message.shiftMs, ask.answers.size)
            }
            is Message.ReadyCheck -> if (message.on) later(400) { p.confirmReady() }
            is Message.QuizEnd -> later(1_500) {
                masterEndpoint?.let { link.send(it, Message.TestAck("final", true, if (message.aborted) "abgebrochen" else "beendet")) }
                p.dismissFinalResult()
            }
            else -> Unit
        }
    }

    /** Spieler im Intensivtest: Befehl des Spielleiters ausführen. */
    private fun runTestCommand(c: Message.TestCommand, endpointId: String) {
        Diagnostics.log("Intensivtest: Befehl ${c.action}" + if (c.arg != 0L) " (${c.arg})" else "")
        val p = player
        when (c.action) {
            "offline" -> {
                // Wie ein echter Abbruch: trennen, Übernahme-Suche, nach der Offline-Zeit zurück zum Spielleiter.
                testOfflineUntil = UptimeClock.now() + c.arg.coerceIn(1_000, 30_000)
                link.disconnect(endpointId)
                onDisconnected(endpointId)
            }
            "mute" -> testMuteUntil = UptimeClock.now() + c.arg.coerceIn(1_000, 60_000)
            "chat" -> p?.sendChat(de.pabich.buzzer.core.MASTER_ADDRESS, c.text)
            "emote" -> p?.sendEmote(c.text)
            "readspeed" -> p?.reportReadSpeed(c.arg.toInt())
            "profile" -> p?.requestProfile()
            "early" -> testEarly = true
            "challenge" -> link.send(endpointId, Message.TestAck("challenge", p?.challenge("Intensivtest") == true))
            "handback" -> testHandBackTo = c.text.ifEmpty { null }
            "slow" -> {
                testSlowMs = c.arg.coerceIn(0, 30_000)
                link.send(endpointId, Message.TestAck("slow", true))
            }
            else -> link.send(endpointId, Message.TestAck(c.action, false, "unbekannter Befehl"))
        }
    }

    /**
     * Selbsttest starten (Spielleiter, zwischen den Runden). Ohne GitHub-Zugang und mit Spielern fragt der Spielleiter
     * vorher alle Mitspieler um Erlaubnis; der Bericht geht am Ende an einen Mitspieler mit Zugang (Wunsch vom 07.11.2026).
     */
    fun runSelfTest(live: Boolean, intensive: Boolean = false) {
        val engine = master ?: return
        if (selfTestRunning || consentAnswers != null) return
        if (engine.snapshot().phase != MasterEngine.Phase.READY && engine.snapshot().phase != MasterEngine.Phase.QUIZ_REVEAL) {
            status = "Den Selbsttest bitte zwischen den Runden starten."
            return
        }
        val remote = engine.snapshot().players.filter { it.connected && it.id != LOCAL_PLAYER_ID }
        if (!diagnoseUpload.hasToken && (live || intensive) && remote.isNotEmpty()) {
            askConsent(remote, intensive) { startSelfTest(live, intensive) }
        } else {
            startSelfTest(live, intensive)
        }
    }

    /** Ohne GitHub-Zugang: alle Mitspieler fragen; erst wenn alle zustimmen, startet [start]. */
    private fun askConsent(remote: List<MasterEngine.PlayerRow>, intensive: Boolean, start: () -> Unit) {
        val old = remote.filter { (peerBuilds[it.id] ?: 0) < CONSENT_BUILD }
        if (old.isNotEmpty()) {
            status = "Ohne GitHub-Zugang müssen alle Mitspieler zustimmen – ${old.joinToString { it.name }} " +
                "${if (old.size == 1) "hat" else "haben"} eine ältere App und kann nicht gefragt werden. Bitte erst aktualisieren."
            return
        }
        val id = ++consentId
        consentAnswers = remote.associate { it.id to null as Boolean? }.toMutableMap()
        consentStart = start
        remote.forEach { link.send(it.id, Message.SelfTestAsk(id, intensive)) }
        Diagnostics.log("Selbsttest ohne GitHub-Zugang: Zustimmung bei ${remote.size} Spielern angefragt")
        status = "Selbsttest: Warte auf die Zustimmung aller Mitspieler …"
        scheduler.schedule(CONSENT_TIMEOUT_MS) {
            val answers = consentAnswers
            if (consentId == id && answers != null) {
                val missing = answers.filterValues { it == null }.keys.map { senderName(it) }
                endConsent(id)
                status = "Selbsttest nicht gestartet – keine Antwort von ${missing.joinToString()}."
            }
        }
    }

    private fun onConsentReply(endpointId: String, ok: Boolean) {
        val answers = consentAnswers ?: return
        if (endpointId !in answers) return
        answers[endpointId] = ok
        Diagnostics.log("Selbsttest-Zustimmung von ${senderName(endpointId)}: ${if (ok) "ja" else "nein"}")
        if (!ok) {
            val id = consentId
            endConsent(id)
            status = "Selbsttest nicht gestartet – ${senderName(endpointId)} möchte das gerade nicht."
            return
        }
        if (answers.values.all { it == true }) {
            val start = consentStart
            consentAnswers = null
            consentStart = null
            status = "Alle Mitspieler sind einverstanden – der Selbsttest startet."
            start?.invoke()
        }
    }

    /** Zustimmungsrunde beenden; offene Fragen bei den Spielern schließen. */
    private fun endConsent(id: Int) {
        consentAnswers?.keys?.forEach { link.send(it, Message.SelfTestAsk(id, false, on = false)) }
        consentAnswers = null
        consentStart = null
    }

    /** Spieler: Antwort auf die Selbsttest-Frage des Spielleiters. */
    fun answerSelfTest(ok: Boolean) {
        val ask = selfTestConsent ?: return
        selfTestConsent = null
        masterEndpoint?.let { link.send(it, Message.SelfTestReply(ask.id, ok)) }
        Diagnostics.log("Selbsttest des Spielleiters: ${if (ok) "zugestimmt" else "abgelehnt"}")
    }

    private fun startSelfTest(live: Boolean, intensive: Boolean) {
        val engine = master ?: return
        if (selfTestRunning) return
        selfTestRunning = true
        selfTestCancel = false
        selfTestResults = null
        intensiveMatrix = null
        Diagnostics.log("Selbsttest gestartet" + if (intensive) " (Intensivtest)" else if (live) " (mit Live-Test)" else "")
        launchTest {
            val results = mutableListOf<SelfTest.Result>()
            try {
                selfTestStep = "Spiellogik und Klänge auf diesem Gerät …"
                results += inBackground { SelfTest.runOffline() }
                selfTestStep = "Fragenpakete …"
                results += checkPacks()
                selfTestStep = "Release-Seite …"
                val latest = suspendCoroutine<Int?> { c -> AppUpdate.latestBuild { c.resume(it) } }
                results += SelfTest.Result("Release-Seite erreichbar", latest != null, latest?.let { "neuester Build $it, hier Build ${AppUpdate.ownBuild}" } ?: "nicht erreichbar")
                if (intensive) results += intensiveTest() else if (live) results += liveTest(engine)
            } catch (e: Throwable) {
                results += SelfTest.Result("Selbsttest abgebrochen", false, e.message ?: e.javaClass.simpleName)
            }
            selfTestStep = "Bericht hochladen …"
            val text = selfTestReport(results)
            Diagnostics.log("Selbsttest: ${SelfTest.summary(results)}")
            addPending(DiagnoseTransfer.Report(System.currentTimeMillis().toString(36), "Selbsttest-" + myName.trim().ifEmpty { "Spielleiter" }, AppUpdate.ownBuild, text))
            if (diagnoseUpload.hasToken) uploadReports() else sendReportsToCourier()
            selfTestResults = results
            selfTestStep = null
            selfTestRunning = false
        }
    }

    private fun checkPacks(): List<SelfTest.Result> {
        val questions = quizStore.questions
        val byCategory = questions.groupBy { it.category }
        val thin = byCategory.filter { (_, qs) -> qs.map { it.level }.toSet().size < de.pabich.buzzer.core.QuizLevel.entries.size }.keys
        return listOf(
            SelfTest.Result("Fragenpakete geladen", questions.isNotEmpty(), "${questions.size} Fragen in ${byCategory.size} Kategorien"),
            SelfTest.Result("Jede Kategorie mit allen Stufen", thin.isEmpty(), if (thin.isEmpty()) "" else "fehlt bei: ${thin.joinToString()}"),
            SelfTest.Result("Keine doppelten Fragen-IDs", questions.map { it.id }.toSet().size == questions.size, ""),
        )
    }

    /** Live-Test mit den verbundenen Spielern im Testmodus. */
    private suspend fun liveTest(engine: MasterEngine): List<SelfTest.Result> {
        val results = mutableListOf<SelfTest.Result>()
        val remote = engine.snapshot().players.filter { it.connected && it.id != LOCAL_PLAYER_ID }
        if (remote.isEmpty()) return listOf(SelfTest.Result("Live-Test", false, "keine Spieler verbunden"))
        val ids = remote.map { it.id }.toSet()
        val old = remote.filter { (peerBuilds[it.id] ?: 0) < SELFTEST_BUILD }
        results += SelfTest.Result(
            "Spieler mit aktuellem Build", old.isEmpty(),
            remote.joinToString { "${it.name} (Build ${peerBuilds[it.id] ?: "alt"})" } +
                if (old.isEmpty()) "" else " – ab Build $SELFTEST_BUILD spielen Spieler im Testmodus mit",
        )
        val savedKind = engine.snapshot().kind
        val savedSettings = engine.settings
        val savedProfile = engine.profileName
        val savedTest = testGame
        testGame = true
        engine.testGame = true
        ids.forEach { link.send(it, Message.TestMode(true)) }
        try {
            // Uhrenabgleich und Herzschlag
            selfTestStep = "Uhrenabgleich und Funk-Laufzeit …"
            sleep(3_000)
            for (row in engine.snapshot().players.filter { it.id in ids }) {
                val rtt = row.rttMs
                results += SelfTest.Result("Funk-Laufzeit ${row.name}", rtt != null && rtt < 1_500, rtt?.let { "$it ms" } ?: "keine Messung")
            }
            // Buzzer-Runde
            selfTestStep = "Buzzer-Runde …"
            if (engine.snapshot().kind != GameKind.BUZZER) engine.chooseKind(GameKind.BUZZER)
            engine.updateSettings(savedSettings.copy(startMode = de.pabich.buzzer.core.StartMode.TAP), savedProfile)
            val released = engine.release()
            // Der Spielleiter-Spieler buzzert im Test nicht (10 s Wartezeit); ein Gleichstand der Spieler
            // (unter 5 ms) bringt noch ein Sudden Death dazu – dafür genug Zeit lassen.
            val decided = waitUntil(40_000) {
                engine.snapshot().phase.let { it == MasterEngine.Phase.ANSWERING || it == MasterEngine.Phase.ROUND_OVER }
            }
            sleep(1_000)
            val rows = engine.snapshot().players.filter { it.id in ids }
            val buzzed = rows.filter { it.reactionMs != null }
            results += SelfTest.Result(
                "Buzzer-Runde: alle buzzern", released && decided && buzzed.size == rows.size,
                rows.joinToString { "${it.name} ${it.reactionMs?.let { ms -> "$ms ms" } ?: "–"}" },
            )
            if (engine.snapshot().phase == MasterEngine.Phase.ANSWERING) engine.markCorrect()
            waitUntil(5_000) { engine.snapshot().phase == MasterEngine.Phase.ROUND_OVER }
            engine.nextRound()
            sleep(800)
            // Bereit-Abfrage
            selfTestStep = "Bereit-Abfrage …"
            engine.askReady()
            engine.confirmMasterReady()
            val ready = waitUntil(10_000) { engine.snapshot().ready?.allReady == true }
            results += SelfTest.Result("Bereit-Abfrage", ready, engine.snapshot().ready?.waitingNames?.takeIf { it.isNotEmpty() }?.let { "fehlt: ${it.joinToString()}" } ?: "")
            engine.cancelReady()
            sleep(500)
            // Quiz-Frage
            selfTestStep = "Quiz-Frage …"
            engine.chooseKind(GameKind.QUIZ)
            val questions = quizStore.questions.shuffled().take(2).map(quizStore::toQuiz)
            val started = questions.isNotEmpty() && engine.startQuiz(questions)
            // Sobald alle Spieler geantwortet haben, antwortet auch der Spielleiter – sonst wartet die Frage
            // die volle Antwortzeit ab.
            val allIn = started && waitUntil(engine.settings.quizAnswerMs + 10_000) {
                val q = engine.snapshot().quiz
                q?.revealed == true || q?.rows?.filter { it.id in ids }?.all { it.answered } == true
            }
            if (allIn && engine.snapshot().quiz?.revealed != true) player?.tapAnswer(0, UptimeClock.now())
            val revealed = started && waitUntil(engine.settings.quizAnswerMs + 5_000) { engine.snapshot().quiz?.revealed == true }
            val answered = engine.snapshot().quiz?.rows?.filter { it.id in ids && it.answered }?.size ?: 0
            results += SelfTest.Result("Quiz-Frage: alle antworten", revealed && answered == ids.size, "$answered von ${ids.size} Antworten")
            if (engine.snapshot().phase != MasterEngine.Phase.READY) engine.stopQuiz()
            sleep(1_500)
            replayDone()
            // Diagnosen einsammeln
            selfTestStep = "Diagnosen der Spieler einsammeln …"
            val before = diagReportsReceived
            requestDiagnoses()
            val collected = waitUntil(25_000) { diagReportsReceived - before >= ids.size }
            results += SelfTest.Result("Diagnosen der Spieler", collected, "${diagReportsReceived - before} von ${ids.size}")
        } finally {
            ids.forEach { link.send(it, Message.TestMode(false)) }
            if (savedKind != null && engine.snapshot().kind != savedKind && engine.snapshot().phase == MasterEngine.Phase.READY) engine.chooseKind(savedKind)
            engine.updateSettings(savedSettings, savedProfile)
            testGame = savedTest
            engine.testGame = savedTest
        }
        return results
    }

    // ------------------------------------------------------------------ Intensivtest (Spielleiter)

    private class SelfTestCancelled : Exception("vom Spielleiter abgebrochen")

    /**
     * Intensivtest: Jede Funktion mit jedem Spieler – Buzzer (Tippen, Halten, Frühstart, Antwortzeit),
     * Spielregeln, Quiz (Pause, neu stellen, überspringen, abbrechen, Challenge, Ersatzfrage, Endergebnis,
     * Countdown), Kurznachrichten, Emoticons, Lesetempo, Profil, offline gehen und zurückkommen, verstummen,
     * offline mitten in der Frage, Übergabe der Spielleitung hin und zurück, Diagnosen.
     * Spieler ab Build [INTENSIVE_TEST_BUILD] kennen alle Schritte; bei älteren werden ihre Schritte übersprungen.
     */
    private suspend fun intensiveTest(): List<SelfTest.Result> {
        val first = master ?: return listOf(SelfTest.Result("Intensivtest", false, "kein Spiel"))
        val names = first.snapshot().players.filter { it.connected && it.id != LOCAL_PLAYER_ID }.map { it.name }
        if (names.isEmpty()) return listOf(SelfTest.Result("Intensivtest", false, "keine Spieler verbunden"))
        val results = mutableListOf<SelfTest.Result>()
        val matrix = linkedMapOf<String, MutableMap<String, String>>()
        val startedAt = UptimeClock.now()
        val stepTitles = listOf(
            "Verbindung", "Spielregeln", "Buzzer: Tippen", "Buzzer: Halten", "Buzzer: Frühstart", "Buzzer: Antwortzeit",
            "Quiz: Antworten, Pause, neu stellen, überspringen, abbrechen, Challenge", "Quiz: lange Fragen und Antworten",
            "Quiz: Countdown-Wertung",
            "Kurznachrichten, Emoticons, Lesetempo, Profil", "Offline gehen und zurück", "Verstummen und zurück",
            "Offline mitten in der Frage", "Übergabe der Spielleitung", "Diagnosen",
        )
        var stepNo = 0
        fun step(title: String) {
            if (selfTestCancel) throw SelfTestCancelled()
            stepNo++
            selfTestStep = "Intensivtest $stepNo/${stepTitles.size}: $title"
            Diagnostics.log("Intensivtest: Schritt $stepNo – $title")
        }
        fun mark(check: String, name: String?, ok: Boolean?, detail: String = "") {
            val row = matrix.getOrPut(check) { linkedMapOf() }
            if (name == null) {
                row["alle"] = when (ok) { true -> "✓"; false -> "✗"; null -> "–" }
                if (ok != null) results += SelfTest.Result(check, ok, detail)
            } else {
                row[name] = when (ok) { true -> "✓"; false -> "✗"; null -> "–" }
                results += SelfTest.Result("$check – $name", ok != false, if (ok == null) "übersprungen" + detail.let { if (it.isEmpty()) "" else " ($it)" } else detail)
            }
            if (ok == false) scheduler.schedule(0) { systemNote("Selbsttest ✗ $check" + (name?.let { " – $it" } ?: "") + detail.let { if (it.isEmpty()) "" else " ($it)" }) }
        }
        fun engine(): MasterEngine = master ?: throw IllegalStateException("kein Spielleiter mehr")
        fun row(name: String) = master?.snapshot()?.players?.firstOrNull { it.connected && it.name == name }
        fun build(name: String) = row(name)?.let { peerBuilds[it.id] } ?: 0
        fun capable(name: String) = build(name) >= INTENSIVE_TEST_BUILD
        fun cmd(name: String, action: String, arg: Long = 0, text: String = "") {
            row(name)?.let { link.send(it.id, Message.TestCommand(action, arg, text)) }
        }
        fun acked(name: String, action: String, since: Int, ok: Boolean = true) =
            testAcks.drop(since).any { it.name == name && it.action == action && it.ok == ok }
        val oldBuild = "Build < $INTENSIVE_TEST_BUILD"
        val e0 = first
        val saved = e0.settings
        val savedProfile = e0.profileName
        val savedKind = e0.snapshot().kind
        val savedTest = testGame
        val testSettings = saved.copy(
            startMode = de.pabich.buzzer.core.StartMode.TAP, reactionLimitEnabled = true, timeLimitMs = 10_000,
            answerLimitEnabled = false, autoAdvanceOnTimeout = true, quizAnswerMs = 12_000, quizReadAdapt = false,
            quizAutoReadyMs = 0, quizScoring = QuizScoring.FASTEST, messagesEnabled = true, emojisEnabled = true,
            rulesConfirm = true, quizShuffleAnswers = true,
        )
        testAcks.clear()
        testEmotes.clear()
        fun enterTestMode() {
            testGame = true
            master?.testGame = true
            master?.snapshot()?.players?.filter { it.connected && it.id != LOCAL_PLAYER_ID }?.forEach { link.send(it.id, Message.TestMode(true)) }
        }
        enterTestMode()

        // Buzzer-Runde: freigeben, abwarten, auflösen; liefert die Zeilen nach der Entscheidung.
        suspend fun buzzerRound(settings: Settings, before: () -> Unit = {}): List<MasterEngine.PlayerRow> {
            val e = engine()
            if (e.snapshot().kind != GameKind.BUZZER) e.chooseKind(GameKind.BUZZER)
            e.updateSettings(settings, savedProfile)
            before()
            sleep(400)
            e.release()
            // Spielt der Spielleiter selbst mit, muss auch er im Halten-Modus den Finger auflegen,
            // sonst startet die Runde nie (Intensivtest 01.11.: Schritt 4 ohne Buzz).
            val local = player
            if (settings.startMode == de.pabich.buzzer.core.StartMode.HOLD_RELEASE && local != null &&
                e.snapshot().players.any { it.id == LOCAL_PLAYER_ID }
            ) {
                sleep(300)
                local.onTouchDown(UptimeClock.now())
                waitUntil(20_000) { e.snapshot().phase.let { it != MasterEngine.Phase.WAIT_HOLD && it != MasterEngine.Phase.COUNTDOWN } }
                sleep(250)
                local.onTouchUp(UptimeClock.now())
            }
            waitUntil(40_000) { e.snapshot().phase.let { it == MasterEngine.Phase.ANSWERING || it == MasterEngine.Phase.ROUND_OVER } }
            sleep(800)
            return e.snapshot().players
        }
        suspend fun finishRound() {
            val e = engine()
            if (e.snapshot().phase == MasterEngine.Phase.ANSWERING) e.markCorrect()
            waitUntil(15_000) { e.snapshot().phase == MasterEngine.Phase.ROUND_OVER }
            e.nextRound()
            sleep(700)
        }
        fun answeredAll(): Boolean {
            val q = master?.snapshot()?.quiz ?: return false
            return q.revealed || q.rows.filter { it.name in names }.all { it.answered }
        }
        fun answered(name: String) = master?.snapshot()?.quiz?.rows?.any { it.name == name && it.answered } == true
        suspend fun reveal() {
            val e = engine()
            if (e.snapshot().quiz?.revealed != true) e.revealNow()
            waitUntil(5_000) { e.snapshot().quiz?.revealed == true }
        }
        suspend fun awaitQuestion(number: Int) = waitUntil(10_000) {
            val snap = master?.snapshot()
            val q = snap?.quiz
            q != null && q.number == number && !q.revealed && snap.phase == MasterEngine.Phase.QUIZ_QUESTION
        }

        try {
            // 1 Verbindung
            step(stepTitles[0])
            sleep(3_000)
            for (n in names) {
                val rtt = row(n)?.rttMs
                mark("Verbindung", n, rtt != null && rtt < 1_500, rtt?.let { "$it ms, Build ${build(n)}" } ?: "keine Messung")
            }

            // 2 Spielregeln bestätigen
            step(stepTitles[1])
            run {
                val e = engine()
                if (e.snapshot().kind != GameKind.QUIZ) e.chooseKind(GameKind.QUIZ)
                e.updateSettings(testSettings, savedProfile)
                // Erst ab dem Intensivtest-Build bestätigen die Testspieler automatisch – ältere nicht warten lassen.
                val noAuto = names.filter { build(it) < INTENSIVE_TEST_BUILD }
                val exempt = noAuto.mapNotNull { row(it)?.id }.toSet()
                var done = false
                e.onRulesConfirmed = { done = true }
                val (title, lines) = rulesFor(e) ?: ("Intensivtest" to listOf("Test" to "Bitte bestätigen"))
                val asked = e.requestRulesConfirmation(title, lines, exempt)
                e.confirmMasterRules()
                val t0 = UptimeClock.now()
                waitUntil(15_000) { done || !asked }
                val waiting = e.snapshot().rules?.waitingNames.orEmpty()
                for (n in names) {
                    when {
                        build(n) < RULES_CONFIRM_BUILD -> mark("Spielregeln bestätigen", n, null, "Build < $RULES_CONFIRM_BUILD")
                        n in noAuto -> mark("Spielregeln bestätigen", n, null, "$oldBuild (bestätigt nicht automatisch)")
                        else -> mark("Spielregeln bestätigen", n, n !in waiting && (done || !asked), if (done) "${UptimeClock.now() - t0} ms" else "fehlt")
                    }
                }
                e.onRulesConfirmed = null
                e.endRulesConfirmation(false)
                sleep(500)
            }

            // 3 Buzzer: Tippen, Falsch → nächster, Richtig
            step(stepTitles[2])
            run {
                val rows = buzzerRound(testSettings)
                for (n in names) mark("Buzzer tippen", n, rows.firstOrNull { it.name == n }?.reactionMs != null, rows.firstOrNull { it.name == n }?.reactionMs?.let { "$it ms" } ?: "kein Buzz")
                val e = engine()
                var ok = true
                if (e.snapshot().phase == MasterEngine.Phase.ANSWERING && names.size >= 2) {
                    e.markWrong()
                    ok = waitUntil(5_000) { e.snapshot().phase.let { it == MasterEngine.Phase.ANSWERING || it == MasterEngine.Phase.ROUND_OVER } }
                }
                finishRound()
                mark("Buzzer: Falsch, dann Richtig", null, ok)
            }

            // 4 Buzzer: Halten und Loslassen
            step(stepTitles[3])
            run {
                val rows = buzzerRound(testSettings.copy(startMode = de.pabich.buzzer.core.StartMode.HOLD_RELEASE))
                for (n in names) mark("Buzzer halten/loslassen", n, rows.firstOrNull { it.name == n }?.reactionMs != null, rows.firstOrNull { it.name == n }?.reactionMs?.let { "$it ms" } ?: "kein Buzz")
                finishRound()
            }

            // 5 Buzzer: Frühstart (ein Spieler drückt absichtlich zu früh)
            step(stepTitles[4])
            run {
                val early = names.firstOrNull { capable(it) }
                if (early == null) {
                    names.forEach { mark("Frühstart erkannt", it, null, oldBuild) }
                } else {
                    val rows = buzzerRound(testSettings) { cmd(early, "early") }
                    val out = rows.firstOrNull { it.name == early }?.display == de.pabich.buzzer.core.Display.GRAY
                    mark("Frühstart erkannt", early, out, if (out) "ausgeschieden" else "nicht erkannt")
                    names.filter { it != early }.forEach { n -> mark("Frühstart erkannt", n, rows.firstOrNull { it.name == n }?.reactionMs != null, "spielt normal weiter") }
                    finishRound()
                }
            }

            // 6 Buzzer: Antwortzeit läuft ab
            step(stepTitles[5])
            run {
                buzzerRound(testSettings.copy(answerLimitEnabled = true, answerLimitMs = 3_000))
                val e = engine()
                // Nach Ablauf ist der Nächste dran (wieder ANSWERING) – erkennbar an einer neuen Frist.
                val first = e.snapshot()
                val buzzed = first.phase == MasterEngine.Phase.ANSWERING
                val d0 = first.answerDeadline
                val timedOut = buzzed && waitUntil(8_000) {
                    e.snapshot().let { it.phase != MasterEngine.Phase.ANSWERING || it.answerDeadline != d0 }
                }
                waitUntil(5_000 + 4_000L * names.size) { e.snapshot().phase == MasterEngine.Phase.ROUND_OVER }
                mark("Buzzer: Antwortzeit läuft ab", null, timedOut, if (buzzed) "" else "niemand gebuzzert")
                finishRound()
            }

            // 7 Quiz
            step(stepTitles[6])
            run {
                val e = engine()
                e.chooseKind(GameKind.QUIZ)
                e.updateSettings(testSettings, savedProfile)
                sleep(500)
                val questions = quizStore.questions.shuffled().take(7).map(quizStore::toQuiz)
                if (questions.size < 7 || !e.startQuiz(questions)) {
                    mark("Quiz starten", null, false, "zu wenige Fragen oder Start abgelehnt")
                    return@run
                }
                // Q1: alle antworten
                waitUntil(20_000) { answeredAll() }
                names.forEach { mark("Quiz: Antwort", it, answered(it)) }
                reveal(); e.nextQuestion()
                // Q2: Pause während der Frage, danach weiter
                awaitQuestion(2)
                e.setPaused(true)
                sleep(4_000)
                e.setPaused(false)
                waitUntil(25_000) { answeredAll() }
                names.forEach { n -> if (capable(n)) mark("Quiz: Pause in der Frage", n, answered(n)) else mark("Quiz: Pause in der Frage", n, null, oldBuild) }
                reveal(); e.nextQuestion()
                // Q3: neu stellen – Antworten neu gemischt
                awaitQuestion(3)
                e.setPaused(true)
                sleep(800)
                val acks = testAcks.size
                e.restartQuestion()
                waitUntil(20_000) { answeredAll() }
                sleep(500)
                names.forEach { n ->
                    if (capable(n)) mark("Quiz: neu stellen, neu gemischt", n, answered(n) && acked(n, "reshuffle", acks), if (acked(n, "reshuffle", acks, ok = false)) "gleiche Reihenfolge" else "")
                    else mark("Quiz: neu stellen, neu gemischt", n, null, oldBuild)
                }
                reveal(); e.nextQuestion()
                // Q4: überspringen
                awaitQuestion(4)
                e.setPaused(true)
                sleep(500)
                e.skipQuestion()
                mark("Quiz: Frage überspringen", null, awaitQuestion(5))
                // Q5: Runde abbrechen (ohne Wertung)
                e.setPaused(true)
                sleep(500)
                val scores = e.snapshot().players.associate { it.name to it.scoreValue }
                e.abortQuestion()
                val aborted = waitUntil(3_000) { e.snapshot().quiz?.annulled == true && e.snapshot().phase == MasterEngine.Phase.QUIZ_REVEAL }
                mark("Quiz: Runde abbrechen ohne Wertung", null, aborted && e.snapshot().players.all { scores[it.name] == null || scores[it.name] == it.scoreValue })
                e.nextQuestion()
                // Q6: Challenge eines Spielers, Ersatzfrage
                awaitQuestion(6)
                waitUntil(20_000) { answeredAll() }
                reveal()
                val challenger = names.firstOrNull { capable(it) }
                if (challenger != null) {
                    cmd(challenger, "challenge")
                    val got = waitUntil(8_000) { e.snapshot().quiz?.challenges?.any { it.name == challenger } == true }
                    mark("Quiz: Challenge", challenger, got)
                    names.filter { it != challenger }.forEach { mark("Quiz: Challenge", it, null, "nur ein Spieler fordert heraus") }
                    if (got) {
                        replaceQuestion()
                        val again = waitUntil(8_000) { e.snapshot().quiz?.let { it.number == 6 && !it.revealed } == true }
                        waitUntil(20_000) { answeredAll() }
                        mark("Quiz: Ersatzfrage", null, again && names.all { answered(it) })
                        reveal()
                    }
                } else {
                    names.forEach { mark("Quiz: Challenge", it, null, oldBuild) }
                }
                if (e.snapshot().quiz?.challenges?.isNotEmpty() == true) { e.decideChallenge(de.pabich.buzzer.core.ChallengeDecision.KEEP); e.endReview() }
                e.nextQuestion()
                // Q7: Challenge durch den Spielleiter in der Pause, dann Endergebnis
                awaitQuestion(7)
                e.setPaused(true)
                sleep(500)
                val called = e.challengeQuestion()
                val review = called && waitUntil(3_000) { e.snapshot().quiz?.revealed == true && e.snapshot().reviewing }
                e.decideChallenge(de.pabich.buzzer.core.ChallengeDecision.KEEP)
                e.endReview()
                mark("Quiz: Challenge des Spielleiters", null, review)
                val acks2 = testAcks.size
                e.nextQuestion()
                waitUntil(8_000) { names.filter { capable(it) }.all { acked(it, "final", acks2) } }
                names.forEach { n -> if (capable(n)) mark("Quiz: Endergebnis", n, acked(n, "final", acks2)) else mark("Quiz: Endergebnis", n, null, oldBuild) }
                sleep(1_000)
                replayDone()
            }

            // 8 Quiz: die Frage mit dem längsten Text und die mit den längsten Antworten – passen sie auf jeden
            // Bildschirm? Sie bleiben länger stehen; tippt ein Mensch mit, läuft der Test einfach weiter.
            step(stepTitles[7])
            run {
                val e = engine()
                if (e.snapshot().phase != MasterEngine.Phase.READY) e.stopQuiz()
                val all = quizStore.questions
                val longQ = all.maxByOrNull { it.text.length }
                val longA = all.filter { it.id != longQ?.id }.maxByOrNull { q -> q.answers.sumOf { it.length } }
                val longOne = all.filter { it.id != longQ?.id && it.id != longA?.id }.maxByOrNull { q -> q.answers.maxOf { it.length } }
                val picks = listOfNotNull(longQ, longA, longOne)
                if (picks.size < 3) { mark("Quiz: lange Fragen und Antworten", null, false, "keine Fragen"); return@run }
                results += SelfTest.Result("Längste Frage", true, "${longQ!!.id}, ${longQ.text.length} Zeichen")
                results += SelfTest.Result("Längste Antworten", true, "${longA!!.id}, zusammen ${longA.answers.sumOf { it.length }} Zeichen")
                results += SelfTest.Result("Längste Einzelantwort", true, "${longOne!!.id}, ${longOne.answers.maxOf { it.length }} Zeichen")
                val fitters = names.filter { build(it) >= FIT_TEST_BUILD }
                fitters.forEach { cmd(it, "slow", LONG_VIEW_MS) }
                e.updateSettings(testSettings.copy(quizAnswerMs = LONG_VIEW_MS + 12_000), savedProfile)
                sleep(500)
                val acks = testAcks.size
                if (!e.startQuiz(picks.map(quizStore::toQuiz))) { mark("Quiz: lange Fragen und Antworten", null, false, "Start abgelehnt"); return@run }
                for (num in picks.indices.map { it + 1 }) {
                    awaitQuestion(num)
                    waitUntil(LONG_VIEW_MS + 20_000) { answeredAll() }
                    sleep(1_000)
                    reveal()
                    sleep(LONG_REVEAL_MS)
                    e.nextQuestion()
                }
                waitUntil(5_000) { fitters.all { n -> testAcks.drop(acks).count { it.name == n && it.action == "fit" } >= picks.size } }
                val checks = listOf(
                    "Quiz: längste Frage passt" to "Frage 1:",
                    "Quiz: längste Antworten passen" to "Frage 2:",
                    "Quiz: längste Einzelantwort passt" to "Frage 3:",
                )
                for ((check, prefix) in checks) {
                    names.forEach { n ->
                        if (n !in fitters) { mark(check, n, null, "Build < $FIT_TEST_BUILD"); return@forEach }
                        val ack = testAcks.drop(acks).lastOrNull { it.name == n && it.action == "fit" && it.detail.startsWith(prefix) }
                        mark(check, n, ack?.ok == true, ack?.detail?.removePrefix(prefix)?.trim() ?: "keine Rückmeldung")
                    }
                }
                fitters.forEach { cmd(it, "slow", 0) }
                sleep(1_500)
                replayDone()
            }

            // 9 Quiz: Countdown-Wertung
            step(stepTitles[8])
            run {
                val e = engine()
                if (e.snapshot().phase != MasterEngine.Phase.READY) e.stopQuiz()
                e.updateSettings(testSettings.copy(quizScoring = QuizScoring.COUNTDOWN), savedProfile)
                sleep(500)
                val started = e.startQuiz(quizStore.questions.shuffled().take(1).map(quizStore::toQuiz))
                waitUntil(20_000) { answeredAll() }
                reveal()
                mark("Quiz: Countdown-Wertung", null, started && e.snapshot().quiz?.revealed == true)
                e.nextQuestion()
                sleep(1_500)
                replayDone()
            }

            // 10 Kurznachrichten, Emoticons, Lesetempo, Profil
            step(stepTitles[9])
            run {
                val e = engine()
                if (e.snapshot().phase != MasterEngine.Phase.READY) e.stopQuiz()
                val acks = testAcks.size
                val emoji = e.settings.emojis.firstOrNull() ?: "👍"
                for (n in names.filter { capable(it) }) {
                    cmd(n, "chat", text = "$TEST_CHAT_PREFIX Nachricht von $n")
                    row(n)?.let { sendChat(it.id, n, "$TEST_CHAT_PREFIX Hallo $n") }
                    cmd(n, "emote", text = emoji)
                    cmd(n, "readspeed", 123)
                    cmd(n, "profile")
                    sleep(300)
                }
                sleep(4_000)
                for (n in names) {
                    if (!capable(n)) {
                        listOf("Kurznachricht an den Spielleiter", "Kurznachricht an den Spieler", "Emoticon", "Lesetempo melden", "Profil kopieren").forEach { mark(it, n, null, oldBuild) }
                        continue
                    }
                    mark("Kurznachricht an den Spielleiter", n, chat.any { it.from == n && it.text.startsWith(TEST_CHAT_PREFIX) })
                    mark("Kurznachricht an den Spieler", n, acked(n, "chat", acks))
                    mark("Emoticon", n, n in testEmotes || !receiveEmojis, if (!receiveEmojis) "Empfang hier aus" else "")
                    mark("Lesetempo melden", n, row(n)?.readMsPerChar == 123)
                    mark("Profil kopieren", n, acked(n, "profile", acks))
                }
            }

            // 11 Offline gehen und zurückkommen (hart getrennt)
            step(stepTitles[10])
            for (n in names) {
                if (!capable(n)) { mark("Offline und zurück", n, null, oldBuild); continue }
                val before = row(n)?.scoreValue
                val t0 = UptimeClock.now()
                cmd(n, "offline", 4_000)
                val gone = waitUntil(8_000) { row(n) == null }
                val back = gone && waitUntil(45_000) { row(n) != null }
                sleep(1_000)
                val same = row(n)?.scoreValue == before
                mark("Offline und zurück", n, back && same, if (back) "zurück nach ${(UptimeClock.now() - t0) / 1000} s" + if (same) ", Punkte übernommen" else ", Punkte anders" else if (gone) "nicht zurückgekommen" else "nicht getrennt")
                enterTestMode()
            }

            // 12 Verstummen (Funkloch ohne Trennung) und zurückkommen
            step(stepTitles[11])
            for (n in names) {
                if (!capable(n)) { mark("Verstummen erkannt und zurück", n, null, oldBuild); continue }
                val t0 = UptimeClock.now()
                cmd(n, "mute", 20_000)
                val dropped = waitUntil(30_000) { row(n) == null }
                val back = dropped && waitUntil(45_000) { row(n) != null }
                mark("Verstummen erkannt und zurück", n, back, if (back) "zurück nach ${(UptimeClock.now() - t0) / 1000} s" else if (dropped) "nicht zurückgekommen" else "Stille nicht erkannt")
                sleep(1_000)
                enterTestMode()
            }

            // 13 Offline mitten in der Frage – nach der Rückkehr noch antworten
            step(stepTitles[12])
            run {
                val target = names.firstOrNull { capable(it) }
                if (target == null) { names.forEach { mark("Offline in der Frage, dann antworten", it, null, oldBuild) }; return@run }
                val e = engine()
                e.chooseKind(GameKind.QUIZ)
                e.updateSettings(testSettings.copy(quizAnswerMs = 35_000), savedProfile)
                sleep(500)
                if (!e.startQuiz(quizStore.questions.shuffled().take(1).map(quizStore::toQuiz))) {
                    mark("Offline in der Frage, dann antworten", target, false, "Quiz startet nicht")
                    return@run
                }
                awaitQuestion(1)
                cmd(target, "offline", 3_000)
                val ok = waitUntil(45_000) { answered(target) }
                waitUntil(10_000) { answeredAll() }
                mark("Offline in der Frage, dann antworten", target, ok, if (ok) "Antwort nach der Rückkehr angekommen" else "keine Antwort")
                names.filter { it != target }.forEach { mark("Offline in der Frage, dann antworten", it, null, "ein Spieler genügt") }
                reveal()
                e.nextQuestion()
                sleep(1_500)
                replayDone()
                enterTestMode()
            }

            // 14 Übergabe der Spielleitung an einen Spieler und zurück
            step(stepTitles[13])
            run {
                val target = names.firstOrNull { capable(it) && row(it) != null }
                if (target == null) { mark("Übergabe der Spielleitung", null, null); return@run }
                val e = engine()
                if (e.snapshot().phase != MasterEngine.Phase.READY) e.stopQuiz()
                sleep(500)
                cmd(target, "handback", text = myName.trim())
                sleep(500)
                handOver(target)
                val away = waitUntil(5_000) { master == null }
                val backAsMaster = away && waitUntil(150_000) { master != null }
                val allBack = backAsMaster && waitUntil(60_000) { names.all { row(it) != null } }
                mark("Übergabe der Spielleitung", null, backAsMaster, if (backAsMaster) "an $target und zurück" else if (away) "Leitung kam nicht zurück" else "Übergabe nicht gestartet")
                names.forEach { n -> mark("Nach der Übergabe wieder dabei", n, row(n) != null) }
                if (allBack || backAsMaster) {
                    master?.updateSettings(testSettings, savedProfile)
                    enterTestMode()
                }
            }

            // 15 Diagnosen einsammeln
            step(stepTitles[14])
            run {
                val before = diagReportsReceived
                requestDiagnoses()
                val connected = names.count { row(it) != null }
                val ok = waitUntil(30_000) { diagReportsReceived - before >= connected }
                mark("Diagnosen der Spieler", null, ok, "${diagReportsReceived - before} von $connected")
            }
        } catch (e: SelfTestCancelled) {
            results += SelfTest.Result("Intensivtest abgebrochen", false, "vom Spielleiter bei Schritt $stepNo")
        } catch (e: Exception) {
            results += SelfTest.Result("Intensivtest abgebrochen", false, "Schritt $stepNo: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            master?.let { e ->
                e.onRulesConfirmed = null
                e.endRulesConfirmation(false)
                if (e.snapshot().phase != MasterEngine.Phase.READY) e.stopQuiz()
                e.snapshot().players.filter { it.connected && it.id != LOCAL_PLAYER_ID }.forEach { link.send(it.id, Message.TestMode(false)) }
                if (savedKind != null && e.snapshot().kind != savedKind && e.snapshot().phase == MasterEngine.Phase.READY) e.chooseKind(savedKind)
                e.updateSettings(saved, savedProfile)
                e.testGame = savedTest
            }
            testGame = savedTest
            replayDone()
            val secs = (UptimeClock.now() - startedAt) / 1000
            Diagnostics.log("Intensivtest: Dauer $secs s")
            results += SelfTest.Result("Dauer des Intensivtests", true, "$secs s mit ${names.size} Spielern")
            intensiveMatrix = buildString {
                val width = (matrix.keys.maxOfOrNull { it.length } ?: 10) + 2
                appendLine("Funktion".padEnd(width) + names.joinToString("  ") + "  alle")
                for ((check, cells) in matrix) {
                    appendLine(check.padEnd(width) + names.joinToString("  ") { n -> (cells[n] ?: " ").padEnd(n.length) } + "  " + (cells["alle"] ?: ""))
                }
            }.trimEnd()
        }
        return results
    }

    private fun selfTestReport(results: List<SelfTest.Result>): String = buildString {
        appendLine("Buzzer – Selbsttest")
        appendLine("Ergebnis: ${SelfTest.summary(results)}")
        appendLine("Spielleiter: ${myName.trim()} (Build ${AppUpdate.ownBuild})")
        masterSnapshot?.players?.filter { it.connected && it.id != LOCAL_PLAYER_ID }?.takeIf { it.isNotEmpty() }?.let { rows ->
            appendLine("Spieler: " + rows.joinToString { "${it.name} (Build ${peerBuilds[it.id] ?: "alt"})" })
        }
        appendLine()
        intensiveMatrix?.let {
            appendLine("== Intensivtest: Funktion × Spieler (✓ ok, ✗ Fehler, – übersprungen) ==")
            appendLine(it)
            appendLine()
        }
        appendLine("== Prüfungen ==")
        appendLine(SelfTest.format(results))
        appendLine()
        append(Diagnostics.buildReport(getApplication(), null, true))
    }

    // Kleine Ablaufsteuerung ohne Zusatzbibliothek: alles läuft auf dem Main-Thread über den Scheduler.
    private fun launchTest(block: suspend () -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) { r ->
            r.exceptionOrNull()?.let {
                Diagnostics.log("Selbsttest: Fehler ${it.message}")
                selfTestRunning = false
                selfTestStep = null
            }
        })
    }

    private suspend fun sleep(ms: Long) = suspendCoroutine<Unit> { c -> scheduler.schedule(ms) { c.resume(Unit) } }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = UptimeClock.now() + timeoutMs
        while (UptimeClock.now() < end) {
            if (condition()) return true
            sleep(250)
        }
        return condition()
    }

    private suspend fun <T> inBackground(work: () -> T): T = suspendCoroutine { c ->
        kotlin.concurrent.thread(name = "buzzer-selftest", isDaemon = true) {
            val r = runCatching(work)
            android.os.Handler(android.os.Looper.getMainLooper()).post { c.resumeWith(r) }
        }
    }

    // ------------------------------------------------------------------ Spielregeln

    /** Regeln der laufenden Runde in Klartext – für Spieler beim Beitritt und zu Spielbeginn. */
    /** Spielleiter: aktuelle Spielregeln (Titel, Zeilen) für die Bestätigung. */
    fun currentRules() = master?.let(::rulesFor)

    private fun rulesFor(engine: MasterEngine): Pair<String, List<Pair<String, String>>>? {
        val s = engine.settings
        val kind = engine.snapshot().kind ?: return null
        fun sec(ms: Long) = String.format(Locale.GERMANY, "%.1f s", ms / 1000.0).replace(",0 s", " s")
        val profile = engine.profileName.takeIf { it.isNotEmpty() }?.let { " („$it“)" }.orEmpty()
        return if (kind == GameKind.QUIZ) {
            val categories = s.quizCategories.ifEmpty { quizStore.questions.map { it.category }.toSortedSet() }
            "Quiz$profile – so wird gespielt" to listOfNotNull(
                "Fragen" to "${s.quizCount}",
                "Kategorien" to categories.joinToString(),
                "Schwierigkeit" to s.quizLevels.sortedBy { it.ordinal }.joinToString { it.label }.ifEmpty { "alle Stufen" },
                "Ablauf" to "Erst die Frage, 3 s später die vier Antworten – bei jedem in anderer Reihenfolge.",
                "Antwortzeit" to sec(s.quizAnswerMs),
                "Wertung" to "${s.quizScoring.label}: ${s.quizScoring.description}",
                ("Countdown" to "Nach der ersten richtigen Antwort bleiben noch ${sec(s.quizCountdownMs)}").takeIf { s.quizScoring == QuizScoring.COUNTDOWN },
                "Falsche Antworten" to "kosten nie Punkte",
                "Challenge" to "Hältst du eine Auflösung für falsch, tippe „Challenge“.",
            )
        } else {
            "Buzzer$profile – so wird gespielt" to listOfNotNull(
                "Ablauf" to "Der Spielleiter stellt die Frage mündlich und gibt dann die Buzzer frei.",
                "Buzzern" to if (s.startMode == de.pabich.buzzer.core.StartMode.HOLD_RELEASE) {
                    "Finger auflegen und bei Gelb loslassen"
                } else {
                    "Bei Gelb so schnell wie möglich tippen"
                },
                "Frühstart" to "Wer zu früh drückt, ist für diese Runde raus.",
                "Reaktionszeit" to (s.reactionLimitMs?.let(::sec) ?: "ohne Limit"),
                "Antwortzeit" to (s.answerLimit?.let(::sec) ?: "ohne Limit"),
                "Wertung" to if (s.scoring == de.pabich.buzzer.core.ScoringMode.CORRECT_ANSWERS) {
                    "1 Punkt je richtige Antwort"
                } else {
                    "Die Restzeit bis zum Limit wird zu Punkten – schnell sein lohnt sich."
                },
                "Gleichstand" to "Liegen zwei fast gleichauf, entscheidet ein Sudden Death.",
            )
        }
    }

    private fun sendRules(engine: MasterEngine) {
        rulesFor(engine)?.let { (title, lines) -> engine.sendRules(title, lines) }
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
            if (build >= HEARTBEAT_BUILD && silence > MASTER_SILENCE_MS && UptimeClock.now() >= diagQuietUntil) {
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
        // Sync-Verfahren: Nach einem Abbruch übernimmt Nachfolger 1 nach 5 s, jeder weitere 5 s später – nur wenn
        // bis dahin niemand aus diesem Spiel zu finden ist (dann tritt man stattdessen bei, siehe checkSuccessor).
        val auto = s.sync && s.to.isEmpty() && s.master != myName.trim()
        val rank = de.pabich.buzzer.core.TakeoverRules.rank(s, myName.trim())
        // Intensivtest „offline“: erst nach der absichtlichen Funkpause zählen.
        val offlineLeft = (testOfflineUntil - UptimeClock.now()).coerceAtLeast(0)
        val autoAt = if (auto) UptimeClock.now() + offlineLeft + de.pabich.buzzer.core.TakeoverRules.waitMs(rank) else null
        takeover = Takeover(s, deadline, s.to.ifEmpty { null }, reason = reason, autoAt = autoAt, rank = rank)
        autoTakeoverTask?.cancel()
        autoTakeoverTask = autoAt?.let { at ->
            Diagnostics.log("Übernahme: Platz ${rank + 1} der Nachfolger – übernehme in ${(at - UptimeClock.now()) / 1000} s, falls sich niemand meldet")
            scheduler.schedule(at - UptimeClock.now()) {
                autoTakeoverTask = null
                val t = takeover ?: return@schedule
                if (master != null || t.taker != null || connectingTo != null || masterEndpoint != null) return@schedule
                Diagnostics.log("Übernahme: niemand gefunden – ich übernehme (Platz ${rank + 1})")
                becomeSuccessor(t.succession)
            }
        }
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

    /** Name für die Ankündigung; Nachfolger-Gruppen im Sync-Verfahren mit Spiel-ID und Leitungsnummer. */
    private fun advertName(engine: MasterEngine): String =
        if (engine.epoch > 0 && engine.syncGame) {
            PeerInfo.format(myName.trim(), AppUpdate.ownBuild, game = engine.gameId, epoch = engine.epoch)
        } else {
            PeerInfo.format(myName.trim(), AppUpdate.ownBuild)
        }

    /** Antwort auf „Leitung zurückgeben?“ ([give] = zurückgeben). */
    fun answerReturnOffer(give: Boolean) {
        val name = returnOffer ?: return
        returnOffer = null
        if (!give) {
            Diagnostics.log("Übernahme: Leitung bleibt hier ($name ist zurück)")
            return
        }
        Diagnostics.log("Übernahme: Leitung zurück an $name")
        handOver(name)
    }

    /** Rang in der Reihenfolge des Vorgängers (wer früher dabei war, hat Vorrang). */
    private fun rankIn(s: Message.Succession, name: String): Int =
        s.scores.indexOfFirst { it.first == name }.let { if (it < 0) Int.MAX_VALUE else it }

    /** Gefundene Gruppe: Ist das der Nachfolger, dem wir beitreten? */
    private fun checkSuccessor(group: Group) {
        val name = group.peer.name
        if (!group.peer.compatible || name == myName.trim()) return
        // Intensivtest: Der Spieler bleibt absichtlich ein paar Sekunden offline, dann meldet er sich zurück.
        val offline = testOfflineUntil - UptimeClock.now()
        if (offline > 0 && master == null) {
            scheduler.schedule(offline + 200) { groups.firstOrNull { it.endpointId == group.endpointId }?.let { checkSuccessor(it) } }
            return
        }
        val t = takeover
        if (t != null && master == null) {
            val s = t.succession
            val sync = s.sync && t.expected == null
            val fits = when {
                t.expected != null -> name == t.expected
                sync -> name != s.master && de.pabich.buzzer.core.TakeoverRules.joinWhileWaiting(s, group.peer)
                else -> name != s.master && s.scores.any { it.first == name }
            }
            // Der bisherige Spielleiter ist wieder da und noch niemand hat übernommen: zurück zu ihm.
            val predecessorBack = t.expected == null && t.taker == null && name == s.master &&
                (!sync || de.pabich.buzzer.core.TakeoverRules.joinWhileWaiting(s, group.peer))
            if ((!fits && !predecessorBack) || connectingTo != null) return
            Diagnostics.log(if (predecessorBack) "Übernahme: $name ist wieder da – Beitritt" else "Übernahme: $name leitet jetzt – Beitritt")
            // Wer gerade verbunden wird, bekommt noch etwas Zeit.
            val deadline = maxOf(t.deadline, UptimeClock.now() + TAKEOVER_CONNECT_MS)
            takeover = t.copy(taker = name, deadline = deadline)
            autoTakeoverTask?.cancel()
            autoTakeoverTask = null
            armTakeoverTimeout(deadline)
            join(group)
            return
        }
        // Sync-Verfahren: Der bisherige Spielleiter findet seinen Nachfolger (höhere Leitungsnummer) und tritt ihm
        // bei. Sind inzwischen Spieler zu ihm zurückgekehrt, schickt er sie mit – das Spiel bleibt eine Gruppe.
        val former = master
        if (former != null && former.syncGame && !former.handedOver &&
            de.pabich.buzzer.core.TakeoverRules.joinAsFormerMaster(former.gameId, former.epoch, group.peer)
        ) {
            Diagnostics.log("Übernahme: $name leitet inzwischen dieses Spiel – trete bei" + if (peerBuilds.isNotEmpty()) " (mit ${peerBuilds.size} Spielern)" else "")
            val s = former.redirectTo(name)
            updateTicking(null)
            scheduler.schedule(500) {
                link.stopAll()
                master = null
                masterSnapshot = null
                player = null
                playerView = null
                joinRequests.clear()
                peerBuilds.clear()
                takenOver = null
                startTakeover(s, "$name hat die Spielleitung übernommen, während du weg warst – du spielst dort weiter.")
            }
            return
        }
        // Zwei haben gleichzeitig übernommen: Der später Beigetretene gibt ab und tritt bei.
        val taken = takenOver ?: return
        val engine = master ?: return
        // Der Vorgänger ist wieder da: Ist noch niemand bei uns, geben wir ab und alle landen bei ihm.
        // Haben sich schon Spieler bei uns verbunden, bleibt das Spiel bei uns.
        // Nicht bei einer gewollten Übergabe an uns: Der Vorgänger sucht dann selbst uns – gäben wir ab, warteten
        // beide aufeinander und niemand leitete (Intensivtest 01.11.2026, 18:47).
        val handedToMe = taken.to.isNotEmpty() && taken.to == myName.trim()
        val predecessorBack: Boolean
        if (taken.sync) {
            // Sync-Verfahren: an eine neuere Leitung oder einen vorderen Rang abgeben. An den Vorgänger nur, wenn nach
            // [SYNC_LONELY_MS] immer noch niemand hier ist – dann spielen die anderen offenbar schon wieder bei ihm.
            val now = UptimeClock.now()
            if (now > takenOverUntil) return
            val lonely = name == taken.master && taken.to.isEmpty() && peerBuilds.isEmpty() && now - takenOverAt > SYNC_LONELY_MS
            predecessorBack = lonely
            if (!lonely && !de.pabich.buzzer.core.TakeoverRules.yieldTo(taken, engine.epoch, myName.trim(), group.peer)) return
        } else {
            predecessorBack = !handedToMe && name == taken.master && peerBuilds.isEmpty() && UptimeClock.now() < takenOverUntil
            if (!predecessorBack && (name == taken.master || UptimeClock.now() > takenOverUntil || rankIn(taken, name) >= rankIn(taken, myName.trim()))) return
        }
        Diagnostics.log(
            if (predecessorBack) "Übernahme: $name ist wieder da und noch niemand ist bei mir – gebe ab" else "Übernahme: $name hat ebenfalls übernommen und hat Vorrang – gebe ab",
        )
        val s = engine.succession(name)
        engine.leaveOpen()
        link.stopAll()
        master = null
        masterSnapshot = null
        player = null
        playerView = null
        takenOver = null
        startTakeover(s, if (predecessorBack) "$name ist wieder da – es geht bei ihm weiter." else "$name hat die Spielleitung zugleich übernommen.")
    }

    /** Platzhalter für eine schon gespielte Frage, die der Nachfolger nicht kennt. */
    private fun playedPlaceholder(id: String) = de.pabich.buzzer.core.QuizQuestion(
        id, "", de.pabich.buzzer.core.QuizLevel.MEDIUM, "(bereits gespielt)", listOf("–", "–", "–", "–"), 0, "", "",
    )

    /**
     * Gehört [peer] zu diesem Spiel und kommt zurück? Nach einer Übernahme die Mitspieler des
     * Vorgängers; sonst Spieler, deren Verbindung in diesem Spiel abgebrochen ist – oder deren alte
     * Verbindung hier noch hängt (der Spieler hat den Abbruch früher bemerkt als der Spielleiter).
     */
    private fun isReturning(peer: PeerInfo): Boolean {
        if (!peer.compatible || peer.updateOnly || peer.name == myName.trim()) return false
        val now = UptimeClock.now()
        val lost = peer.name in lostPlayers ||
            master?.snapshot()?.players.orEmpty().any { it.id != LOCAL_PLAYER_ID && it.name == peer.name }
        val taken = takenOver
        val fromPredecessor = taken != null && now < takenOverUntil &&
            (peer.name == taken.master || taken.scores.any { it.first == peer.name })
        return lost || fromPredecessor
    }

    /** Rückkehrer aus älteren Anfragen (vor Build 36 warteten sie bis zur Pause) ohne Rückfrage aufnehmen. */
    private fun acceptReturning() {
        if (master?.canAcceptJoin() != true) return
        for (request in joinRequests.filter { isReturning(it.peer) }) {
            Diagnostics.log("${request.peer.name} kommt in der Pause zurück – ohne Rückfrage aufgenommen")
            joinRequests.remove(request)
            link.accept(request.endpointId)
        }
    }

    /**
     * Neuer Spielleiter: Mitspieler aus dem Spiel des Vorgängers, die noch nicht wieder verbunden
     * sind (solange die Übernahme läuft). Der Vorgänger zählt nur bei einer freiwilligen Übergabe.
     */
    val takeoverMissing: List<String>
        get() {
            val taken = takenOver ?: return emptyList()
            if (UptimeClock.now() > takenOverUntil) return emptyList()
            val back = masterSnapshot?.players.orEmpty().filter { it.connected }.map { statsName(it.name) }.toSet()
            val expected = taken.scores.map { it.first } + listOfNotNull(taken.master.takeIf { taken.to.isNotEmpty() })
            return expected.distinct().filter { it != myName.trim() && it !in back }
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
        autoTakeoverTask?.cancel()
        autoTakeoverTask = null
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
        testHandBackTo?.let { back -> testHandBackTo = null; handBackWhenReady(back, UptimeClock.now() + 90_000) }
    }

    /**
     * Intensivtest: Leitung zurückgeben, sobald [name] wieder verbunden ist und auch alle anderen Spieler hier
     * angekommen sind (Punkt 120/121: vorher kamen Nachzügler mitten in die Rückgabe). Fehlt nach 60 s noch jemand,
     * geht die Leitung trotzdem zurück. Spätestens bis [until].
     */
    private fun handBackWhenReady(name: String, until: Long, backSince: Long = 0L) {
        scheduler.schedule(1_000) {
            val engine = master ?: return@schedule
            val back = engine.snapshot().players.any { it.connected && it.name == name && it.id in peerBuilds }
            val now = UptimeClock.now()
            val since = if (back) backSince.takeIf { it > 0 } ?: now else 0L
            val othersHere = takeoverMissing.none { it != name }
            when {
                back && !othersHere && now - since < 60_000 && now < until -> handBackWhenReady(name, until, since)
                back && engine.snapshot().phase == MasterEngine.Phase.READY -> scheduler.schedule(3_000) {
                    if (master === engine) {
                        Diagnostics.log("Intensivtest: Spielleitung zurück an $name")
                        handOver(name)
                    }
                }
                UptimeClock.now() < until -> handBackWhenReady(name, until)
                else -> {
                    Diagnostics.log("Intensivtest: $name ist nicht zurückgekommen – Leitung bleibt hier")
                    systemNote("Intensivtest: $name ist nicht zurückgekommen – Leitung bleibt hier")
                }
            }
        }
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
    /** Bis hierhin trennt niemand wegen Stille – Diagnosen werden übertragen (Punkt 115). */
    private var diagQuietUntil = 0L

    fun requestDiagnoses() {
        val ids = peerBuilds.keys.toList()
        if (ids.isEmpty()) return
        // Nacheinander anfragen: Senden alle gleichzeitig, verstopft der Funk in beide Richtungen und
        // Spieler werden wegen Stille getrennt (31.10.2026). Dazu eine Schonfrist ohne Stille-Trennung.
        diagQuietUntil = UptimeClock.now() + DIAG_STAGGER_MS * ids.size + DIAG_QUIET_MS
        ids.forEachIndexed { i, id ->
            scheduler.schedule(DIAG_STAGGER_MS * i) { if (master != null && id in peerBuilds) link.send(id, Message.DiagnoseRequest) }
        }
        // Ältere Builds kennen die Anfrage nicht und antworten nie.
        val old = ids.filter { (peerBuilds[it] ?: 0) < DIAG_REQUEST_BUILD }
            .mapNotNull { id -> masterSnapshot?.players?.firstOrNull { it.id == id }?.name?.let { "$it (Build ${peerBuilds[id] ?: "alt"})" } }
        Diagnostics.log("Diagnosen bei ${ids.size} Spielern angefragt" + if (old.isNotEmpty()) "; zu alt: ${old.joinToString()}" else "")
        status = "Diagnosen bei ${ids.size} Spielern angefragt – sie kommen, sobald sie zustimmen." +
            if (old.isNotEmpty()) " ${old.joinToString()} kann erst ab Build $DIAG_REQUEST_BUILD antworten – bitte dort „Diagnose senden“ tippen." else ""
    }

    /**
     * Gespeicherte Antwort auf „Diagnose an den Spielleiter?“: `true` senden, `false` nicht, `null`
     * fragen. Gilt dauerhaft, auch über neue Builds hinweg, bis sie in den Einstellungen zurückgesetzt wird.
     */
    private fun savedDiagnoseChoice(): Boolean? =
        if (diagPrefs.contains(KEY_SEND)) diagPrefs.getBoolean(KEY_SEND, false) else null

    /** Für die Einstellungen: gespeicherte Antwort in Worten, `null` = es wird gefragt. */
    val diagnoseChoiceText: String?
        get() = diagnoseChoiceVersion.let {
            when (savedDiagnoseChoice()) {
                null -> null
                false -> "nie senden"
                true -> if (diagPrefs.getBoolean(KEY_SYSTEM, false)) "immer senden (mit Systemdaten)" else "immer senden"
            }
        }
    private var diagnoseChoiceVersion by mutableIntStateOf(0)

    /** Gespeicherte Antwort löschen – beim nächsten Mal wird wieder gefragt. */
    fun resetDiagnoseChoice() {
        diagPrefs.edit().remove(KEY_SEND).remove(KEY_SYSTEM).remove(KEY_BUILD).apply()
        diagnoseChoiceVersion++
        Diagnostics.log("Diagnose-Anfragen: wieder fragen")
    }

    /** Antwort auf die Anfrage des Spielleiters; [remember] = nicht erneut fragen (dauerhaft). */
    fun answerDiagnoseRequest(send: Boolean, includeSystem: Boolean, remember: Boolean) {
        diagnoseRequest = false
        if (remember) {
            diagPrefs.edit().putBoolean(KEY_SEND, send).putBoolean(KEY_SYSTEM, includeSystem).apply()
            diagnoseChoiceVersion++
            val what = when {
                !send -> "nie senden"
                includeSystem -> "immer senden (mit Systemdaten)"
                else -> "immer senden"
            }
            Diagnostics.log("Diagnose-Anfragen: ab jetzt $what")
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
        val parts = DiagnoseTransfer.split(id, myName.trim().ifEmpty { "Spieler" }, AppUpdate.ownBuild, report)
        diagQuietUntil = maxOf(diagQuietUntil, UptimeClock.now() + DIAG_QUIET_MS)
        // Teile mit kleinem Abstand schicken, damit Herzschlag und Spiel dazwischen durchkommen.
        parts.forEachIndexed { i, part ->
            scheduler.schedule(DIAG_PART_GAP_MS * i) { if (masterEndpoint == endpoint) link.send(endpoint, part) }
        }
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

    /**
     * Spielleiter ohne GitHub-Zugang: alle gespeicherten Berichte (Selbsttest und Diagnosen) an einen Mitspieler mit
     * Zugang; gelöscht wird erst, wenn er sie im Repository abgelegt hat. Ohne solchen Mitspieler bleiben sie gespeichert.
     */
    private fun sendReportsToCourier() {
        val connected = masterSnapshot?.players.orEmpty().filter { it.connected }.map { it.id }.toSet()
        diagCouriers.retainAll(connected)
        val courier = diagCouriers.firstOrNull()
        if (courier == null || pendingReports.isEmpty()) {
            status = "Selbsttest-Bericht auf dem Gerät gespeichert (⋮ „Gespeicherte Diagnosen“) – ein Mitspieler mit GitHub-Zugang kann ihn übernehmen."
            return
        }
        // Gestaffelt wie bei den Diagnosen (Punkt 115), damit der Funk nicht verstopft.
        val chunks = pendingReports.toList().flatMap { r -> DiagnoseTransfer.split(r.id, r.from, r.build, r.text, saved = true) }
        chunks.forEachIndexed { i, chunk -> scheduler.schedule(i * DIAG_PART_GAP_MS) { link.send(courier, chunk) } }
        Diagnostics.log("${pendingReports.size} Bericht(e) an ${senderName(courier)} zum Hochladen gesendet")
        status = "${pendingReports.size} Bericht(e) gehen an ${senderName(courier)} – er legt sie in GitHub ab."
    }

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
        AppUpdate.onInstallResult = null
        GameService.stop(getApplication())
        // App weggewischt: Die Spieler dürfen übernehmen (Punkt 59), sobald eine Spielart läuft.
        val engine = master
        master = null // Danach eintreffende Trennungen nicht mehr als Spielleiter auswerten.
        engine?.let { if (it.snapshot().kind != null) it.leaveOpen() else it.close() }
        link.stopAll()
        sounds.release()
    }

    companion object {
        /** Stichworte, an denen eine Statusmeldung als Problem erkannt und als [System]-Meldung gezeigt wird. */
        private val PROBLEM_WORDS = listOf(
            "fehlgeschlagen", "fehler", "nicht erreichbar", "abgelehnt", "abgebrochen", "getrennt",
            "konnte nicht", "nicht zurück", "niemand",
        )

        /** Nearby-Endpunkt-IDs sind vierstellig, eine Verwechslung ist ausgeschlossen. */
        const val LOCAL_PLAYER_ID = "local-master"

        /** Zur Auswahl als Avatar. */
        val AVATARS = listOf(
            "🦊", "🐼", "🐸", "🐙", "🦄", "🐯", "🐨", "🐵", "🐧", "🦁", "🐻", "🐰",
            "🐢", "🐝", "🦉", "🐳", "🚀", "⭐", "👑", "🎩", "🤖", "👻", "🎸", "⚽",
            "🍕", "🌵", "🍄", "🔥", "🌈", "🎯", "🧠", "😎",
        )

        /** Zufällige Variante, aber nie zweimal hintereinander dieselbe (gemerkt auf dem Gerät). */
        fun nextVariant(context: Context, key: String, count: Int): Int {
            val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            val last = prefs.getInt(key, -1)
            val v = (0 until count).filter { it != last }.random()
            prefs.edit().putInt(key, v).apply()
            return v
        }

        /** Ab diesem Build pingt der Spielleiter in jeder Phase (Herzschlag). */
        const val HEARTBEAT_BUILD = 27

        /** Ab diesem Build spielen Spieler im Testmodus des Selbsttests mit. */
        const val SELFTEST_BUILD = 35

        /** Ab diesem Build fragt der Spielleiter ohne GitHub-Zugang die Mitspieler vor dem Selbsttest um Erlaubnis. */
        const val CONSENT_BUILD = 52
        const val CONSENT_TIMEOUT_MS = 60_000L

        /** Ab diesem Build bestätigen Spieler die Spielregeln mit OK (ältere werden nicht abgewartet). */
        const val RULES_CONFIRM_BUILD = 39

        /** Ab diesem Build kennen Spieler die Befehle des Intensivtests. */
        const val INTENSIVE_TEST_BUILD = 44

        /** Grobe Dauer des Intensivtests: fester Teil plus je Spieler (Sekunden). */
        const val INTENSIVE_BASE_S = 300

        /** Intensivtest „lange Fragen“: ab diesem Build melden Spieler, ob Frage und Antworten passen. */
        const val FIT_TEST_BUILD = 50
        /** So lange stehen die langen Fragen mit ihren Antworten, bevor die App antwortet, und danach die Auflösung. */
        const val LONG_VIEW_MS = 10_000L
        const val LONG_REVEAL_MS = 5_000L
        const val INTENSIVE_PER_PLAYER_S = 50

        /** Kurznachrichten des Intensivtests beginnen so – der Spieler quittiert sie. */
        const val TEST_CHAT_PREFIX = "[Test]"

        /** Ab diesem Build beantworten Spieler „Diagnosen abrufen“. */
        const val DIAG_REQUEST_BUILD = 27

        /** So lange darf der Spielleiter still sein, bevor die Verbindung als abgebrochen gilt. */
        const val MASTER_SILENCE_MS = 12_000L

        /** Diagnosen abrufen: Abstand zwischen den Anfragen, Schonfrist ohne Stille-Trennung, Abstand der Teile. */
        const val DIAG_STAGGER_MS = 8_000L
        const val DIAG_QUIET_MS = 45_000L
        const val DIAG_CHUNK_QUIET_MS = 20_000L
        const val DIAG_PART_GAP_MS = 300L

        private const val KEY_BUILD = "build"
        private const val KEY_SEND = "send"
        private const val KEY_SYSTEM = "system"

        /** Ohne Nachfolger endet das Spiel nach dieser Zeit (Punkt 59). */
        const val TAKEOVER_MS = 60_000L

        /** Ab diesem Build gilt das Sync-Verfahren der Übernahme (Punkt 118), wenn alle Mitspieler es haben. */
        const val SYNC_BUILD = 46

        /** Sync-Verfahren: So lange bleibt ein neuer Spielleiter ohne Spieler, bevor er dem Vorgänger beitritt. */
        const val SYNC_LONELY_MS = 20_000L

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
