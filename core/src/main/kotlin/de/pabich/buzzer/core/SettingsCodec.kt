package de.pabich.buzzer.core

/**
 * Einstellungen als Schlüssel/Wert-Paare – für Config-Datei, Profile, `.buzzconf` und das
 * Weitergeben eines Profils. Fehlende Schlüssel (z. B. aus älteren Builds) werden gemeldet,
 * damit die App sie beim ersten Laden abfragen kann.
 */
object SettingsCodec {
    /** Lesbare Namen für die Abfrage neuer Einstellungen. */
    val LABELS = linkedMapOf(
        "startMode" to "Startmodus",
        "timeLimitMs" to "Max. Reaktionszeit",
        "reactionLimitEnabled" to "Reaktionszeit begrenzen",
        "answerLimitEnabled" to "Antwortzeit begrenzen",
        "answerLimitMs" to "Max. Antwortzeit",
        "autoAdvanceOnTimeout" to "Nach Zeitablauf automatisch weiter",
        "delayMinMs" to "Verzögerung min",
        "delayMaxMs" to "Verzögerung max",
        "suddenDeathDelayMinMs" to "Sudden-Death-Verzögerung min",
        "suddenDeathDelayMaxMs" to "Sudden-Death-Verzögerung max",
        "tieThresholdMs" to "Gleichstands-Schwelle",
        "scoring" to "Wertung",
        "masterPlays" to "Master spielt mit",
        "boardShowNames" to "Rangliste: Namen anzeigen",
        "boardPlaces" to "Rangliste: sichtbare Plätze",
        "messagesEnabled" to "Kurznachrichten erlaubt",
        "emojisEnabled" to "Emoticons erlaubt",
        "emojis" to "Emoticon-Auswahl",
        "sounds" to "Klänge",
        "quizCategories" to "Quiz: Kategorien",
        "quizLevels" to "Quiz: Schwierigkeit",
        "quizCount" to "Quiz: Fragen pro Spiel",
        "quizAnswerMs" to "Quiz: Antwortzeit",
        "quizScoring" to "Quiz: Wertung",
        "quizCountdownMs" to "Quiz: Countdown nach erster richtiger Antwort",
    )

    /** Klänge, die es vor dem Schlüssel [SOUNDS_KNOWN] gab; später hinzugekommene sind neu. */
    private val LEGACY_SOUNDS = listOf(
        "JOIN", "HUM", "BUZZ", "FIRST", "RED", "FALSE_START", "SUDDEN_DEATH", "TICK", "TIMEOUT",
        "RIGHT", "WRONG", "ROUND_END", "EMOTE", "CHAT", "CHASE",
    )

    /** Technischer Schlüssel: welche Klänge der speichernde Build kannte (neue sind vorgabemäßig an). */
    private const val SOUNDS_KNOWN = "soundsKnown"

    val KEYS: Set<String> get() = LABELS.keys

    fun toMap(s: Settings): Map<String, String> = linkedMapOf(
        "startMode" to s.startMode.name,
        "timeLimitMs" to s.timeLimitMs.toString(),
        "reactionLimitEnabled" to s.reactionLimitEnabled.toString(),
        "answerLimitEnabled" to s.answerLimitEnabled.toString(),
        "answerLimitMs" to s.answerLimitMs.toString(),
        "autoAdvanceOnTimeout" to s.autoAdvanceOnTimeout.toString(),
        "delayMinMs" to s.delayMinMs.toString(),
        "delayMaxMs" to s.delayMaxMs.toString(),
        "suddenDeathDelayMinMs" to s.suddenDeathDelayMinMs.toString(),
        "suddenDeathDelayMaxMs" to s.suddenDeathDelayMaxMs.toString(),
        "tieThresholdMs" to s.tieThresholdMs.toString(),
        "scoring" to s.scoring.name,
        "masterPlays" to s.masterPlays.toString(),
        "boardShowNames" to s.boardShowNames.toString(),
        "boardPlaces" to s.boardPlaces.toString(),
        "messagesEnabled" to s.messagesEnabled.toString(),
        "emojisEnabled" to s.emojisEnabled.toString(),
        "emojis" to s.emojis.joinToString(" "),
        "sounds" to s.sounds.sortedBy { it.ordinal }.joinToString(",") { it.name },
        SOUNDS_KNOWN to SoundEvent.entries.joinToString(",") { it.name },
        "quizCategories" to s.quizCategories.sorted().joinToString("|"),
        "quizLevels" to s.quizLevels.sortedBy { it.ordinal }.joinToString(",") { it.key },
        "quizCount" to s.quizCount.toString(),
        "quizAnswerMs" to s.quizAnswerMs.toString(),
        "quizScoring" to s.quizScoring.name,
        "quizCountdownMs" to s.quizCountdownMs.toString(),
    )

