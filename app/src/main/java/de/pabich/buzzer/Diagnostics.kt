package de.pabich.buzzer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sammelt die letzten Ereignisse und Fehler und baut daraus einen Diagnosebericht zum Teilen.
 * Das Protokoll liegt in einer Datei und übersteht Neustarts. Systeminformationen kommen nur
 * hinein, wenn der Nutzer das ausdrücklich wählt.
 */
object Diagnostics {
    private const val MAX_LINES = 500
    private const val CRASH_FILE = "last_crash.txt"
    private const val LOG_FILE = "diagnose.log"
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("dd.MM. HH:mm:ss.SSS", Locale.GERMANY)
    private var logFile: File? = null
    private var appendsSinceTrim = 0

    /** Absturz-Text vom letzten Start, falls die App unerwartet beendet wurde. */
    var previousCrash: String? = null
        private set

    @Synchronized
    fun log(text: String) {
        val line = "${time.format(Date())}  $text"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        val file = logFile ?: return
        runCatching {
            if (++appendsSinceTrim >= MAX_LINES / 2) {
                // Datei gelegentlich auf die letzten Zeilen kürzen.
                file.writeText(lines.joinToString("\n", postfix = "\n"))
                appendsSinceTrim = 0
            } else {
                file.appendText(line + "\n")
            }
        }
    }

    /** Löscht Protokoll und Absturzbericht (beim regulären Beenden auf Wunsch). */
    @Synchronized
    fun clear(context: Context) {
        lines.clear()
        previousCrash = null
        File(context.filesDir, LOG_FILE).delete()
        File(context.filesDir, CRASH_FILE).delete()
    }

    /** Lädt das gespeicherte Protokoll und schreibt Abstürze in eine Datei für den nächsten Start. */
    @Synchronized
    fun install(context: Context) {
        if (logFile == null) {
            val log = File(context.filesDir, LOG_FILE)
            logFile = log
            runCatching { if (log.exists()) log.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) } }
            log("--- App gestartet: ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) ---")
        }
        val file = File(context.filesDir, CRASH_FILE)
        if (file.exists()) {
            previousCrash = file.readText()
            file.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(file, previous))
    }

    private class CrashHandler(
        private val file: File,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                file.writeText("Absturz in Thread ${thread.name}:\n$trace\nLetzte Ereignisse:\n${snapshot()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    private fun snapshot(): String = lines.joinToString("\n")

    fun buildReport(context: Context, currentError: String?, includeSystem: Boolean): String = buildString {
        appendLine("Buzzer – Diagnose")
        appendLine("Erstellt: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("== Fehler ==")
        appendLine(currentError ?: "(keine aktuelle Fehlermeldung)")
        previousCrash?.let {
            appendLine()
            appendLine("== Absturz beim letzten Start ==")
            appendLine(it)
        }
        appendLine()
        appendLine("== Letzte Ereignisse ==")
        appendLine(snapshot().ifEmpty { "(keine)" })
        if (includeSystem) {
            appendLine()
            appendLine("== System ==")
            append(systemInfo(context))
        }
    }

    private fun systemInfo(context: Context): String = buildString {
        appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), Patch ${Build.VERSION.SECURITY_PATCH}")
        val playServices = runCatching {
            context.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
        }.getOrNull()
        appendLine("Google Play-Dienste: ${playServices ?: "nicht gefunden"}")
        val location = context.getSystemService(LocationManager::class.java)
        val locationOn = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled?.toString() ?: "?" else "?"
        appendLine("Standort eingeschaltet: $locationOn")
        appendLine("Berechtigungen:")
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        for (permission in permissions) {
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            appendLine("  ${permission.substringAfterLast('.')}: ${if (granted) "erteilt" else "FEHLT"}")
        }
    }

    /** Öffnet die Teilen-Auswahl von Android (Mail, Messenger, Notizen …). */
    fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Buzzer – Diagnose (Build ${BuildConfig.VERSION_CODE})")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(send, "Diagnose teilen"))
    }
}
