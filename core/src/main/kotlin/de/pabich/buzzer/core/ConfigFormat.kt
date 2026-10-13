package de.pabich.buzzer.core

import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/** Ein gespeichertes Spielprofil; [values] kann aus einem älteren Build stammen und Lücken haben. */
data class StoredProfile(val name: String, val values: Map<String, String>) {
    fun decode(): SettingsCodec.Decoded = SettingsCodec.fromMap(values)
}

/** Lokale Konfiguration: Spielername, letzte Einstellungen, Profile. */
data class AppConfig(
    val playerName: String = "",
    val receiveEmojis: Boolean = true,
    /** Töne auf diesem Gerät. */
    val soundOn: Boolean = true,
    /** Gewähltes Design (derzeit nur „quizshow“). */
    val design: String = "quizshow",
    val lastSettings: Settings = Settings(),
    val lastProfileName: String = "",
    val profiles: List<StoredProfile> = emptyList(),
)

/**
 * Lesen und Schreiben der Config-Datei und von `.buzzconf`-Exporten (beides Properties-Text).
 * Beim Lesen werden fehlende Felder mit Vorgaben ergänzt; Profile bleiben dabei unverändert,
 * damit die App neue Felder beim ersten Laden des Profils abfragen kann.
 */
object ConfigFormat {
    const val VERSION = 2

    data class Parts(
        val name: Boolean = true,
        val settings: Boolean = true,
        /** `null` = alle Profile. */
        val profileNames: Set<String>? = null,
    )

    /** Inhalt einer gelesenen Datei: was darin war, mit Lücken-Info. */
    data class Content(
        val version: Int,
        val playerName: String?,
        val receiveEmojis: Boolean?,
        val soundOn: Boolean?,
        val design: String?,
        val settings: Settings?,
        val lastProfileName: String?,
        val profiles: List<StoredProfile>,
    )

    fun write(config: AppConfig, out: OutputStream, parts: Parts = Parts()) {
        val p = Properties()
        p["format.version"] = VERSION.toString()
        if (parts.name) {
            p["player.name"] = config.playerName
            p["player.receiveEmojis"] = config.receiveEmojis.toString()
            p["player.soundOn"] = config.soundOn.toString()
            p["app.design"] = config.design
        }
        if (parts.settings) {
            SettingsCodec.toMap(config.lastSettings).forEach { (k, v) -> p["settings.$k"] = v }
            p["settings.profileName"] = config.lastProfileName
        }
        config.profiles
            .filter { parts.profileNames == null || it.name in parts.profileNames }
            .forEachIndexed { i, profile ->
                p["profile.$i.name"] = profile.name
                profile.values.forEach { (k, v) -> p["profile.$i.$k"] = v }
            }
        p.store(out.writer(Charsets.UTF_8), "Buzzer-Konfiguration")
    }

    fun read(input: InputStream): Content {
        val p = Properties()
        p.load(input.reader(Charsets.UTF_8))
        val settingsMap = p.stringPropertyNames().filter { it.startsWith("settings.") }
            .associate { it.removePrefix("settings.") to p.getProperty(it) }
        val profiles = generateSequence(0) { it + 1 }
            .takeWhile { p.getProperty("profile.$it.name") != null }
            .map { i ->
                val prefix = "profile.$i."
                val values = p.stringPropertyNames().filter { it.startsWith(prefix) && it != "${prefix}name" }
                    .associate { it.removePrefix(prefix) to p.getProperty(it) }
                StoredProfile(p.getProperty("${prefix}name"), values)
            }
            .toList()
        return Content(
            version = p.getProperty("format.version")?.toIntOrNull() ?: 1,
            playerName = p.getProperty("player.name"),
            receiveEmojis = p.getProperty("player.receiveEmojis")?.toBooleanStrictOrNull(),
            soundOn = p.getProperty("player.soundOn")?.toBooleanStrictOrNull(),
            design = p.getProperty("app.design"),
            settings = settingsMap.takeIf { it.isNotEmpty() }?.let { SettingsCodec.fromMap(it).settings },
            lastProfileName = p.getProperty("settings.profileName"),
            profiles = profiles,
        )
    }

    /** Liest die eigene Config-Datei und ergänzt fehlende Werte mit Vorgaben. */
    fun readConfig(input: InputStream): AppConfig {
        val c = read(input)
        return AppConfig(
            playerName = c.playerName ?: "",
            receiveEmojis = c.receiveEmojis ?: true,
            soundOn = c.soundOn ?: true,
            design = c.design ?: "quizshow",
            lastSettings = c.settings ?: Settings(),
            lastProfileName = c.lastProfileName ?: "",
            profiles = c.profiles,
        )
    }
}
