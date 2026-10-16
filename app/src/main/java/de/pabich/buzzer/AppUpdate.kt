package de.pabich.buzzer

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Neuere Versionen der App: eigene APK bereitstellen, empfangene oder geladene APK prüfen und
 * installieren, neueste Version aus dem Repository laden.
 */
object AppUpdate {
    /** Release-Seite des Repositorys: listet alle Builds als `buzzer-build-N.apk`. */
    private const val RELEASE_API = "https://api.github.com/repos/AxelPabich/hello-world/releases/tags/buzzer-latest"
    private const val APK_TYPE = "application/vnd.android.package-archive"
    private val main = Handler(Looper.getMainLooper())

    val ownBuild: Int get() = BuildConfig.VERSION_CODE

    /** Die installierte APK – kann direkt verschickt werden. */
    fun ownApk(context: Context): File = File(context.applicationInfo.sourceDir)

    fun updatesDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Ergebnis der Prüfung einer APK vor der Installation. */
    data class Check(val ok: Boolean, val build: Int, val reason: String)

    /** Nur echte Buzzer-Updates: gleicher Paketname, gleiche Signatur, höhere Build-Nummer. */
    fun check(context: Context, apk: File): Check {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: return Check(false, 0, "Die Datei ist keine gültige App.")
        val build = versionOf(archive)
        if (archive.packageName != context.packageName) return Check(false, build, "Die Datei ist nicht die Buzzer-App.")
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
            ?: return Check(false, build, "Installierte App nicht lesbar.")
        val own = signatures(installed)
        // Manche Geräte liefern für Archive keine neuen Signaturdaten: dann über die alte Schnittstelle.
        @Suppress("DEPRECATION")
        val theirs = signatures(archive).ifEmpty {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.let { signatures(it) }.orEmpty()
        }
        if (own.isEmpty() || theirs.isEmpty() || own != theirs) return Check(false, build, "Die Signatur passt nicht – kein offizieller Buzzer-Build.")
        if (build <= ownBuild) return Check(false, build, "Build $build ist nicht neuer als deine Version (Build $ownBuild).")
        return Check(true, build, "")
    }

