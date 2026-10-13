package de.pabich.buzzer

import android.content.Context
import android.net.Uri
import de.pabich.buzzer.core.AppConfig
import de.pabich.buzzer.core.ConfigFormat
import de.pabich.buzzer.core.Settings
import de.pabich.buzzer.core.SettingsCodec
import de.pabich.buzzer.core.StoredProfile
import java.io.File

/**
 * Lokale Config-Datei `buzzer.conf`. Beim Laden ergänzt sie fehlende Felder mit Vorgaben und
 * schreibt sich sofort neu – so passt sie sich jeder neuen Installation an. Profile bleiben
 * dabei unverändert, damit neue Felder beim ersten Laden des Profils abgefragt werden können.
 */
class ConfigStore(private val context: Context) {
    private val file = File(context.filesDir, "buzzer.conf")

    var config: AppConfig
        private set

    init {
        config = runCatching { file.inputStream().use { ConfigFormat.readConfig(it) } }
            .onFailure { if (file.exists()) Diagnostics.log("Config nicht lesbar: ${it.message}") }
            .getOrDefault(AppConfig())
        // Sofort neu schreiben: ergänzt Felder, die ein neuer Build mitbringt.
        save()
    }

    private fun save() {
        runCatching { file.outputStream().use { ConfigFormat.write(config, it) } }
            .onFailure { Diagnostics.log("Config nicht speicherbar: ${it.message}") }
    }

    private fun update(change: (AppConfig) -> AppConfig) {
        config = change(config)
        save()
    }

    fun setPlayerName(name: String) = update { it.copy(playerName = name) }

    fun setReceiveEmojis(receive: Boolean) = update { it.copy(receiveEmojis = receive) }

    fun setSoundOn(on: Boolean) = update { it.copy(soundOn = on) }

    fun setDesign(id: String) = update { it.copy(design = id) }

    fun setLastSettings(settings: Settings, profileName: String) =
        update { it.copy(lastSettings = settings, lastProfileName = profileName) }

    // ------------------------------------------------------------------ Profile

    fun profile(name: String): StoredProfile? = config.profiles.firstOrNull { it.name == name }

    /** Speichert (oder ersetzt) ein Profil mit allen aktuellen Feldern. */
    fun saveProfile(name: String, settings: Settings) = update { c ->
        val stored = StoredProfile(name, SettingsCodec.toMap(settings))
        val existing = c.profiles.indexOfFirst { it.name == name }
        c.copy(profiles = if (existing >= 0) c.profiles.toMutableList().also { it[existing] = stored } else c.profiles + stored)
    }

    fun saveProfileValues(name: String, values: Map<String, String>) =
        saveProfile(name, SettingsCodec.fromMap(values).settings)

    fun deleteProfile(name: String) = update { c -> c.copy(profiles = c.profiles.filter { it.name != name }) }

    fun renameProfile(old: String, new: String) = update { c ->
        c.copy(
            profiles = c.profiles.map { if (it.name == old) it.copy(name = new) else it },
            lastProfileName = if (c.lastProfileName == old) new else c.lastProfileName,
        )
    }

    /** Freier Name: „Name“, sonst „Name (2)“, „Name (3)“ … */
    fun freeProfileName(base: String): String {
        val names = config.profiles.map { it.name }.toSet()
        if (base !in names) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in names }
    }

    // ------------------------------------------------------------------ Export / Import (.buzzconf)

    fun export(uri: Uri, parts: ConfigFormat.Parts) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { ConfigFormat.write(config, it, parts) }
        Diagnostics.log("Export: Name=${parts.name}, Einstellungen=${parts.settings}, Profile=${parts.profileNames ?: "alle"}")
    }

    fun readImport(uri: Uri): ConfigFormat.Content =
        context.contentResolver.openInputStream(uri)!!.use { ConfigFormat.read(it) }

    /** Ein Profil aus einem Import: ersetzen oder unter freiem Namen ablegen. */
    data class ProfileChoice(val profile: StoredProfile, val import: Boolean, val replace: Boolean)

    fun applyImport(content: ConfigFormat.Content, name: Boolean, settings: Boolean, profiles: List<ProfileChoice>) {
        val importedName = content.playerName
        if (name && importedName != null) {
            update { it.copy(playerName = importedName, receiveEmojis = content.receiveEmojis ?: it.receiveEmojis) }
        }
        val importedSettings = content.settings
        if (settings && importedSettings != null) {
            update { it.copy(lastSettings = importedSettings, lastProfileName = content.lastProfileName ?: "") }
        }
        for (choice in profiles.filter { it.import }) {
            val target = if (choice.replace) choice.profile.name else freeProfileName(choice.profile.name)
            // Rohwerte übernehmen: fehlende Felder werden beim ersten Laden abgefragt.
            update { c ->
                val others = c.profiles.filter { it.name != target }
                c.copy(profiles = others + StoredProfile(target, choice.profile.values))
            }
        }
        Diagnostics.log("Import: Name=$name, Einstellungen=$settings, Profile=${profiles.count { it.import }}")
    }
}
