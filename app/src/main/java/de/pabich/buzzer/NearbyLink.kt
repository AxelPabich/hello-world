package de.pabich.buzzer

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import android.net.Uri
import de.pabich.buzzer.core.Message
import de.pabich.buzzer.core.MessageCodec
import java.io.File

/**
 * Dünne Hülle um Google Nearby Connections (Strategie P2P_STAR: Master in der Mitte).
 * Alle Callbacks kommen auf dem Main-Thread an.
 */
class NearbyLink(context: Context, private val listener: Listener) {
    interface Listener {
        fun onEndpointFound(endpointId: String, name: String)
        fun onEndpointLost(endpointId: String)

        /** Nur beim Master: ein Spieler möchte beitreten. */
        fun onConnectionRequest(endpointId: String, name: String, authCode: String)
        fun onConnected(endpointId: String, name: String)
        fun onConnectionFailed(endpointId: String, reason: String)
        fun onDisconnected(endpointId: String)
        fun onMessage(endpointId: String, message: Message)
        fun onError(text: String)

        /** Fortschritt einer Dateiübertragung (gesendet oder empfangen). */
        fun onTransferProgress(endpointId: String, payloadId: Long, bytes: Long, total: Long, incoming: Boolean)

        /** Datei vollständig empfangen: [uri] (ab Android 10) oder [file]. */
        fun onFileReceived(endpointId: String, payloadId: Long, uri: Uri?, file: File?)

        /** Gesendete Datei ist angekommen ([ok]) oder abgebrochen. */
        fun onFileSent(endpointId: String, payloadId: Long, ok: Boolean)
    }

    private val client = Nearby.getConnectionsClient(context)
    private val strategy = Strategy.P2P_STAR
    private val pendingNames = mutableMapOf<String, String>()

    /** Eingehende Dateien bis zum Abschluss der Übertragung. */
    private val incomingFiles = mutableMapOf<Long, Payload>()
    private val outgoingFiles = mutableSetOf<Long>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.FILE) {
                incomingFiles[payload.id] = payload
                return
            }
            val bytes = payload.asBytes() ?: return
            MessageCodec.decode(bytes)?.let { listener.onMessage(endpointId, it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val id = update.payloadId
            val incoming = incomingFiles[id]
            val outgoing = id in outgoingFiles
            if (incoming == null && !outgoing) return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    listener.onTransferProgress(endpointId, id, update.bytesTransferred, update.totalBytes, incoming != null)
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (incoming != null) {
                        incomingFiles.remove(id)
                        val f = incoming.asFile()
                        @Suppress("DEPRECATION")
                        val javaFile = f?.asJavaFile()
                        listener.onFileReceived(endpointId, id, f?.asUri(), javaFile)
                    } else {
                        outgoingFiles.remove(id)
                        listener.onFileSent(endpointId, id, true)
                    }
                }
                else -> {
                    if (incoming != null) {
                        incomingFiles.remove(id)
                        listener.onFileReceived(endpointId, id, null, null)
                    } else {
                        outgoingFiles.remove(id)
                        listener.onFileSent(endpointId, id, false)
                    }
                }
            }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            if (info.isIncomingConnection) {
                listener.onConnectionRequest(endpointId, info.endpointName, info.authenticationDigits)
            } else {
                // Der Spieler hat selbst angefragt; die Entscheidung trifft der Master.
                client.acceptConnection(endpointId, payloadCallback)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = pendingNames.remove(endpointId) ?: endpointId
            val status = result.status
            if (status.isSuccess) {
                listener.onConnected(endpointId, name)
            } else {
                listener.onConnectionFailed(endpointId, "${status.statusCode} ${status.statusMessage ?: ""}".trim())
            }
        }

        override fun onDisconnected(endpointId: String) = listener.onDisconnected(endpointId)
    }

    fun startAdvertising(groupName: String) {
        client.startAdvertising(
            groupName, SERVICE_ID, lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { listener.onError("Gruppe konnte nicht eröffnet werden: ${it.message}") }
    }

    fun startDiscovery() {
        client.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    listener.onEndpointFound(endpointId, info.endpointName)
                }

                override fun onEndpointLost(endpointId: String) = listener.onEndpointLost(endpointId)
            },
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { listener.onError("Suche konnte nicht gestartet werden: ${it.message}") }
    }

    fun stopDiscovery() = client.stopDiscovery()

    fun requestConnection(myName: String, endpointId: String) {
        client.requestConnection(myName, endpointId, lifecycleCallback)
            .addOnFailureListener {
                listener.onError("Verbindungsanfrage fehlgeschlagen: ${it.message}")
                listener.onConnectionFailed(endpointId, it.message ?: it.javaClass.simpleName)
            }
    }

    fun accept(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
    }

    fun reject(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    fun send(endpointId: String, message: Message) {
        client.sendPayload(endpointId, Payload.fromBytes(MessageCodec.encode(message)))
    }

    /**
     * Schickt eine Datei. [announce] erhält vorher die Payload-ID, damit der Empfänger die Datei
     * zuordnen kann (Nachricht vor der Datei senden).
     */
    fun sendFile(endpointId: String, file: File, announce: (Long) -> Unit) {
        val payload = Payload.fromFile(file)
        outgoingFiles += payload.id
        announce(payload.id)
        client.sendPayload(endpointId, payload).addOnFailureListener {
            outgoingFiles.remove(payload.id)
            listener.onFileSent(endpointId, payload.id, false)
        }
    }

    fun disconnect(endpointId: String) = client.disconnectFromEndpoint(endpointId)

    fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        pendingNames.clear()
        incomingFiles.clear()
        outgoingFiles.clear()
    }

    companion object {
        const val SERVICE_ID = "de.pabich.buzzer"
    }
}
