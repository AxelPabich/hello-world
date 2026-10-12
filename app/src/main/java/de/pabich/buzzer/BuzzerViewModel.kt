package de.pabich.buzzer

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import de.pabich.buzzer.core.ALL_ADDRESS
import de.pabich.buzzer.core.Cancellable
import de.pabich.buzzer.core.ConfigFormat
import de.pabich.buzzer.core.MasterEngine
import de.pabich.buzzer.core.Message
import de.pabich.buzzer.core.PlayerEngine
import de.pabich.buzzer.core.Settings
import de.pabich.buzzer.core.SettingsCodec

enum class Screen { START, DISCOVER, MASTER, PLAYER }

data class Group(val endpointId: String, val name: String)

data class JoinRequest(val endpointId: String, val name: String, val authCode: String)

/** Ein auffliegendes Emoticon. */
data class FlyingEmote(val id: Long, val from: String, val emoji: String)

data class ChatLine(val from: String, val text: String, val toAll: Boolean, val outgoing: Boolean)

/** Ein empfangenes Profil, das der Spieler ansehen und speichern kann. */
data class ReceivedProfile(val name: String, val values: Map<String, String>)

/** Ein geladenes Profil aus einem älteren Build: diese Felder sind neu und sollen geprüft werden. */
data class ProfileUpgrade(val name: String, val missingLabels: List<String>)

class BuzzerViewModel(app: Application) : AndroidViewModel(app), NearbyLink.Listener {
    private val store = ConfigStore(app)

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
    val emotes = mutableStateListOf<FlyingEmote>()
    val chat = mutableStateListOf<ChatLine>()
    var receivedProfile by mutableStateOf<ReceivedProfile?>(null)
    var profiles by mutableStateOf(store.config.profiles)
        private set
    var profileUpgrade by mutableStateOf<ProfileUpgrade?>(null)
        private set
    var importContent by mutableStateOf<ConfigFormat.Content?>(null)

    private val link = NearbyLink(app, this)
    private val scheduler = MainThreadScheduler()
    private var master: MasterEngine? = null
    private var player: PlayerEngine? = null
    private var masterEndpoint: String? = null
    private var pendingStop: Cancellable? = null
    private var emoteSeq = 0L

    init {
        if (Diagnostics.previousCrash != null) {
            status = "Die App wurde beim letzten Mal unerwartet beendet. Bitte „Diagnose senden“ antippen."
        }
    }

    val isMaster: Boolean get() = master != null

    // ------------------------------------------------------------------ Start

    fun openGroup() {
        finishPendingStop()
        store.setPlayerName(myName.trim())
        val engine = MasterEngine(UptimeClock, scheduler, log = Diagnostics::log) { to, message ->
            if (to == LOCAL_PLAYER_ID) {
                // Gleicher Weg wie über Funk, nur ohne Laufzeit.
                scheduler.schedule(0) { player?.onMessage(message) }
            } else {
                link.send(to, message)
            }
        }
        engine.masterName = myName.trim()
        engine.onChanged = { masterSnapshot = it }
        engine.onEmote = { from, emoji -> showEmote(from, emoji) }
        engine.onChat = { from, text, toAll -> addChat(ChatLine(from, text, toAll, outgoing = false)) }
        master = engine
        engine.updateSettings(store.config.lastSettings, store.config.lastProfileName)
        syncLocalPlayer(engine)
        masterSnapshot = engine.snapshot()
        engine.start()
        Diagnostics.log("Gruppe eröffnet")
        link.startAdvertising(myName.trim())
        screen = Screen.MASTER
    }

    fun searchGroups() {
        finishPendingStop()
        store.setPlayerName(myName.trim())
        groups.clear()
        connectingTo = null
        Diagnostics.log("Suche gestartet")
        link.startDiscovery()
        screen = Screen.DISCOVER
    }

    fun join(group: Group) {
        connectingTo = group.endpointId
        Diagnostics.log("Beitritt angefragt bei ${group.endpointId}")
        link.requestConnection(myName.trim(), group.endpointId)
    }

    /** Reguläres Beenden; auf Wunsch werden danach alle Diagnosedaten gelöscht. */
    fun leave(deleteDiagnostics: Boolean = false) {
        Diagnostics.log("Verlassen")
        val engine = master
        if (engine != null) {
            // Erst verabschieden, dann trennen – sonst kommt die Nachricht nicht mehr an.
            engine.close()
            pendingStop = scheduler.schedule(500) { pendingStop = null; link.stopAll() }
        } else {
            link.stopAll()
        }
        master = null
        player = null
        masterEndpoint = null
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
        if (master?.canAcceptJoin() == true) link.accept(request.endpointId) else link.reject(request.endpointId)
    }

    fun rejectJoin(request: JoinRequest) {
        joinRequests.remove(request)
        link.reject(request.endpointId)
    }

    fun release() = master?.release()
    fun markCorrect() = master?.markCorrect()
    fun markWrong() = master?.markWrong()
    fun proceed() = master?.proceed()
    fun nextRound() = master?.nextRound()
    fun newGame() = master?.newGame()

