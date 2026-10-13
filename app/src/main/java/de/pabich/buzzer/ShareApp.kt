package de.pabich.buzzer

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/** Gibt die installierte App an andere Handys weiter (Bluetooth, Quick Share, Messenger, QR-Code). */
object ShareApp {
    /** Fester Download-Link, immer der neueste Build. */
    const val DOWNLOAD_URL = "https://github.com/AxelPabich/hello-world/releases/download/buzzer-latest/buzzer.apk"

    private const val APK_TYPE = "application/vnd.android.package-archive"
    private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
    private const val BLUETOOTH_SEND = "com.android.bluetooth.opp.BluetoothOppLauncherActivity"

    /**
     * Kopiert die eigene APK in den Cache und übergibt sie an Android. Mit [viaBluetooth] öffnet sich
     * direkt das Bluetooth-Senden (Gerät wählen, ggf. wird Bluetooth eingeschaltet); wo es das nicht
     * gibt, erscheint die normale Teilen-Auswahl. Das Kopieren läuft im Hintergrund.
     */
    fun send(context: Context, viaBluetooth: Boolean, onError: (String) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread(name = "buzzer-share", isDaemon = true) {
            val intent = runCatching { buildIntent(app) }.getOrElse {
                Diagnostics.log("Weitergeben: APK nicht vorbereitet: ${it.message}")
                main.post { onError("Die Installationsdatei konnte nicht vorbereitet werden.") }
                return@thread
            }
            main.post {
                val activity = context
                try {
                    if (viaBluetooth) {
                        val direct = Intent(intent).setComponent(ComponentName(BLUETOOTH_PACKAGE, BLUETOOTH_SEND))
                        try {
                            activity.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            Diagnostics.log("Weitergeben: Bluetooth-Senden geöffnet")
                            return@post
                        } catch (e: ActivityNotFoundException) {
                            Diagnostics.log("Weitergeben: kein direktes Bluetooth-Senden, nutze Teilen-Auswahl")
                        } catch (e: SecurityException) {
                            Diagnostics.log("Weitergeben: Bluetooth-Senden nicht erlaubt, nutze Teilen-Auswahl")
                        }
                    }
                    val chooser = Intent.createChooser(intent, "Buzzer weitergeben").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(chooser)
                    Diagnostics.log("Weitergeben: Teilen-Auswahl geöffnet")
                } catch (e: Exception) {
                    Diagnostics.log("Weitergeben fehlgeschlagen: ${e.message}")
                    onError("Weitergeben ist auf diesem Gerät nicht möglich: ${e.message}")
                }
            }
        }
    }

    private fun buildIntent(context: Context): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "Buzzer-Build-${BuildConfig.VERSION_CODE}.apk")
        File(context.applicationInfo.sourceDir).copyTo(target, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", target)
        return Intent(Intent.ACTION_SEND).apply {
            type = APK_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Buzzer – Quiz-Buzzer-App")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
