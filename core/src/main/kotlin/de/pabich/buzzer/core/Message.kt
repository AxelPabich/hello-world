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

    /** M → S: Spielregeln, die Spieler vor der Freigabe sehen. Limits 0 = ohne Limit; leere Emoticons = aus. */
    data class Config(
        val reactionLimitMs: Long,
        val answerLimitMs: Long,
        val messagesEnabled: Boolean,
        val emojis: List<String>,
        val profileName: String,
        val sounds: Set<SoundEvent> = DEFAULT_SOUNDS,
    ) : Message

    /** M → S: Antwortzeit des grünen Spielers, Rest in ms ab Empfang (0 = keine). */
    data class AnswerTimer(val remainingMs: Long) : Message

    /** S → M: Emoticon senden ([from] leer); M → S: Emoticon von [from] anzeigen. */
    data class Emote(val from: String, val emoji: String) : Message

    /** S → M: Emoticons empfangen (und damit auch senden) ja/nein. */
    data class Prefs(val receiveEmojis: Boolean) : Message

    /** M → S: mögliche Empfänger für Kurznachrichten. */
    data class Roster(val entries: List<Pair<PlayerId, String>>) : Message

    /** S → M: Kurznachricht an [to] (Spieler-ID, [MASTER_ADDRESS] oder [ALL_ADDRESS]). */
    data class ChatSend(val to: String, val text: String) : Message

    /** M → S: Kurznachricht von [from]. */
    data class Chat(val from: String, val text: String, val toAll: Boolean) : Message

    /** M → S: das laufende Spielprofil zum Ansehen und Speichern. */
    data class Profile(val name: String, val values: Map<String, String>) : Message

    /** M → S: der Spielleiter schließt die Gruppe. */
    data object Bye : Message

    /** M → S: gewählte Spielart (`null` = noch nicht gewählt). */
    data class Mode(val kind: GameKind?) : Message

    /**
     * M → S: neue Quizfrage. [answers] in der Reihenfolge dieses Spielers – welche richtig ist,
     * erfährt das Gerät erst mit [QuizResult]. Frage ab [questionAtLocal], Antworten und Timer ab
     * [answersAtLocal] (beides in der Uhr des Spielers).
     */
    data class QuizAsk(
        val number: Int,
        val total: Int,
        val category: String,
        val level: QuizLevel,
        val text: String,
        val answers: List<String>,
        val questionAtLocal: Long,
        val answersAtLocal: Long,
        val durationMs: Long,
    ) : Message

    /** S → M: gewählte Position (0–3, eigene Reihenfolge) nach [elapsedMs] ab Einblenden der Antworten. */
    data class QuizAnswer(val number: Int, val position: Int, val elapsedMs: Long) : Message

    /** M → S: Antwortzeit verkürzt (Countdown nach der ersten richtigen Antwort), ab Einblenden gerechnet. */
    data class QuizDeadline(val number: Int, val deadlineMs: Long) : Message

    /** M → S: Nach einer Pause mitten in der Frage verschieben sich Einblenden und Antwortzeit um [shiftMs]. */
    data class QuizShift(val number: Int, val shiftMs: Long) : Message

    /** M → S: Auflösung aus Sicht des Empfängers; Positionen in seiner Reihenfolge, -1 = keine. */
    data class QuizResult(
        val number: Int,
        val correctPosition: Int,
        val chosenPosition: Int,
        val outcome: QuizOutcome,
        val gained: String,
        val score: String,
        val explanation: String,
        val source: String,
        /** Nach einer Challenge-Entscheidung neu geschickt (ohne Klang). */
        val corrected: Boolean,
    ) : Message

    /** S → M: Frage anfechten, mit optionaler Begründung. */
    data class QuizChallenge(val number: Int, val reason: String) : Message

    /** S → M: tippt gerade eine Kurznachricht (an) bzw. fertig (aus). */
    data class Typing(val on: Boolean) : Message

    /**
     * S → M: Teil [index] von [count] eines Diagnoseberichts von [from] (Build [build]).
     * [saved]: ein auf dem Spieler gespeicherter Bericht – er wird dort erst gelöscht, wenn der
     * Spielleiter ihn im Repository abgelegt hat.
     */
    data class DiagnoseChunk(
        val reportId: String,
        val index: Int,
        val count: Int,
        val from: String,
        val build: Int,
        val text: String,
        val saved: Boolean = false,
    ) : Message

    /** M → S: Diagnosebericht erhalten (bzw. bei gespeicherten Berichten: im Repository abgelegt). */
    data class DiagnoseReceived(val reportId: String) : Message

    /** M → S: Der Spielleiter hat einen GitHub-Zugang und übernimmt gespeicherte Diagnosen. */
    data object DiagnoseCollect : Message

    /** M → S: automatischer Testmodus an/aus – der Spieler tippt, antwortet und bestätigt von selbst. */
    data class TestMode(val on: Boolean) : Message

    /** M → S: Der Spielleiter bittet um einen Diagnosebericht (der Spieler entscheidet). */
    data object DiagnoseRequest : Message

    /** M → S: Spielstart-Animation [variant] zeigen (Rennszene, auf allen Geräten dieselbe). */
    data class Intro(val variant: Int) : Message

    /** M → S: „Bereit?“-Abfrage [id] anzeigen ([on]) bzw. schließen. */
    data class ReadyCheck(val id: Int, val on: Boolean) : Message

    /** S → M: bereit für Abfrage [id]. */
    data class Ready(val id: Int) : Message

    /** M → S: Der Spielleiter prüft die Auflösung (Laufband an/aus). */
    data class Review(val on: Boolean) : Message

    /** Beide Richtungen: bitte die eigene App (APK) schicken. */
    data object ApkRequest : Message

    /** Beide Richtungen: gleich folgt die APK als Datei [payloadId] mit [build] und [size] Bytes. */
    data class ApkInfo(val payloadId: Long, val build: Int, val size: Long) : Message

    /**
     * M → S: alles, was ein Nachfolger zum Weiterführen braucht (Punkt 59). Kommt in jeder Pause neu.
     * [to] = Name des bestimmten Nachfolgers bei „Spielleitung übergeben“, sonst leer.
     * [scores] nach Namen (ohne „ (Master)“); [quiz] = Fragen-IDs, [quizNext] = Index der nächsten
     * zu stellenden Frage (-1 = kein laufendes Quiz).
     */
    data class Succession(
        val game: String,
        val master: String,
        val to: String,
        val kind: GameKind?,
        val round: Int,
        val profile: String,
        val settings: Map<String, String>,
        val scores: List<Pair<String, Long>>,
        val quiz: List<String>,
        val quizNext: Int,
        val test: Boolean,
        /** Fragen ab der zuletzt gestellten – der Nachfolger hat sie vielleicht nicht in seinen Paketen. */
        val questions: List<QuizQuestion> = emptyList(),
    ) : Message

    /** S → M: Emoticon, das der Spieler als Avatar gewählt hat (leer = keins). */
    data class Avatar(val emoji: String) : Message

    /** M → S: Spielregeln der Runde zum einmaligen Anzeigen – beim Beitritt und zu Spielbeginn. */
    data class Rules(val title: String, val lines: List<Pair<String, String>>, val confirmId: Int = 0) : Message

    /** S → M: Der Spieler hat die Spielregeln gelesen und mit OK bestätigt. */
    data class RulesOk(val id: Int) : Message

    /** M → S: Die Bestätigung der Spielregeln ist vorbei (alle bestätigt oder abgebrochen). */
    data class RulesDone(val id: Int) : Message

    /** M → S: Das Quiz ist zu Ende (oder abgebrochen) – die Spieler sehen das Endergebnis. */
    data class QuizEnd(val aborted: Boolean) : Message

    /** S → M: Der Spieler möchte das Profil des laufenden Spiels kopieren (Antwort: [Profile]). */
    data object ProfileRequest : Message

    /** S → M: Lesetempo des Spielers in Millisekunden je Zeichen (aus dem Lesetest). */
    data class ReadSpeed(val msPerChar: Int) : Message

    /** M → S: Der Spielleiter geht, die Spieler dürfen übernehmen (statt [Bye]). */
    data object MasterGone : Message

    /** M → S: Spielpause an/aus – Spieler sehen ein Pausen-Overlay. */
    data class Pause(val on: Boolean) : Message

    /** M → S: diesen Klang jetzt abspielen. */
    data class Sound(val event: SoundEvent) : Message

    /**
     * M → S: Spielverlauf als Animation. [labels] aus Sicht des Empfängers (eigener Eintrag „Du“
     * an Position [selfIndex], sonst -1); [frames] = Punktestände je Spieler nach jeder Runde,
     * der erste Frame ist der Start.
     */
    data class Replay(
        val labels: List<String>,
        val selfIndex: Int,
        val frames: List<List<Long>>,
        val scoring: ScoringMode,
        val durationMs: Long,
    ) : Message
}

