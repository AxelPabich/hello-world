package de.pabich.buzzer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Rückmeldung des Paket-Installers: Bestätigung anzeigen lassen und jedes Ergebnis ins
 * Diagnose-Protokoll schreiben. Bei Erfolg beendet Android die alte Version – den Erfolg
 * protokolliert dann die neue Version beim Start („App aktualisiert“).
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Diagnostics.install(context.applicationContext)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Diagnostics.log("Update: Installer wartet auf Bestätigung")
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Diagnostics.log("Update: Bestätigungsdialog fehlt")
                    AppUpdate.onInstallResult?.invoke(false, "Der Installer hat keinen Bestätigungsdialog geliefert.")
                    return
                }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure {
                        Diagnostics.log("Update: Bestätigungsdialog nicht zu öffnen: ${it.message}")
                        AppUpdate.onInstallResult?.invoke(false, "Der Installationsdialog ließ sich nicht öffnen.")
                    }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Diagnostics.log("Update: Installation erfolgreich")
                AppUpdate.onInstallResult?.invoke(true, "")
            }
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "abgebrochen"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "blockiert"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Konflikt mit der installierten Version"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "nicht passend für dieses Gerät"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "ungültige Datei"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "zu wenig Speicher"
                    else -> "Fehler $status"
                }
                Diagnostics.log("Update: Installation fehlgeschlagen – $reason ${message.take(200)}".trim())
                AppUpdate.onInstallResult?.invoke(false, "Installation $reason.")
            }
        }
    }
}
