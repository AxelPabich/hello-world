package de.pabich.buzzer.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.ChatLine
import de.pabich.buzzer.FlyingEmote
import de.pabich.buzzer.core.PlayerEngine
import java.util.Locale

fun seconds(ms: Long): String = String.format(Locale.GERMANY, "%.1f s", ms / 1000.0)

/** Text für die Limits, die Spieler vor der Freigabe sehen. */
fun limitsText(rules: PlayerEngine.Rules): String =
    "Reaktion: " + (rules.reactionLimitMs?.let { "max. ${seconds(it)}" } ?: "ohne Limit") +
        " · Antwort: " + (rules.answerLimitMs?.let { "max. ${seconds(it)}" } ?: "ohne Limit")

/** Uhrzeit (uptime), die in jedem Frame neu gelesen wird – für laufende Zeitbalken. */
@Composable
private fun frameTime(key: Any?): Long {
    val now by produceState(SystemClock.uptimeMillis(), key) {
        while (true) {
            withFrameMillis { }
            value = SystemClock.uptimeMillis()
        }
    }
    return now
}

/** Reaktionszeit: Balken füllt sich. Antwortzeit: Balken läuft rückwärts. Jeweils mit Zahl. */
@Composable
fun TimerBar(timer: PlayerEngine.Timer, color: Color, modifier: Modifier = Modifier) {
    val now = frameTime(timer)
    val elapsed = (now - timer.startAt).coerceIn(0, timer.durationMs)
    val (fraction, text) = when (timer.kind) {
        PlayerEngine.TimerKind.REACTION ->
            if (now < timer.startAt) {
                0f to "Max. Reaktionszeit ${seconds(timer.durationMs)}"
            } else {
                elapsed.toFloat() / timer.durationMs to "Reaktion: ${seconds(elapsed)} / ${seconds(timer.durationMs)}"
            }
        PlayerEngine.TimerKind.ANSWER -> {
            val remaining = timer.durationMs - elapsed
            remaining.toFloat() / timer.durationMs to "Antwortzeit: noch ${seconds(remaining)}"
        }
    }
    TimeBar(fraction, text, color, modifier)
}

@Composable
fun TimeBar(fraction: Float, text: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text, color = color, fontSize = 16.sp)
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.25f)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
        }
    }
}

/** Countdown für den Master-Bildschirm, gerechnet ab [deadline] (uptime). */
@Composable
fun AnswerCountdown(deadline: Long, durationMs: Long, color: Color, modifier: Modifier = Modifier) {
    val now = frameTime(deadline)
    val remaining = (deadline - now).coerceIn(0, durationMs)
    TimeBar(remaining.toFloat() / durationMs, "Antwortzeit: noch ${seconds(remaining)}", color, modifier)
}

// ---------------------------------------------------------------------- Emoticons

/** Leiste am oberen Rand: Emoticon-Knöpfe und weitere Knöpfe; gehört nicht zur Buzzer-Fläche. */
@Composable
fun TopBar(
    emojis: List<String>,
    onEmoji: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val theme = LocalBuzzerTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.panel)
            .drawBehind {
                val line = 2.dp.toPx()
                drawRect(theme.accent, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (emoji in emojis) BarButton(emoji, onClick = { onEmoji(emoji) })
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun BarButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f))
    }
}

/** Auffliegende Emoticons am rechten Rand. Fängt keine Berührungen ab. */
@Composable
fun EmoteOverlay(emotes: List<FlyingEmote>, onDone: (Long) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val travel = maxHeight * 0.7f
        for (emote in emotes) {
            key(emote.id) {
                FlyingEmoteItem(emote, travel, onDone, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun FlyingEmoteItem(emote: FlyingEmote, travel: Dp, onDone: (Long) -> Unit, modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 2800, easing = LinearEasing))
        onDone(emote.id)
    }
    Column(
        modifier = modifier
            .padding(end = (8 + (emote.id % 3).toInt() * 36).dp, bottom = 64.dp)
            .graphicsLayer {
                val p = progress.value
                translationY = -p * travel.toPx()
                val pop = if (p < 0.08f) p / 0.08f else 1f
                scaleX = pop
                scaleY = pop
                alpha = if (p < 0.7f) 1f else 1f - (p - 0.7f) / 0.3f
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emote.emoji, fontSize = 48.sp)
        Text(
            emote.from,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------- Kurznachrichten

@Composable
fun ChatPanel(lines: List<ChatLine>, color: Color, modifier: Modifier = Modifier, maxLines: Int = 4) {
    if (lines.isEmpty()) return
    Column(modifier) {
        for (line in lines.takeLast(maxLines)) {
            Text(
                text = "${line.from}${if (line.toAll && !line.outgoing) " (an alle)" else ""}: ${line.text}",
                color = if (line.outgoing) color.copy(alpha = 0.7f) else color,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