enum class QuizOutcome { RIGHT, WRONG, NONE, ANNULLED }

const val ALL_ADDRESS = "*"
const val MASTER_ADDRESS = "@master"
const val MAX_CHAT_LENGTH = 100

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
            is Message.Config -> "CONFIG" to mapOf(
                "rl" to message.reactionLimitMs, "al" to message.answerLimitMs, "me" to message.messagesEnabled,
                "em" to encodeList(message.emojis), "pn" to message.profileName,
                "so" to message.sounds.joinToString(",") { it.name },
            )
            is Message.Sound -> "SOUND" to mapOf("e" to message.event.name)
            is Message.Pause -> "PAUSE" to mapOf("on" to message.on)
            is Message.Replay -> "REPLAY" to mapOf(
                "l" to encodeList(message.labels), "s" to message.selfIndex,
                "f" to message.frames.joinToString(";") { it.joinToString(",") },
                "sc" to message.scoring, "d" to message.durationMs,
            )
            is Message.AnswerTimer -> "ANSWER_TIMER" to mapOf("ms" to message.remainingMs)
            is Message.Emote -> "EMOTE" to mapOf("f" to message.from, "e" to message.emoji)
            is Message.Prefs -> "PREFS" to mapOf("re" to message.receiveEmojis)
            is Message.Roster -> "ROSTER" to mapOf(
                "e" to encodeList(message.entries.map { "${enc(it.first)}:${enc(it.second)}" }),
            )
            is Message.ChatSend -> "CHAT_SEND" to mapOf("to" to message.to, "t" to message.text)
            is Message.Chat -> "CHAT" to mapOf("f" to message.from, "t" to message.text, "all" to message.toAll)
            is Message.Profile -> "PROFILE" to mapOf("n" to message.name, "v" to SettingsCodec.encodeLine(message.values))
            Message.Bye -> "BYE" to emptyMap()
            is Message.Mode -> "MODE" to mapOf("k" to (message.kind?.name ?: ""))
            is Message.QuizAsk -> "QUIZ_ASK" to mapOf(
                "n" to message.number, "t" to message.total, "c" to message.category, "l" to message.level.legacyName,
                "lk" to message.level.key, "ll" to message.level.label,
                "q" to message.text, "a" to encodeList(message.answers), "qa" to message.questionAtLocal,
                "aa" to message.answersAtLocal, "d" to message.durationMs,
            )
            is Message.QuizAnswer -> "QUIZ_ANSWER" to mapOf("n" to message.number, "p" to message.position, "e" to message.elapsedMs)
            is Message.QuizDeadline -> "QUIZ_DEADLINE" to mapOf("n" to message.number, "d" to message.deadlineMs)
            is Message.QuizShift -> "QUIZ_SHIFT" to mapOf("n" to message.number, "ms" to message.shiftMs)
            is Message.QuizResult -> "QUIZ_RESULT" to mapOf(
                "n" to message.number, "cp" to message.correctPosition, "ch" to message.chosenPosition,
                "o" to message.outcome.name, "g" to message.gained, "s" to message.score,
                "x" to message.explanation, "src" to message.source, "cor" to message.corrected,
            )
            is Message.QuizChallenge -> "QUIZ_CHALLENGE" to mapOf("n" to message.number, "r" to message.reason)
            is Message.Typing -> "TYPING" to mapOf("on" to message.on)
            is Message.DiagnoseChunk -> "DIAG" to mapOf(
                "id" to message.reportId, "i" to message.index, "n" to message.count,
                "f" to message.from, "b" to message.build, "t" to message.text, "sv" to message.saved,
            )
            Message.DiagnoseCollect -> "DIAG_COLLECT" to emptyMap()
            Message.DiagnoseRequest -> "DIAG_REQUEST" to emptyMap()
            is Message.TestMode -> "TEST_MODE" to mapOf("on" to message.on)
            is Message.Intro -> "INTRO" to mapOf("v" to message.variant)
            is Message.DiagnoseReceived -> "DIAG_OK" to mapOf("id" to message.reportId)
            is Message.ReadyCheck -> "READY_CHECK" to mapOf("id" to message.id, "on" to message.on)
            is Message.Ready -> "READY" to mapOf("id" to message.id)
            is Message.Review -> "REVIEW" to mapOf("on" to message.on)
            Message.ApkRequest -> "APK_REQUEST" to emptyMap()
            is Message.ApkInfo -> "APK_INFO" to mapOf("id" to message.payloadId, "b" to message.build, "sz" to message.size)
            is Message.Succession -> "SUCCESSION" to mapOf(
                "g" to message.game, "m" to message.master, "to" to message.to, "k" to (message.kind?.name ?: ""),
                "r" to message.round, "pn" to message.profile, "s" to SettingsCodec.encodeLine(message.settings),
                "sc" to encodeList(message.scores.map { "${enc(it.first)}:${it.second}" }),
                "q" to encodeList(message.quiz), "qn" to message.quizNext, "t" to message.test,
                "qq" to encodeQuestions(message.questions),
            )
            Message.MasterGone -> "MASTER_GONE" to emptyMap()
            is Message.Avatar -> "AVATAR" to mapOf("e" to message.emoji)
            is Message.Rules -> "RULES" to mapOf(
                "t" to message.title,
                "l" to encodeList(message.lines.map { "${enc(it.first)}:${enc(it.second)}" }),
                "cid" to message.confirmId,
            )
            is Message.RulesOk -> "RULES_OK" to mapOf("id" to message.id)
            is Message.RulesDone -> "RULES_DONE" to mapOf("id" to message.id)
            is Message.QuizEnd -> "QUIZ_END" to mapOf("a" to message.aborted)
            Message.ProfileRequest -> "PROFILE_REQ" to emptyMap()
            is Message.ReadSpeed -> "READ_SPEED" to mapOf("ms" to message.msPerChar)
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
            "CONFIG" -> Message.Config(
                long("rl"), long("al"), bool("me"), decodeList(f.getValue("em")), f.getValue("pn"),
                f["so"]?.let { v -> v.split(',').filter { it.isNotEmpty() }.mapNotNull { n -> SoundEvent.entries.firstOrNull { it.name == n } }.toSet() }
                    ?: DEFAULT_SOUNDS,
            )
            "PAUSE" -> Message.Pause(bool("on"))
            "SOUND" -> SoundEvent.entries.firstOrNull { it.name == f.getValue("e") }?.let { Message.Sound(it) }
            "REPLAY" -> Message.Replay(
                decodeList(f.getValue("l")), int("s"),
                f.getValue("f").split(';').filter { it.isNotEmpty() }.map { row -> row.split(',').map { it.toLong() } },
                ScoringMode.valueOf(f.getValue("sc")), long("d"),
            )
            "ANSWER_TIMER" -> Message.AnswerTimer(long("ms"))
            "EMOTE" -> Message.Emote(f.getValue("f"), f.getValue("e"))
            "PREFS" -> Message.Prefs(bool("re"))
            "ROSTER" -> Message.Roster(
                decodeList(f.getValue("e")).map { val (id, name) = it.split(':'); dec(id) to dec(name) },
            )
            "CHAT_SEND" -> Message.ChatSend(f.getValue("to"), f.getValue("t"))
            "CHAT" -> Message.Chat(f.getValue("f"), f.getValue("t"), bool("all"))
            "PROFILE" -> Message.Profile(f.getValue("n"), SettingsCodec.decodeLine(f.getValue("v")))
            "BYE" -> Message.Bye
            "MODE" -> Message.Mode(f.getValue("k").takeIf { it.isNotEmpty() }?.let { GameKind.valueOf(it) })
            "QUIZ_ASK" -> Message.QuizAsk(
                int("n"), int("t"), f.getValue("c"),
                QuizLevel.fromWire(f["lk"], f["ll"], f.getValue("l")), f.getValue("q"),
                decodeList(f.getValue("a")), long("qa"), long("aa"), long("d"),
            )
            "QUIZ_ANSWER" -> Message.QuizAnswer(int("n"), int("p"), long("e"))
            "QUIZ_DEADLINE" -> Message.QuizDeadline(int("n"), long("d"))
            "QUIZ_SHIFT" -> Message.QuizShift(int("n"), long("ms"))
            "QUIZ_RESULT" -> Message.QuizResult(
                int("n"), int("cp"), int("ch"), QuizOutcome.valueOf(f.getValue("o")), f.getValue("g"), f.getValue("s"),
                f.getValue("x"), f.getValue("src"), bool("cor"),
            )
            "QUIZ_CHALLENGE" -> Message.QuizChallenge(int("n"), f.getValue("r"))
            "TYPING" -> Message.Typing(bool("on"))
            "DIAG" -> Message.DiagnoseChunk(
                f.getValue("id"), int("i"), int("n"), f.getValue("f"), int("b"), f.getValue("t"), f["sv"]?.toBooleanStrict() ?: false,
            )
            "DIAG_COLLECT" -> Message.DiagnoseCollect
            "DIAG_REQUEST" -> Message.DiagnoseRequest
            "TEST_MODE" -> Message.TestMode(bool("on"))
            "INTRO" -> Message.Intro(int("v"))
            "DIAG_OK" -> Message.DiagnoseReceived(f.getValue("id"))
            "READY_CHECK" -> Message.ReadyCheck(int("id"), bool("on"))
            "READY" -> Message.Ready(int("id"))
            "REVIEW" -> Message.Review(bool("on"))
            "APK_REQUEST" -> Message.ApkRequest
            "APK_INFO" -> Message.ApkInfo(long("id"), int("b"), long("sz"))
            "SUCCESSION" -> Message.Succession(
                game = f.getValue("g"), master = f.getValue("m"), to = f.getValue("to"),
                kind = f.getValue("k").takeIf { it.isNotEmpty() }?.let { GameKind.valueOf(it) },
                round = int("r"), profile = f.getValue("pn"), settings = SettingsCodec.decodeLine(f.getValue("s")),
                scores = decodeList(f.getValue("sc")).map {
                    val i = it.lastIndexOf(':')
                    dec(it.substring(0, i)) to it.substring(i + 1).toLong()
                },
                quiz = decodeList(f.getValue("q")), quizNext = int("qn"), test = bool("t"),
                questions = f["qq"]?.let { decodeQuestions(it) }.orEmpty(),
            )
            "MASTER_GONE" -> Message.MasterGone
            "AVATAR" -> Message.Avatar(f.getValue("e"))
            "RULES" -> Message.Rules(
                f.getValue("t"),
                decodeList(f.getValue("l")).map {
                    val i = it.indexOf(':')
                    dec(it.substring(0, i)) to dec(it.substring(i + 1))
                },
                f["cid"]?.toInt() ?: 0,
            )
            "RULES_OK" -> Message.RulesOk(int("id"))
            "RULES_DONE" -> Message.RulesDone(int("id"))
            "QUIZ_END" -> Message.QuizEnd(bool("a"))
            "PROFILE_REQ" -> Message.ProfileRequest
            "READ_SPEED" -> Message.ReadSpeed(int("ms"))
            else -> null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
    private fun dec(text: String) = URLDecoder.decode(text, "UTF-8")

    private fun encodeList(items: List<String>) = items.joinToString(",") { enc(it) }

    private fun decodeList(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split(',').map { dec(it) }

    /** Fragen kompakt; wird es zu groß für eine Nachricht, entfallen erst Quellen, dann Erklärungen. */
    private fun encodeQuestions(questions: List<QuizQuestion>): String {
        fun build(source: Boolean, explanation: Boolean, count: Int = questions.size) = questions.take(count).joinToString(";") { q ->
            encodeList(
                listOf(q.id, q.category, q.level.legacyName, q.text) + q.answers +
                    listOf(q.correctIndex.toString(), if (explanation) q.explanation else "", if (source) q.source else ""),
            )
        }
        return build(true, true).takeIf { it.length < MAX_QUESTIONS_CHARS }
            ?: build(false, true).takeIf { it.length < MAX_QUESTIONS_CHARS }
            ?: build(false, false).takeIf { it.length < MAX_QUESTIONS_CHARS }
            // Sehr langes Quiz: nur so viele, wie hineinpassen (die übrigen holt der Nachfolger aus seinen Paketen).
            ?: (questions.size downTo 1).map { build(false, false, it) }.firstOrNull { it.length < MAX_QUESTIONS_CHARS }
            ?: ""
    }

    private fun decodeQuestions(text: String): List<QuizQuestion> =
        if (text.isEmpty()) emptyList() else text.split(';').mapNotNull { item ->
            val v = decodeList(item)
            if (v.size < 7) return@mapNotNull null
            val n = v.size - 7 // Anzahl Antworten
            QuizQuestion(
                id = v[0], category = v[1], level = QuizLevel.byKey(v[2]) ?: QuizLevel.HARD, text = v[3],
                answers = v.subList(4, 4 + n), correctIndex = v[4 + n].toInt(),
                explanation = v[5 + n], source = v[6 + n],
            )
        }

    /** Grenze für die Fragen im Spielstand (Nearby-Nachrichten dürfen höchstens 32 KB groß sein). */
    private const val MAX_QUESTIONS_CHARS = 12_000

    private fun encodeEntries(entries: List<ScoreEntry>) = entries.joinToString(",") {
        listOf(it.rank, it.score, if (it.isSelf) 1 else 0, enc(it.label), enc(it.scoreText)).joinToString(":")
    }

    private fun decodeEntries(text: String): List<ScoreEntry> =
        if (text.isEmpty()) emptyList() else text.split(',').map {
            val (rank, score, self, label, scoreText) = it.split(':')
            ScoreEntry(rank.toInt(), dec(label), score.toLong(), dec(scoreText), self == "1")
        }
}
