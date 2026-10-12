package de.pabich.buzzer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.ChatLine
import de.pabich.buzzer.core.Display
import de.pabich.buzzer.core.PlayerEngine
import kotlin.random.Random

/** Legacy-Helfer für Stellen, die nur eine Fläche einfärben (z. B. Statuspunkte in Listen). */
@Composable
fun Display.color(): Color = LocalBuzzerTheme.current.state(this).core

/**
 * Die ganze Fläche ist der Buzzer. Gemeldet wird der Zeitstempel des Touch-Ereignisses
 * (`uptimeMillis`), nicht der Zeitpunkt der Verarbeitung. In der Mitte glüht ein großer runder
 * Buzzer in der Zustandsfarbe; zwischen den Runden kommen Rangliste und Nachrichten dazu.
 */
@Composable
fun BuzzerPad(
    view: PlayerEngine.View,
    onDown: (Long) -> Unit,
    onUp: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    chat: List<ChatLine> = emptyList(),
    confettiSeq: Int = 0,
) {
    val theme = LocalBuzzerTheme.current
    val colors = theme.state(view.display)
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)
    val pause = !compact && view.showBoard
    Column(modifier = modifier.background(theme.ground)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.ground)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        currentOnDown(down.uptimeMillis)
                        var upTime = down.uptimeMillis
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            upTime = change.uptimeMillis
                            if (!change.pressed) break
                        }
                        currentOnUp(upTime)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                val size = when {
                    compact -> 110.dp
                    pause -> 130.dp
                    else -> 230.dp
                }
                BuzzerCircle(view, colors, size)
                if (view.hint.isNotEmpty()) {
                    Text(
                        text = view.hint.uppercase(),
                        color = colors.text,
                        fontFamily = theme.display,
                        fontSize = if (compact) 18.sp else if (pause) 24.sp else 32.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                // Vor jeder Freigabe: beide Zeitlimits, damit jeder das Risiko abwägen kann.
                if (!compact && (pause || view.display == Display.BLACK)) {
                    Text(
                        text = limitsText(view.rules),
                        color = theme.muted,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                if (pause && view.board.isNotEmpty()) {
                    Scoreboard(
                        entries = view.board,
                        maxScore = view.boardMaxScore,
                        color = colors.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .background(theme.panel.copy(alpha = 0.85f), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .padding(14.dp),
                    )
                }
                if (pause && chat.isNotEmpty()) {
                    ChatPanel(chat, colors.text, Modifier.fillMaxWidth())
                }
            }
            if (!compact) Confetti(confettiSeq)
        }
        if (!compact) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                view.timer?.let { TimerBar(it, theme.accent, Modifier.fillMaxWidth()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Punkte: ${view.score}", color = theme.muted, fontSize = 15.sp)
                    if (view.rules.profileName.isNotEmpty()) {
                        Text("Profil: ${view.rules.profileName}", color = theme.muted, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/** Runder Buzzer: glüht in der Zustandsfarbe, pulsiert bei Gelb, wackelt bei Frühstart. */
@Composable
private fun BuzzerCircle(view: PlayerEngine.View, colors: StateColors, size: Dp) {
    val theme = LocalBuzzerTheme.current
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (view.display == Display.YELLOW) 1.07f else 1f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "scale",
    )
    val shake = remember { Animatable(0f) }
    LaunchedEffect(view.display, view.hint) {
        if (view.display == Display.GRAY && view.hint == "Frühstart") {
            for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, tween(55))
        }
    }
    val big = when {
        view.place > 0 -> view.place.toString()
        view.display == Display.YELLOW -> "!"
        view.display == Display.BLUE -> "…"
        view.display == Display.GRAY -> "×"
        else -> "?"
    }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = shake.value * density
            }
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(colors.glow, Color.Transparent), radius = this.size.minDimension * 0.85f),
                    radius = this.size.minDimension * 0.85f,
                )
            }
            .background(colors.core, CircleShape)
            .border(width = size / 24, color = theme.text, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(big, color = colors.text, fontFamily = theme.display, fontSize = (size.value * 0.5f).sp)
    }
}

/** Konfetti, wenn auf diesem Gerät „Richtig“ gefeiert wird. Fängt keine Berührungen ab. */
@Composable
private fun Confetti(seq: Int) {
    if (seq == 0) return
    val progress = remember(seq) { Animatable(0f) }
    LaunchedEffect(seq) { progress.animateTo(1f, tween(2_600, easing = LinearEasing)) }
    val theme = LocalBuzzerTheme.current
    val pieces = remember(seq) {
        val r = Random(seq)
        List(70) {
            ConfettiPiece(
                x = r.nextFloat(),
                delay = r.nextFloat() * 0.3f,
                speed = 0.7f + r.nextFloat() * 0.6f,
                drift = (r.nextFloat() - 0.5f) * 0.3f,
                color = listOf(theme.accent, theme.accent2, Color(0xFFFF5DA2), Color.White, Color(0xFF7CFF8A))[it % 5],
            )
        }
    }
    if (progress.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        for (piece in pieces) {
            val t = ((p - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val x = (piece.x + piece.drift * t) * size.width
            val y = -20f + t * piece.speed * size.height * 1.1f
            drawRect(
                color = piece.color.copy(alpha = 1f - t * 0.6f),
                topLeft = Offset(x, y),
                size = Size(14f, 22f),
            )
        }
    }
}

private class ConfettiPiece(val x: Float, val delay: Float, val speed: Float, val drift: Float, val color: Color)
