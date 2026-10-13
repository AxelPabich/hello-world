package de.pabich.buzzer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

/**
 * Legt Diagnoseberichte im privaten GitHub-Repository des Spielleiters ab. Das Fine-grained
 * Token liegt nur auf diesem Gerät, verschlüsselt mit einem Schlüssel aus dem Android-Keystore.
 */
class DiagnoseUpload(context: Context) {
    private val prefs = context.getSharedPreferences("diagnose-upload", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /** Ziel-Repository `Besitzer/Name`. */
    var repo: String
        get() = prefs.getString(KEY_REPO, DEFAULT_REPO) ?: DEFAULT_REPO
        set(value) {
            prefs.edit().putString(KEY_REPO, value.trim().removePrefix("https://github.com/").trim('/')).apply()
        }

    val hasToken: Boolean get() = prefs.contains(KEY_TOKEN)

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        Diagnostics.log("Diagnose-Upload: Token gespeichert (verschlüsselt)")
    }

    fun deleteToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_IV).apply()
        Diagnostics.log("Diagnose-Upload: Token gelöscht")
    }

    private fun token(): String? = runCatching {
        val data = Base64.decode(prefs.getString(KEY_TOKEN, null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.onFailure { Diagnostics.log("Diagnose-Upload: Token nicht lesbar: ${it.message}") }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /** Prüft Token und Repository; [onResult] mit `null` bei Erfolg, sonst Fehlertext. */
    fun check(onResult: (String?) -> Unit) {
        val token = token() ?: return onResult("Kein Token gespeichert.")
        val target = repo
        thread(name = "buzzer-github-check", isDaemon = true) {
            val error = runCatching {
                val conn = open("https://api.github.com/repos/$target", token, "GET")
                val code = conn.responseCode
                val body = runCatching { conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("")
                conn.disconnect()
                when {
                    code == 200 && "\"private\": true" !in body && "\"private\":true" !in body ->
                        "Das Repository $target ist öffentlich – bitte als „Private“ anlegen."
                    code == 200 -> null
                    else -> describe(code, target)
                }
            }.getOrElse { "GitHub ist nicht erreichbar (Internet?)." }
            main.post { onResult(error) }
        }
    }

    /** Lädt [text] nach [path] hoch; [onResult] mit `null` bei Erfolg, sonst Fehlertext. */
    fun upload(path: String, text: String, message: String, onResult: (String?) -> Unit) {
        val token = token() ?: return onResult("Kein Token gespeichert.")
        val target = repo
        thread(name = "buzzer-github-upload", isDaemon = true) {
            val error = runCatching {
                val conn = open("https://api.github.com/repos/$target/contents/$path", token, "PUT")
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val content = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val json = """{"message":${quote(message)},"content":"$content"}"""
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200 || code == 201) null else describe(code, target)
            }.getOrElse { "GitHub ist nicht erreichbar (Internet?)." }
            Diagnostics.log("Diagnose-Upload $path: ${error ?: "ok"}")
            main.post { onResult(error) }
        }
    }

    private fun describe(code: Int, target: String) = when (code) {
        401 -> "Das Token ist ungültig oder abgelaufen."
        403 -> "Das Token darf nicht in $target schreiben (Contents: Read and write?)."
        404 -> "Repository $target nicht gefunden – oder das Token ist nicht dafür freigegeben."
        422 -> "Die Datei gibt es schon."
        else -> "GitHub meldet Fehler $code."
    }

    private fun open(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Buzzer-App/${BuildConfig.VERSION_CODE}")
        }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> if (c < ' ') append(' ') else append(c)
            }
        }
        append('"')
    }

    companion object {
        const val DEFAULT_REPO = "AxelPabich/buzzer-diagnose"
        const val NEW_REPO_URL = "https://github.com/new"
        const val NEW_TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
        const val TOKENS_URL = "https://github.com/settings/personal-access-tokens"
        private const val KEY_REPO = "repo"
        private const val KEY_TOKEN = "token"
        private const val KEY_IV = "iv"
        private const val KEY_ALIAS = "buzzer-github-token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
