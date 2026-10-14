package de.pabich.buzzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Hält die Nearby-Verbindung am Leben, solange eine Gruppe offen ist: Als Vordergrund-Dienst
 * (mit Benachrichtigung „Buzzer-Runde läuft“) drosselt Android die App im Hintergrund nicht und
 * trennt die Verbindung nicht nach einigen Minuten.
 */
class GameService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Buzzer-Runde läuft"
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Diagnostics.log("Hintergrund-Dienst läuft: $text")
        } catch (e: Exception) {
            // Ohne Dienst läuft das Spiel weiter, nur im Hintergrund ohne Schutz.
            Diagnostics.log("Hintergrund-Dienst nicht gestartet: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Diagnostics.log("Hintergrund-Dienst beendet")
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Laufende Runde", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Hält die Verbindung, solange eine Buzzer-Runde offen ist."
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Buzzer")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "runde"
        private const val NOTIFICATION_ID = 7
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, GameService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Diagnostics.log("Hintergrund-Dienst: Start abgelehnt: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GameService::class.java)) }
        }
    }
}
