package de.pabich.buzzer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.ChatLine
import de.pabich.buzzer.Diagnostics
import de.pabich.buzzer.FlyingEmote
import de.pabich.buzzer.core.MAX_CHAT_LENGTH
import de.pabich.buzzer.core.PlayerEngine
import de.pabich.buzzer.core.PlayerEngine.QuizStage
import de.pabich.buzzer.core.QuizOutcome
import kotlin.math.ceil

/** Farben der Auflösung (Kontrast für weiße Schrift). */
val QuizGreen = Color(0xFF127A39)
val QuizGreenEdge = Color(0xFF50E682)
val QuizRed = Color(0xFFC62828)
val QuizRedEdge = Color(0xFFFF8A8A)

private enum class FieldState { OPEN, CHOSEN, DIMMED, CORRECT, WRONG }

/**
 * Das gemeinsame Feld: Zeitbalken, Frage und darunter die vier Antworten (2×2) – die Frage steht
 * direkt über den Antworten. [onTap] erhält die Position und die Touch-Zeit (uptime).
 */
@Composable
fun QuizCard(
    quiz: PlayerEngine.QuizView,
    onTap: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    title: String? = null,
) {
    val theme = LocalBuzzerTheme.current
    val now = frameTime(quiz.number)
    val beforeAnswers = quiz.stage == QuizStage.WAITING || quiz.stage == QuizStage.QUESTION
    val glow = beforeAnswers
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(theme.panel)
            .border(2.dp, if (glow) theme.accent else theme.line, RoundedCornerShape(20.dp))
            .padding(if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        // Zeitbalken (nicht nach der Auflösung)
        if (quiz.stage != QuizStage.REVEALED) {
            val end = quiz.answersAt + quiz.deadlineMs
            val remaining = if (now < quiz.answersAt) quiz.durationMs else (end - now).coerceIn(0, quiz.durationMs)
            val running = !beforeAnswers && quiz.stage != QuizStage.CLOSED
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (title != null) Text(title, color = theme.accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (compact) 8.dp else 10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(theme.line),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((remaining.toFloat() / quiz.durationMs).coerceIn(0f, 1f))
                            .background(if (running) theme.accent2 else theme.line),
                    )
                }
                Text(
                    seconds(remaining),
                    color = if (running) theme.accent2 else theme.muted,
                    fontFamily = theme.display,
                    fontSize = if (compact) 15.sp else 18.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(58.dp),
                )
            }
        }
        Text(
            // Vor dem gemeinsamen Zeitpunkt bleibt die Frage verdeckt – sie erscheint überall gleichzeitig.
            if (quiz.stage == QuizStage.WAITING) "" else quiz.text,
            color = theme.text,
            fontSize = if (compact) 18.sp else if (beforeAnswers) 26.sp else 23.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = if (compact) 22.sp else 29.sp,
        )
        Box(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f)) {
            AnswerGrid(quiz, onTap, compact, (if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).alpha(if (beforeAnswers) 0f else 1f))
            if (beforeAnswers) {
                val left = ceil((quiz.answersAt - now).coerceAtLeast(0) / 1000.0).toInt().coerceAtLeast(1)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = if (compact) 120.dp else 200.dp).align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(if (compact) 72.dp else 116.dp).border(8.dp, theme.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$left", color = theme.accent, fontFamily = theme.display, fontSize = if (compact) 36.sp else 60.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Antworten kommen gleich", color = theme.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AnswerGrid(quiz: PlayerEngine.QuizView, onTap: (Int, Long) -> Unit, compact: Boolean, modifier: Modifier) {
    val result = quiz.result
    fun state(pos: Int): FieldState = when {
        result != null && result.outcome != QuizOutcome.ANNULLED && pos == result.correctPosition -> FieldState.CORRECT
        result != null && result.outcome != QuizOutcome.ANNULLED && pos == result.chosenPosition -> FieldState.WRONG
        result != null -> if (pos == result.chosenPosition) FieldState.CHOSEN else FieldState.DIMMED
        quiz.chosen == pos -> FieldState.CHOSEN
        quiz.chosen >= 0 || quiz.stage == QuizStage.CLOSED -> FieldState.DIMMED
        else -> FieldState.OPEN
    }
    val enabled = quiz.stage == QuizStage.ANSWERS && quiz.chosen < 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
        for (row in 0 until 2) {
            Row(
                if (compact) Modifier.fillMaxWidth().height(78.dp) else Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
            ) {
                for (col in 0 until 2) {
                    val pos = row * 2 + col
                    val text = quiz.answers.getOrNull(pos) ?: continue
                    val st = state(pos)
                    AnswerField(
                        letter = 'A' + pos,
                        text = text,
                        state = st,
                        thumb = st == FieldState.CORRECT && result?.chosenPosition == pos,
                        chosenInfo = if (st == FieldState.CHOSEN && result == null) quiz.chosenElapsedMs?.let { "DEINE WAHL · ${seconds(it)}" } else null,
                        enabled = enabled,
                        compact = compact,
                        onTap = { time -> onTap(pos, time) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerField(
    letter: Char,
    text: String,
    state: FieldState,
    thumb: Boolean,
    chosenInfo: String?,
    enabled: Boolean,
    compact: Boolean,
    onTap: (Long) -> Unit,
    modifier: Modifier,
) {
    val theme = LocalBuzzerTheme.current
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val (bg, edge, fg) = when (state) {
        FieldState.OPEN -> Triple(theme.ground, theme.accent2, theme.text)
        FieldState.CHOSEN -> Triple(theme.accent, Color(0xFFFFE08A), Color(0xFF1A1300))
        FieldState.DIMMED -> Triple(theme.ground, theme.line, theme.muted)
        FieldState.CORRECT -> Triple(QuizGreen, QuizGreenEdge, Color.White)
        FieldState.WRONG -> Triple(QuizRed, QuizRedEdge, Color.White)
    }
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(if (state == FieldState.CORRECT || state == FieldState.WRONG) 3.dp else 2.dp, edge, shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // Gemessen wird der Zeitpunkt der Berührung, nicht der Verarbeitung.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnTap(down.uptimeMillis)
                }
            }
            .drawWithContent {
                drawContent()
                if (state == FieldState.WRONG) {
                    val inset = size.minDimension * 0.14f
                    val stroke = size.minDimension * 0.07f
                    val c = Color.White.copy(alpha = 0.92f)
                    drawLine(c, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, StrokeCap.Round)
                    drawLine(c, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
                }
            }
            .padding(if (compact) 8.dp else 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Box(
                Modifier
                    .size(if (compact) 22.dp else 30.dp)
                    .background(
                        when (state) {
                            FieldState.OPEN -> theme.accent
                            FieldState.CHOSEN -> Color(0xFF1A1300)
                            FieldState.DIMMED -> theme.line
                            else -> Color.White
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter.toString(),
                    color = when (state) {
                        FieldState.OPEN -> theme.ground
                        FieldState.CHOSEN -> theme.accent
                        FieldState.DIMMED -> theme.muted
                        FieldState.CORRECT -> QuizGreen
                        FieldState.WRONG -> QuizRed
                    },
                    fontFamily = theme.display,
                    fontSize = if (compact) 12.sp else 16.sp,
                )
            }
            Text(
                text,
                color = fg,
                fontSize = if (compact) 13.sp else 16.sp,
                fontWeight = if (state == FieldState.OPEN || state == FieldState.DIMMED) FontWeight.SemiBold else FontWeight.Bold,
                lineHeight = if (compact) 16.sp else 20.sp,
                textDecoration = if (state == FieldState.WRONG) TextDecoration.LineThrough else null,
                overflow = TextOverflow.Ellipsis,
            )
            if (chosenInfo != null && !compact) {
                Text(chosenInfo, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        if (thumb) Text("👍", fontSize = if (compact) 26.sp else 40.sp, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** Ergebnis-Banner über dem Feld nach der Auflösung. */
@Composable
fun QuizResultBanner(result: de.pabich.buzzer.core.Message.QuizResult, soundOn: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val (bg, text, sound) = when (result.outcome) {
        QuizOutcome.RIGHT -> Triple(QuizGreen, "Richtig!" + if (result.gained.isNotEmpty()) " ${result.gained}" else "", "Tusch")
        QuizOutcome.WRONG -> Triple(Color(0xFF8E1B1B), "Leider falsch", "Wah-wah-waaah")
        QuizOutcome.NONE -> Triple(Color(0xFF2C3150), "Zeit abgelaufen", "Wah-wah-waaah")
        QuizOutcome.ANNULLED -> Triple(theme.line, "Frage annulliert", "")
    }
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), color = Color.White, fontFamily = theme.display, fontSize = 22.sp, modifier = Modifier.weight(1f))
        if (soundOn && sound.isNotEmpty() && !result.corrected) Text("♪ $sound", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (result.corrected) Text("korrigiert", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Erklärung, Quelle und Challenge unter der Auflösung. */
@Composable
fun QuizExplanation(
    explanation: String,
    source: String,
    challenged: Boolean,
    onChallenge: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalBuzzerTheme.current
    val uri = LocalUriHandler.current
    var asking by remember { mutableStateOf(false) }
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(theme.panel).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (explanation.isNotEmpty()) Text(explanation, color = theme.text, fontSize = 14.sp, lineHeight = 19.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (source.isNotEmpty()) {
                TextButton(onClick = {
                    runCatching { uri.openUri(source) }.onFailure { Diagnostics.log("Quelle nicht zu öffnen: ${it.message}") }
                }) { Text("Quelle ansehen", color = theme.accent2, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.weight(1f))
            if (onChallenge != null) {
                if (challenged) {
                    Text("Challenge gesendet", color = theme.muted, fontSize = 13.sp)
                } else {
                    Box(
                        Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .border(2.dp, theme.accent, RoundedCornerShape(22.dp))
                            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); asking = true } }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("CHALLENGE", color = theme.accent, fontFamily = theme.display, fontSize = 16.sp) }
                }
            }
        }
    }
    if (asking && onChallenge != null) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Frage anfechten") },
            text = {
                Column {
                    Text("Der Spielleiter sieht deine Challenge und entscheidet. Begründung (optional):", fontSize = 14.sp)
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(MAX_CHAT_LENGTH) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onChallenge(reason); asking = false }) { Text("Anfechten") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Abbrechen") } },
        )
    }
}

/**
 * Eigene Bahn für Emoticons, solange die Antwortzeit läuft: Sie fliegen von rechts nach links
 * oberhalb des Fragenfelds und verdecken nie die Antworten.
 */
@Composable
fun EmoteLane(emotes: List<FlyingEmote>, onDone: (Long) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    BoxWithConstraints(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF10163A)),
    ) {
        val width = constraints.maxWidth.toFloat()
        if (emotes.isEmpty()) {
            Text(
                "Emoticons fliegen hier, solange die Zeit läuft",
                color = theme.line,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            )
        }
        for (emote in emotes) {
            key(emote.id) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    progress.animateTo(1f, tween(durationMillis = 2_800, easing = LinearEasing))
                    onDone(emote.id)
                }
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            val p = progress.value
                            translationX = (width - 110.dp.toPx()) * (1f - p) + ((emote.id % 3).toInt() * 10).dp.toPx() * (1f - p)
                            alpha = if (p < 0.8f) 1f else 1f - (p - 0.8f) / 0.2f
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(emote.emoji, fontSize = 30.sp)
                    Text(
                        emote.from,
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .widthIn(max = 70.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** Drei pulsierende Punkte: ein Teilnehmer tippt gerade eine Nachricht. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val transition = rememberInfiniteTransition(label = "tippt")
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(theme.accent2).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (i in 0 until 3) {
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 200, easing = LinearEasing), RepeatMode.Reverse),
                label = "punkt$i",
            )
            Box(Modifier.size(5.dp).alpha(a).background(theme.ground, CircleShape))
        }
    }
}

/** Kopfzeile über dem Fragenfeld: „FRAGE 4 / 12“ und Kategorie · Stufe. */
@Composable
fun QuizHeader(number: Int, total: Int, category: String, level: String, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("FRAGE $number / $total", color = theme.accent, fontFamily = theme.display, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Text(
            "${category.uppercase()} · ${level.uppercase()}",
            color = theme.accent2,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .border(1.dp, theme.accent2, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Fußzeile mit Name, Punkten und Platz. */
@Composable
fun ScoreStrip(name: String, view: PlayerEngine.View, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val rank = view.board.firstOrNull { it.isSelf }?.rank
    Row(
        modifier.fillMaxWidth().background(theme.panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = theme.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val places = rank?.let { " · Platz $it" } ?: ""
        val score = if (view.score.endsWith(" s")) view.score else "${view.score} " + if (view.score == "1") "Punkt" else "Punkte"
        Text("$score$places", color = theme.muted, fontSize = 15.sp)
    }
}

/** Spieler-Ansicht im Quiz (auch ohne laufende Frage). */
@Composable
fun QuizPlayerContent(
    view: PlayerEngine.View,
    name: String,
    emotes: List<FlyingEmote>,
    onEmoteDone: (Long) -> Unit,
    chat: List<ChatLine>,
    soundOn: Boolean,
    confettiSeq: Int,
    onTap: (Int, Long) -> Unit,
    onChallenge: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalBuzzerTheme.current
    val quiz = view.quiz
    Box(modifier.background(theme.ground)) {
        Column(Modifier.fillMaxSize()) {
            if (quiz == null) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    BigTitle("Quiz", size = 56.sp)
                    Text(view.hint, color = theme.text, fontSize = 18.sp, textAlign = TextAlign.Center)
                    if (view.showBoard && view.board.isNotEmpty() && view.board.any { it.score > 0 }) {
                        Scoreboard(
                            entries = view.board,
                            maxScore = view.boardMaxScore,
                            color = theme.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(theme.panel)
                                .padding(14.dp),
                        )
                    }
                    if (view.showBoard && chat.isNotEmpty()) ChatPanel(chat, theme.text, Modifier.fillMaxWidth())
                }
            } else {
                QuizHeader(quiz.number, quiz.total, quiz.category, quiz.level.label, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp))
                if (quiz.timerRunning) {
                    EmoteLane(emotes, onEmoteDone, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp))
                }
                val result = quiz.result
                if (result != null) {
                    QuizResultBanner(result, soundOn, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp))
                }
                QuizCard(
                    quiz = quiz,
                    onTap = onTap,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                if (quiz.stage == QuizStage.ANSWERS && quiz.chosen >= 0 || quiz.stage == QuizStage.CLOSED) {
                    Text(
                        if (quiz.chosen >= 0) "Antwort gesperrt – warte auf die anderen" else "Zeit abgelaufen – gleich kommt die Auflösung",
                        color = theme.muted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                if (result != null) {
                    QuizExplanation(
                        explanation = result.explanation,
                        source = result.source,
                        challenged = quiz.challenged,
                        onChallenge = onChallenge,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    )
                    if (chat.isNotEmpty()) ChatPanel(chat, theme.text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), maxLines = 3)
                }
                Spacer(Modifier.height(10.dp))
            }
            ScoreStrip(name, view)
        }
        Confetti(confettiSeq)
    }
}

/** Laufband am unteren Rand, solange der Spielleiter die Auflösung prüft. */
@Composable
fun ReviewTicker(modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val transition = rememberInfiniteTransition(label = "laufband")
    val p by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "laufband-position",
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(theme.accent)
            .clip(RoundedCornerShape(0.dp)),
    ) {
        val width = constraints.maxWidth.toFloat()
        Text(
            "BITTE WARTE KURZ – DIE ANTWORTEN WERDEN ÜBERPRÜFT   ✦   BITTE WARTE KURZ – DIE ANTWORTEN WERDEN ÜBERPRÜFT",
            color = theme.ground,
            fontFamily = theme.display,
            fontSize = 20.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .wrapContentWidth(unbounded = true, align = Alignment.Start)
                .graphicsLayer { translationX = width - p * (width + size.width) },
        )
    }
}

/** „Bereit?“-Abfrage des Spielleiters beim Spieler. */
@Composable
fun ReadyDialog(
    text: String = "Der Spielleiter möchte gleich starten.",
    onCancel: (() -> Unit)? = null,
    onReady: () -> Unit,
) {
    val theme = LocalBuzzerTheme.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("BEREIT?", fontFamily = theme.display, fontSize = 32.sp) },
        text = { Text(text, fontSize = 16.sp) },
        dismissButton = onCancel?.let { cancel -> { androidx.compose.material3.TextButton(onClick = cancel) { Text("Abfrage abbrechen") } } },
        confirmButton = {
            Box(
                Modifier
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(theme.accent)
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onReady() } }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("BEREIT!", color = theme.ground, fontFamily = theme.display, fontSize = 22.sp) }
        },
    )
}

/** Stand der „Bereit?“-Abfrage beim Spielleiter; sind alle bereit: „GO!“. */
@Composable
fun ReadyPanel(state: de.pabich.buzzer.core.MasterEngine.ReadyState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (state.allReady) QuizGreen else theme.panel)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.allReady) {
            Text("GO!", color = Color.White, fontFamily = theme.display, fontSize = 30.sp, modifier = Modifier.weight(1f))
            Text("Alle bereit", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        } else {
            Column(Modifier.weight(1f)) {
                Text("BEREIT: ${state.readyNames.size} / ${state.readyNames.size + state.waitingNames.size}", color = theme.accent, fontFamily = theme.display, fontSize = 18.sp)
                Text("Warte auf: ${state.waitingNames.joinToString()}", color = theme.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel) { Text("Abbrechen", color = theme.muted) }
        }
    }
}

/** „Get Ready“: fragt bei allen Spielern „Bereit?“ ab. Starten geht trotzdem jederzeit. */
@Composable
fun GetReadyButton(vm: de.pabich.buzzer.BuzzerViewModel, snapshot: de.pabich.buzzer.core.MasterEngine.Snapshot, modifier: Modifier = Modifier) {
    // Läuft die Abfrage schon, zeigt der Knopf nur den Stand – abbrechen geht über das Bereit-Feld oben.
    val ready = snapshot.ready
    GhostButton(
        when {
            ready == null -> "Get Ready"
            ready.allReady -> "GO!"
            else -> "Bereit: ${ready.readyNames.size} / ${ready.readyNames.size + ready.waitingNames.size} …"
        },
        { vm.askReady() },
        modifier,
        enabled = ready == null && !snapshot.paused && snapshot.players.any { it.connected && it.id != de.pabich.buzzer.BuzzerViewModel.LOCAL_PLAYER_ID },
    )
}