    @Suppress("DEPRECATION")
    private fun versionOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        val sigs = modern?.takeIf { it.isNotEmpty() } ?: info.signatures ?: emptyArray()
        return sigs.map { it.toCharsString() }.toSet()
    }

    /** Darf die App Installationen anstoßen? Sonst einmalig die Erlaubnis in den Einstellungen geben. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Öffnet die Einstellung „Unbekannte Apps installieren“ für Buzzer. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Diagnostics.log("Update: Einstellung für Installationen nicht zu öffnen: ${it.message}") }
    }

    /** Ergebnis einer Installation (`ok`, Hinweis) – für die Anzeige in der App. */
    var onInstallResult: ((Boolean, String) -> Unit)? = null

    /** Zuletzt an den Paket-Installer übergebene APK – für den Ausweg über den Installer-Dialog. */
    @Volatile
    private var pendingApk: File? = null

    /**
     * Der Paket-Installer hat abgelehnt (z. B. „Self update is blocked“ oder Prüfung fehlgeschlagen):
     * einmal den bisherigen Installer-Dialog versuchen. Liefert `true`, wenn er geöffnet wurde.
     */
    fun fallbackAfterFailure(context: Context): Boolean {
        val apk = pendingApk ?: return false
        pendingApk = null
        if (!apk.exists()) return false
        Diagnostics.log("Update: Ausweg über den Installer-Dialog")
        return installViaIntent(context.applicationContext, apk)
    }

    /**
     * Startet die Installation über den Paket-Installer von Android (Sitzung): Die APK wird im
     * Hintergrund übergeben, Android fragt den Nutzer, jedes Ergebnis landet im Protokoll
     * ([InstallResultReceiver]). Klappt das nicht, bleibt der alte Weg über den Installer-Dialog.
     */
    fun install(context: Context, apk: File, onStarted: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        // Erst der klassische Installer-Dialog: Der Paket-Installer (Sitzung) wurde auf mehreren Geräten
        // abgelehnt („Self update is blocked“, „Package Verification Result“), der Dialog klappte immer.
        Diagnostics.log("Update: Installation von ${apk.name} (${apk.length() / 1024} KB) über den Installer-Dialog")
        if (installViaIntent(app, apk)) {
            onStarted(true)
            return
        }
        Diagnostics.log("Update: Installer-Dialog nicht verfügbar – Paket-Installer")
        thread(name = "buzzer-install", isDaemon = true) {
            pendingApk = apk
            val ok = runCatching { installSession(app, apk) }
                .onFailure { Diagnostics.log("Update: Paket-Installer nicht nutzbar (${it.javaClass.simpleName}: ${it.message})") }
                .isSuccess
            pendingApk = null // Der Dialog hat schon nicht geklappt – kein zweiter Versuch damit.
            main.post { onStarted(ok) }
        }
    }

    private fun installSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("buzzer.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                // Der Installer ergänzt Status und Bestätigungsdialog – deshalb veränderbar.
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
                val pending = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
                )
                session.commit(pending.intentSender)
            }
            Diagnostics.log("Update: Sitzung $id übergeben – warte auf den Installer")
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Alter Weg: Installer-Dialog von Android mit der Datei öffnen. */
    private fun installViaIntent(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Diagnostics.log("Update: Installer-Dialog geöffnet (${apk.name})")
            true
        } catch (e: ActivityNotFoundException) {
            Diagnostics.log("Update: kein Installer gefunden")
            false
        }
    }

    /** Kopiert eine über Nearby empfangene Datei in den Update-Ordner. */
    fun takeReceived(context: Context, uri: Uri?, file: File?, build: Int): File? {
        val target = File(updatesDir(context), "buzzer-build-$build.apk")
        return runCatching {
            when {
                uri != null -> {
                    context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
                file != null -> {
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
                else -> return null
            }
            target
        }.onFailure { Diagnostics.log("Update: empfangene Datei nicht übernommen: ${it.message}") }.getOrNull()
    }

    /** Neueste Build-Nummer auf der Release-Seite (im Hintergrund), `null` bei Fehlern. */
    fun latestBuild(onResult: (Int?) -> Unit) {
        thread(name = "buzzer-latest", isDaemon = true) {
            val build = runCatching {
                val json = fetchText(RELEASE_API)
                Regex("""buzzer-build-(\d+)\.apk""").findAll(json).map { it.groupValues[1].toInt() }.maxOrNull()
            }.onFailure { Diagnostics.log("Update: Release-Seite nicht erreichbar: ${it.message}") }.getOrNull()
            main.post { onResult(build) }
        }
    }

    /** Lädt die neueste APK aus dem Repository; [onProgress] 0..1, [onDone] mit Datei oder `null`. */
    fun download(context: Context, onProgress: (Float) -> Unit, onDone: (File?) -> Unit) {
        val target = File(updatesDir(context), "buzzer-download.apk")
        thread(name = "buzzer-download", isDaemon = true) {
            val ok = runCatching {
                val conn = open(ShareApp.DOWNLOAD_URL)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPost = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastPost > 256 * 1024) {
                                lastPost = done
                                val f = done.toFloat() / total
                                main.post { onProgress(f) }
                            }
                        }
                    }
                }
                true
            }.onFailure { Diagnostics.log("Update: Download fehlgeschlagen: ${it.message}") }.getOrDefault(false)
            main.post { onDone(if (ok) target else null) }
        }
    }

    fun fetchText(url: String): String = open(url).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        // Weiterleitungen (GitHub → Download-Server) selbst folgen, auch über Hosts hinweg.
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Buzzer-App/${BuildConfig.VERSION_CODE}")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Weiterleitung ohne Ziel")
                current = URL(current, location)
                conn.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn
        }
        throw IllegalStateException("Zu viele Weiterleitungen")
    }
}
