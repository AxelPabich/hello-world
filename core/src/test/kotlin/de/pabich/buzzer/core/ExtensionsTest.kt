package de.pabich.buzzer.core

import de.pabich.buzzer.core.MasterEngine.Phase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionsTest {
    private class Inbox {
        val emotes = mutableListOf<String>()
        val chats = mutableListOf<String>()
        var profile: Pair<String, Map<String, String>>? = null
        var bye = false
    }

    private fun game(settings: Settings): Pair<SimGame, Map<String, Inbox>> {
        val g = SimGame(settings)
        val inboxes = listOf("A", "B", "C").associateWith { Inbox() }
        for ((id, inbox) in inboxes) {
            val p = g.addPlayer(id, clockOffsetMs = id.hashCode() * 100L, latencyMs = 40)
            p.engine.onEmote = { from, emoji -> inbox.emotes += "$from $emoji" }
            p.engine.onChat = { from, text, all -> inbox.chats += "$from${if (all) "@all" else ""}: $text" }
            p.engine.onProfile = { name, values -> inbox.profile = name to values }
            p.engine.onBye = { inbox.bye = true }
        }
        g.syncClocks()
        return g to inboxes
    }

    private fun SimGame.playToAnswering() {
        master.release()
        scheduleTap("A", 200)
        scheduleTap("B", 300)
        scheduleTap("C", 400)
        // Nur bis zur Antwortphase (plus Laufzeit), damit ein Antwort-Timer nicht schon abläuft.
        while (master.snapshot().phase != Phase.ANSWERING) time.advanceBy(10)
        time.advanceBy(200)
    }

    // ------------------------------------------------------------------ Zeitlimits

    @Test
    fun `players know both limits before the release`() {
        val (g, _) = game(Settings(timeLimitMs = 8_000, answerLimitEnabled = true, answerLimitMs = 12_000))
        val rules = g.view("A").rules
        assertEquals(8_000L, rules.reactionLimitMs)
        assertEquals(12_000L, rules.answerLimitMs)
    }

    @Test
    fun `reaction timer runs from the release`() {
        val (g, _) = game(Settings(timeLimitMs = 8_000))
        g.master.release()
        g.time.advanceBy(500)
        val timer = g.view("A").timer!!
        assertEquals(PlayerEngine.TimerKind.REACTION, timer.kind)
        assertEquals(g.localGoAt("A"), timer.startAt)
        assertEquals(8_000L, timer.durationMs)
    }

    @Test
    fun `without reaction limit the round waits until everybody pressed`() {
        val (g, _) = game(Settings(reactionLimitEnabled = false))
        assertNull(g.view("A").rules.reactionLimitMs)
        g.master.release()
        g.scheduleTap("A", 200)
        g.scheduleTap("B", 25_000)
        g.time.advanceBy(20_000)
        assertEquals(Phase.RUNNING, g.master.snapshot().phase)
        g.scheduleTap("C", 30_000)
        g.time.advanceBy(20_000)
        assertEquals(Phase.ANSWERING, g.master.snapshot().phase)
    }

    @Test
    fun `remaining-time scoring forces the reaction limit on`() {
        val s = Settings(scoring = ScoringMode.REMAINING_TIME, reactionLimitEnabled = false).normalized()
        assertTrue(s.reactionLimitEnabled)
    }

    @Test
    fun `answer timeout counts as wrong and advances automatically`() {
        val (g, _) = game(Settings(answerLimitEnabled = true, answerLimitMs = 5_000))
        g.playToAnswering()
        assertEquals(Display.GREEN, g.view("A").display)
        assertEquals(PlayerEngine.TimerKind.ANSWER, g.view("A").timer?.kind)
        assertTrue(g.master.snapshot().answerDeadline != null)

        g.time.advanceBy(5_500)
        assertEquals(Display.GRAY, g.view("A").display)
        assertEquals(Display.GREEN, g.view("B").display)
        assertEquals(PlayerEngine.TimerKind.ANSWER, g.view("B").timer?.kind)
        assertNull(g.view("A").timer)
    }

    @Test
    fun `answer timeout can wait for the master to proceed`() {
        val (g, _) = game(Settings(answerLimitEnabled = true, answerLimitMs = 5_000, autoAdvanceOnTimeout = false))
        g.playToAnswering()
        g.time.advanceBy(5_500)
        assertEquals(Phase.ANSWER_TIMEOUT, g.master.snapshot().phase)
        assertEquals(Display.GRAY, g.view("A").display)
        assertEquals(Display.RED, g.view("B").display)

        assertTrue(g.master.proceed())
        g.time.advanceBy(500)
        assertEquals(Display.GREEN, g.view("B").display)
    }

    @Test
    fun `answer timer stops on a correct answer`() {
        val (g, _) = game(Settings(answerLimitEnabled = true, answerLimitMs = 5_000))
        g.playToAnswering()
        g.master.markCorrect()
        g.time.advanceBy(10_000)
        assertEquals(Phase.ROUND_OVER, g.master.snapshot().phase)
        assertEquals(Display.GREEN, g.view("A").display)
        assertNull(g.view("A").timer)
    }

    // ------------------------------------------------------------------ Emoticons und Nachrichten

    @Test
    fun `emotes reach everybody who wants them`() {
        val (g, inbox) = game(Settings())
        var onMaster: String? = null
        g.master.onEmote = { from, emoji -> onMaster = "$from $emoji" }
        g.player("C").engine.setReceiveEmojis(false)
        g.time.advanceBy(200)

        g.player("A").engine.sendEmote("😂")
        g.time.advanceBy(200)
        assertEquals(listOf("A 😂"), inbox.getValue("B").emotes)
        assertEquals(listOf("A 😂"), inbox.getValue("A").emotes)
        assertTrue(inbox.getValue("C").emotes.isEmpty())
        assertEquals("A 😂", onMaster)

        // Wer nichts empfängt, kann auch nichts senden; unbekannte Emoticons werden verworfen.
        g.player("C").engine.sendEmote("😂")
        g.player("A").engine.sendEmote("🦄")
        g.time.advanceBy(200)
        assertEquals(1, inbox.getValue("B").emotes.size)
    }

    @Test
    fun `emotes can be switched off by the master and follow the profile`() {
        val (g, inbox) = game(Settings(emojis = listOf("🦄", "🍕")))
        assertEquals(listOf("🦄", "🍕", "😮", "🤔", "😅", "😎", "🙈"), g.view("A").rules.emojis)
        g.master.updateSettings(g.master.settings.copy(emojisEnabled = false))
        g.time.advanceBy(200)
        assertTrue(g.view("A").rules.emojis.isEmpty())
        g.player("A").engine.sendEmote("🦄")
        g.time.advanceBy(200)
        assertTrue(inbox.getValue("B").emotes.isEmpty())
    }

    @Test
    fun `chat goes to one, to all or to the master, only between rounds`() {
        val (g, inbox) = game(Settings())
        val toMaster = mutableListOf<String>()
        g.master.onChat = { from, text, all -> toMaster += "$from${if (all) "@all" else ""}: $text" }
        assertTrue(g.view("A").roster.any { it.first == MASTER_ADDRESS })

        g.player("A").engine.sendChat("B", "Hallo B")
        g.player("A").engine.sendChat(ALL_ADDRESS, "Hallo alle")
        g.player("A").engine.sendChat(MASTER_ADDRESS, "Hallo Chef")
        g.master.sendChat("C", "Psst")
        g.time.advanceBy(300)
        assertEquals(listOf("A: Hallo B", "A@all: Hallo alle"), inbox.getValue("B").chats)
        assertEquals(setOf("A@all: Hallo alle", "Spielleiter: Psst"), inbox.getValue("C").chats.toSet())
        assertTrue(inbox.getValue("A").chats.isEmpty())
        assertEquals(listOf("A@all: Hallo alle", "A: Hallo Chef"), toMaster)

        g.master.release()
        g.player("A").engine.sendChat("B", "während der Runde")
        g.time.advanceBy(300)
        assertEquals(2, inbox.getValue("B").chats.size)
    }

    @Test
    fun `chat can be switched off`() {
        val (g, inbox) = game(Settings(messagesEnabled = false))
        g.player("A").engine.sendChat("B", "Hallo")
        g.time.advanceBy(300)
        assertTrue(inbox.getValue("B").chats.isEmpty())
        assertFalse(g.view("A").rules.messagesEnabled)
    }

    // ------------------------------------------------------------------ Profil und Abschied

    @Test
    fun `the running profile can be shared`() {
        val (g, inbox) = game(Settings())
        g.master.updateSettings(Settings(timeLimitMs = 7_000), profileName = "Kneipenquiz")
        g.master.shareProfile()
        g.time.advanceBy(300)
        val (name, values) = inbox.getValue("B").profile!!
        assertEquals("Kneipenquiz", name)
        assertEquals(7_000L, SettingsCodec.fromMap(values).settings.timeLimitMs)
        assertEquals("Kneipenquiz", g.view("B").rules.profileName)
    }

    @Test
    fun `closing the group says goodbye`() {
        val (g, inbox) = game(Settings())
        g.master.close()
        g.time.advanceBy(300)
        assertTrue(inbox.values.all { it.bye })
    }

    // ------------------------------------------------------------------ Formate

    @Test
    fun `settings survive the codec and report missing keys`() {
        val s = Settings(startMode = StartMode.HOLD_RELEASE, answerLimitEnabled = true, emojis = listOf("🦄"), boardPlaces = 3)
            .normalized()
        val decoded = SettingsCodec.fromMap(SettingsCodec.toMap(s))
        assertEquals(s, decoded.settings)
        assertTrue(decoded.missingKeys.isEmpty())

        val old = SettingsCodec.toMap(s) - "answerLimitMs" - "emojis"
        assertEquals(listOf("answerLimitMs", "emojis"), SettingsCodec.fromMap(old).missingKeys)
    }

    @Test
    fun `config file keeps name, settings and profiles`() {
        val config = AppConfig(
            playerName = "Axel",
            receiveEmojis = false,
            soundOn = false,
            lastSettings = Settings(timeLimitMs = 6_000, sounds = setOf(SoundEvent.CHASE)),
            lastProfileName = "Familie",
            profiles = listOf(
                StoredProfile("Familie", SettingsCodec.toMap(Settings(tieThresholdMs = 30))),
                StoredProfile("Kneipe = laut", SettingsCodec.toMap(Settings(boardShowNames = false))),
            ),
        )
        val out = ByteArrayOutputStream()
        ConfigFormat.write(config, out)
        assertEquals(config, ConfigFormat.readConfig(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `export can leave out parts and import reports what is inside`() {
        val config = AppConfig(
            playerName = "Axel",
            profiles = listOf(StoredProfile("A", mapOf("timeLimitMs" to "5000")), StoredProfile("B", emptyMap())),
        )
        val out = ByteArrayOutputStream()
        ConfigFormat.write(config, out, ConfigFormat.Parts(name = false, settings = false, profileNames = setOf("A")))
        val content = ConfigFormat.read(ByteArrayInputStream(out.toByteArray()))
        assertNull(content.playerName)
        assertNull(content.settings)
        assertEquals(listOf("A"), content.profiles.map { it.name })
        // Profil aus älterem Build: fehlende Werte werden gemeldet.
        val decoded = content.profiles.single().decode()
        assertEquals(5_000L, decoded.settings.timeLimitMs)
        assertTrue("answerLimitMs" in decoded.missingKeys)
    }

    @Test
    fun `old config files without version are read with defaults`() {
        val old = "player.name=Peter\n".byteInputStream()
        val config = ConfigFormat.readConfig(old)
        assertEquals("Peter", config.playerName)
        assertEquals(Settings(), config.lastSettings)
    }
}