    data class Decoded(val settings: Settings, val missingKeys: List<String>)

    /** Unbekannte oder kaputte Werte fallen auf die Vorgabe zurück und zählen als fehlend. */
    fun fromMap(map: Map<String, String>): Decoded {
        val d = Settings()
        val missing = mutableListOf<String>()
        fun <T> read(key: String, default: T, parse: (String) -> T?): T {
            val value = map[key]?.let { runCatching { parse(it) }.getOrNull() }
            if (value == null) missing += key
            return value ?: default
        }
        val s = Settings(
            startMode = read("startMode", d.startMode) { StartMode.valueOf(it) },
            timeLimitMs = read("timeLimitMs", d.timeLimitMs) { it.toLong() },
            reactionLimitEnabled = read("reactionLimitEnabled", d.reactionLimitEnabled) { it.toBooleanStrict() },
            answerLimitEnabled = read("answerLimitEnabled", d.answerLimitEnabled) { it.toBooleanStrict() },
            answerLimitMs = read("answerLimitMs", d.answerLimitMs) { it.toLong() },
            autoAdvanceOnTimeout = read("autoAdvanceOnTimeout", d.autoAdvanceOnTimeout) { it.toBooleanStrict() },
            delayMinMs = read("delayMinMs", d.delayMinMs) { it.toLong() },
            delayMaxMs = read("delayMaxMs", d.delayMaxMs) { it.toLong() },
            suddenDeathDelayMinMs = read("suddenDeathDelayMinMs", d.suddenDeathDelayMinMs) { it.toLong() },
            suddenDeathDelayMaxMs = read("suddenDeathDelayMaxMs", d.suddenDeathDelayMaxMs) { it.toLong() },
            tieThresholdMs = read("tieThresholdMs", d.tieThresholdMs) { it.toLong() },
            scoring = read("scoring", d.scoring) { ScoringMode.valueOf(it) },
            masterPlays = read("masterPlays", d.masterPlays) { it.toBooleanStrict() },
            boardShowNames = read("boardShowNames", d.boardShowNames) { it.toBooleanStrict() },
            boardPlaces = read("boardPlaces", d.boardPlaces) { it.toInt() },
            messagesEnabled = read("messagesEnabled", d.messagesEnabled) { it.toBooleanStrict() },
            emojisEnabled = read("emojisEnabled", d.emojisEnabled) { it.toBooleanStrict() },
            emojis = read("emojis", d.emojis) { v -> v.split(' ').filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() } },
            sounds = read("sounds", d.sounds) { v ->
                val known = map[SOUNDS_KNOWN]?.split(',')?.toSet() ?: LEGACY_SOUNDS.toSet()
                val chosen = v.split(',').filter { it.isNotEmpty() }.mapNotNull { n -> SoundEvent.entries.firstOrNull { it.name == n } }
                // Klänge, die der speichernde Build noch nicht kannte, sind wie vorgegeben an.
                (chosen + SoundEvent.entries.filter { it.defaultOn && it.name !in known }).toSet()
            },
            quizCategories = read("quizCategories", d.quizCategories) { v -> v.split('|').filter { it.isNotBlank() }.toSet() },
            quizLevels = read("quizLevels", d.quizLevels) { v -> v.split(',').mapNotNull { QuizLevel.byKey(it) }.toSet().ifEmpty { null } },
            quizCount = read("quizCount", d.quizCount) { it.toInt() },
            quizAnswerMs = read("quizAnswerMs", d.quizAnswerMs) { it.toLong() },
            quizScoring = read("quizScoring", d.quizScoring) { QuizScoring.valueOf(it) },
            quizCountdownMs = read("quizCountdownMs", d.quizCountdownMs) { it.toLong() },
        )
        return Decoded(s.normalized(), missing)
    }

    /** Kompakte Ein-Zeilen-Form für die Übertragung eines Profils. */
    fun encodeLine(map: Map<String, String>): String =
        map.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    fun decodeLine(line: String): Map<String, String> =
        if (line.isEmpty()) emptyMap() else line.split('&').associate {
            val i = it.indexOf('=')
            dec(it.substring(0, i)) to dec(it.substring(i + 1))
        }

    private fun enc(t: String) = java.net.URLEncoder.encode(t, "UTF-8")
    private fun dec(t: String) = java.net.URLDecoder.decode(t, "UTF-8")
}
