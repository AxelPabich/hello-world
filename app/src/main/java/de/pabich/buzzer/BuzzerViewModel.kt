package de.pabich.buzzer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import de.pabich.buzzer.core.MasterEngine
import de.pabich.buzzer.core.Message
import de.pabich.buzzer.core.PlayerEngine
import de.pabich.buzzer.core.Settings

enum class Screen { START, DISCOVER, MASTER, PLAYER }

data class Group(val endpointId: String, val name: String)

data class JoinRequest(val endpointId: String, val name: String, val authCode: String)

class BuzzerViewModel(app: Application) : AndroidViewModel(app), NearbyLink.Listener {
    var screen by mutableStateOf(Screen.START)
        private set
    var myName by mutableStateOf("")
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

    private val link = NearbyLink(app, this)
    private val scheduler = MainThreadScheduler()
    private var master: MasterEngine? = null
    private var player: PlayerEngine? = null
    private var masterEndpoint: String? = null

    init {
        if (Diagnostics.previousCrash != null) {
            status = "Die App wurde beim letzten Mal unerwartet beendet. Bitte „Diagnose senden“ antippen."
        }
    }

    // ------------------------------------------------------------------ Start

    fun openGroup() {
        val engine = MasterEngine(UptimeClock, scheduler) { to, message ->
            if (to == LOCAL_PLAYER_ID) {
                // Gleicher Weg wie über Funk, nur ohne Laufzeit.
                scheduler.schedule(0) { player?.onMessage(message) }
            } else {
                link.send(to, message)
            }
        }
        engine.onChanged = { masterSnapshot = it }
        master = engine
        masterSnapshot = engine.snapshot()
        engine.start()
        Diagnostics.log("Gruppe eröffnet")
        link.startAdvertising(myName.trim())
        screen = Screen.MASTER
    }

    fun searchGroups() {
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

    fun leave() {
        Diagnostics.log("Verlassen")
        link.stopAll()
        master?.stop()
        master = null
        player = null
        masterEndpoint = null
        masterSnapshot = null
        playerView = null
        connectingTo = null
        groups.clear()
        joinRequests.clear()
        screen = Screen.START
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
    fun nextRound() = master?.nextRound()
    fun newGame() = master?.newGame()

    fun updateSettings(settings: Settings) {
        val engine = master ?: return
        if (!engine.updateSettings(settings)) {
            status = "Einstellungen lassen sich nur zwischen den Runden ändern."
            return
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
        val engineForPlayer = PlayerEngine(UptimeClock, scheduler) { link.send(endpointId, it) }
        engineForPlayer.onViewChanged = { playerView = it }
        player = engineForPlayer
        playerView = engineForPlayer.view
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
        Diagnostics.log("Getrennt von $endpointId")
        val engine = master
        if (engine != null) {
            engine.removePlayer(endpointId)
        } else if (endpointId == masterEndpoint) {
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
        leave()
    }

    companion object {
        /** Nearby-Endpunkt-IDs sind vierstellig, eine Verwechslung ist ausgeschlossen. */
        const val LOCAL_PLAYER_ID = "local-master"
    }
}
