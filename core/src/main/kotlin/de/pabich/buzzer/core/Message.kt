package de.pabich.buzzer.core

import java.net.URLDecoder
import java.net.URLEncoder

/** Nachrichten zwischen Master (M) und Spieler (S), Spezifikation Abschnitt 10. */
sealed interface Message {
    /** M → S: Uhrenabgleich. */
    data class Ping(val seq: Int, val masterTime: Long) : Message

    /** S → M: Antwort auf [Ping] mit der Spieler-Uhrzeit beim Empfang. */
    data class Pong(val seq: Int, val masterTime: Long, val playerTime: Long) : Message

    /** M → S: Modus A – „Finger auflegen“. */
    data class Arm(val round: Int, val attempt: Int, val suddenDeath: Boolean) : Message

    /** S → M: Modus A – Finger liegt auf / abgehoben (vor der Freigabe). */
    data class Hold(val round: Int, val attempt: Int, val holding: Boolean) : Message

    /** M → S: Freigabe um [goAtLocal] (bereits in die Uhr des Spielers umgerechnet). */
    data class GoAt(
        val round: Int,
        val attempt: Int,
        val mode: StartMode,
        val goAtLocal: Long,
        val timeLimitMs: Long,
        val suddenDeath: Boolean,
    ) : Message

    /** S → M: Reaktion (Ereigniszeit − Freigabezeit) oder Frühstart. */
    data class Buzz(val round: Int, val attempt: Int, val reactionMs: Long, val falseStart: Boolean) : Message

    /** M → S: verbindliche Anzeige. [place] 0 = keine Nummer. */
    data class State(val display: Display, val place: Int, val score: String) : Message

    /** M → S: keine Eingaben mehr. */
    data class Freeze(val round: Int) : Message

    /** M → S: Runde beendet, Display schwarz. */
    data class Reset(val score: String) : Message

    /** M → S: Rangliste aus Sicht des Empfängers; [show] = zwischen den Runden anzeigen. */
    data class Scoreboard(val entries: List<ScoreEntry>, val maxScore: Long, val show: Boolean) : Message
}

/** Einfaches Textformat: `TYP|schlüssel=wert|…`, Werte URL-kodiert. */
object MessageCodec {
    fun encode(message: Message): ByteArray {
        val (type, fields) = when (message) {
            is Message.Ping -> "PING" to mapOf("seq" to message.seq, "mt" to message.masterTime)
            is Message.Pong -> "PONG" to mapOf("seq" to message.seq, "mt" to message.masterTime, "pt" to message.playerTime)
            is Message.Arm -> "ARM" to mapOf("r" to message.round, "a" to message.attempt, "sd" to message.suddenDeath)
            is Message.Hold -> "HOLD" to mapOf("r" to message.round, "a" to message.attempt, "h" to message.holding)
            is Message.GoAt -> "GO_AT" to mapOf(
                "r" to message.round, "a" to message.attempt, "m" to message.mode,
                "t" to message.goAtLocal, "l" to message.timeLimitMs, "sd" to message.suddenDeath,
            )
            is Message.Buzz -> "BUZZ" to mapOf(
                "r" to message.round, "a" to message.attempt, "rt" to message.reactionMs, "fs" to message.falseStart,
            )
            is Message.State -> "STATE" to mapOf("d" to message.display, "p" to message.place, "s" to message.score)
            is Message.Freeze -> "FREEZE" to mapOf("r" to message.round)
            is Message.Reset -> "RESET" to mapOf("s" to message.score)
            is Message.Scoreboard -> "BOARD" to mapOf(
                "e" to encodeEntries(message.entries), "m" to message.maxScore, "sh" to message.show,
            )
        }
        val text = buildString {
            append(type)
            for ((key, value) in fields) {
                append('|').append(key).append('=').append(URLEncoder.encode(value.toString(), "UTF-8"))
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Liefert `null` bei unbekannten oder kaputten Nachrichten. */
    fun decode(bytes: ByteArray): Message? = try {
        val parts = String(bytes, Charsets.UTF_8).split('|')
        val f = parts.drop(1).associate {
            val i = it.indexOf('=')
            it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }
        fun int(k: String) = f.getValue(k).toInt()
        fun long(k: String) = f.getValue(k).toLong()
        fun bool(k: String) = f.getValue(k).toBooleanStrict()
        when (parts[0]) {
            "PING" -> Message.Ping(int("seq"), long("mt"))
            "PONG" -> Message.Pong(int("seq"), long("mt"), long("pt"))
            "ARM" -> Message.Arm(int("r"), int("a"), bool("sd"))
            "HOLD" -> Message.Hold(int("r"), int("a"), bool("h"))
            "GO_AT" -> Message.GoAt(
                int("r"), int("a"), StartMode.valueOf(f.getValue("m")), long("t"), long("l"), bool("sd"),
            )
            "BUZZ" -> Message.Buzz(int("r"), int("a"), long("rt"), bool("fs"))
            "STATE" -> Message.State(Display.valueOf(f.getValue("d")), int("p"), f.getValue("s"))
            "FREEZE" -> Message.Freeze(int("r"))
            "RESET" -> Message.Reset(f.getValue("s"))
            "BOARD" -> Message.Scoreboard(decodeEntries(f.getValue("e")), long("m"), bool("sh"))
            else -> null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
    private fun dec(text: String) = URLDecoder.decode(text, "UTF-8")

    private fun encodeEntries(entries: List<ScoreEntry>) = entries.joinToString(",") {
        listOf(it.rank, it.score, if (it.isSelf) 1 else 0, enc(it.label), enc(it.scoreText)).joinToString(":")
    }

    private fun decodeEntries(text: String): List<ScoreEntry> =
        if (text.isEmpty()) emptyList() else text.split(',').map {
            val (rank, score, self, label, scoreText) = it.split(':')
            ScoreEntry(rank.toInt(), dec(label), score.toLong(), dec(scoreText), self == "1")
        }
}