    fun updateSettings(settings: Settings, profileName: String = masterSnapshot?.profileName.orEmpty()) {
        val engine = master ?: return
        if (!engine.updateSettings(settings, profileName)) {
            status = "Einstellungen lassen sich nur zwischen den Runden ändern."
            return
        }
        store.setLastSettings(engine.settings, profileName)
        // Ein Profil aus einem älteren Build ist jetzt vollständig geprüft: ergänzt speichern.
        profileUpgrade?.takeIf { it.name == profileName }?.let {
            store.saveProfile(it.name, engine.settings)
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
            local.onViewChanged = { playerView = it }
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

    fun shareProfile() = master?.shareProfile()

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

    fun changeReceiveEmojis(receive: Boolean) {
        receiveEmojis = receive
        store.setReceiveEmojis(receive)
        if (master == null) player?.setReceiveEmojis(receive)
        if (!receive) emotes.clear()
    }

    private fun showEmote(from: String, emoji: String) {
        if (!receiveEmojis) return
        emotes += FlyingEmote(++emoteSeq, from, emoji)
    }

    fun emoteDone(id: Long) {
        emotes.removeAll { it.id == id }
    }

    /** Empfänger: Spieler-ID, [ALL_ADDRESS] oder (für Spieler) die Adresse des Spielleiters. */
    fun sendChat(to: String, toName: String, text: String) {
        val clean = text.trim().ifEmpty { return }
        master?.sendChat(to, clean) ?: player?.sendChat(to, clean)
        addChat(ChatLine(if (to == ALL_ADDRESS) "Du → alle" else "Du → $toName", clean, to == ALL_ADDRESS, outgoing = true))
    }

    private fun addChat(line: ChatLine) {
        chat += line
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
        Diagnostics.log("Gruppe gefunden: $endpointId")
        if (groups.none { it.endpointId == endpointId }) groups += Group(endpointId, name)
    }

    override fun onEndpointLost(endpointId: String) {
        groups.removeAll { it.endpointId == endpointId }
    }

    override fun onConnectionRequest(endpointId: String, name: String, authCode: String) {
        Diagnostics.log("Beitrittsanfrage von $endpointId")
        if (master?.canAcceptJoin() == true) {
            joinRequests += JoinRequest(endpointId, name, authCode)
        } else {
            // Beitritt nur zwischen zwei Runden.
            link.reject(endpointId)
        }
    }

    override fun onConnected(endpointId: String, name: String) {
        Diagnostics.log("Verbunden mit $endpointId")
        val engine = master
        if (engine != null) {
            engine.addPlayer(endpointId, name)
            return
        }
        masterEndpoint = endpointId
        connectingTo = null
        link.stopDiscovery()
        val engineForPlayer = PlayerEngine(UptimeClock, scheduler, log = Diagnostics::log) { link.send(endpointId, it) }
        engineForPlayer.onViewChanged = { playerView = it }
        engineForPlayer.onEmote = { from, emoji -> showEmote(from, emoji) }
        engineForPlayer.onChat = { from, text, toAll -> addChat(ChatLine(from, text, toAll, outgoing = false)) }
        engineForPlayer.onProfile = { profileName, values -> receivedProfile = ReceivedProfile(profileName, values) }
        engineForPlayer.onBye = {
            leave()
            status = "Der Spielleiter hat die Gruppe geschlossen."
        }
        player = engineForPlayer
        playerView = engineForPlayer.view
        engineForPlayer.setReceiveEmojis(receiveEmojis)
        screen = Screen.PLAYER
    }

    override fun onConnectionFailed(endpointId: String, reason: String) {
        Diagnostics.log("Verbindung mit $endpointId fehlgeschlagen: $reason")
        if (master == null && connectingTo == endpointId) {
            connectingTo = null
            status = "Der Beitritt wurde abgelehnt oder ist fehlgeschlagen."
        }
    }

    override fun onDisconnected(endpointId: String) {
        val engine = master
        if (engine != null) {
            Diagnostics.log("Getrennt von $endpointId")
            engine.removePlayer(endpointId)
        } else if (endpointId == masterEndpoint) {
            val silence = player?.lastMessageAt?.let { UptimeClock.now() - it }
            Diagnostics.log("Verbindung zum Spielleiter abgebrochen; letzte Nachricht vor ${silence ?: "?"} ms")
            leave()
            status = "Die Verbindung zum Spielleiter ist abgebrochen."
        }
    }

    override fun onMessage(endpointId: String, message: Message) {
        val engine = master
        if (engine != null) {
            engine.onMessage(endpointId, message)
        } else if (endpointId == masterEndpoint) {
            player?.onMessage(message)
        }
    }

    override fun onError(text: String) {
        status = text
    }

    override fun onCleared() {
        master?.close()
        link.stopAll()
    }

    companion object {
        /** Nearby-Endpunkt-IDs sind vierstellig, eine Verwechslung ist ausgeschlossen. */
        const val LOCAL_PLAYER_ID = "local-master"
    }
}

/** Kurzbeschreibung eines Profils für die Ansicht beim Empfänger. */
fun describeProfile(values: Map<String, String>): List<Pair<String, String>> {
    val s = SettingsCodec.fromMap(values).settings
    fun sec(ms: Long) = String.format(java.util.Locale.GERMANY, "%.1f s", ms / 1000.0)
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
    )
}
