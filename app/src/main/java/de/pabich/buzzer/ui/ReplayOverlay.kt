package de.pabich.buzzer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.core.Message
import de.pabich.buzzer.core.formatScore
import kotlin.math.floor

/**
 * Spielverlauf als Wettrennen: Runde für Runde wachsen die Balken, die Zeilen tauschen die
 * Plätze; insgesamt [Message.Replay.durationMs]. Danach bleibt der Endstand stehen.
 * Erstes Tippen springt zum Endstand (mit Siegerfanfare), zweites Tippen schließt – oder der
 * Spielleiter macht weiter (nur auf diesem Gerät).
 */
@Composable
fun ReplayOverlay(replay: Message.Replay, onDone: () -> Unit, onMusicEnd: () -> Unit = {}, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val done by rememberUpdatedState(onDone)
    val stopMusic by rememberUpdatedState(onMusicEnd)
    val progress = remember(replay) { Animatable(0f) }
    var atEnd by remember(replay) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(replay) {
        progress.animateTo(1f, tween(replay.durationMs.toInt(), easing = LinearEasing))
        // Endstand bleibt stehen; die Jagdmusik endet, die Siegerfanfare kommt.
        atEnd = true
        stopMusic()
    }
    val frames = replay.frames
    val rounds = (frames.size - 1).coerceAtLeast(1)
    val position = ((progress.value - 0.1f) / 0.72f).coerceIn(0f, 1f) * rounds
    val index = floor(position).toInt().coerceIn(0, rounds - 1)
    val fraction = position - index
    val scores = replay.labels.indices.map { k ->
        val from = frames.getOrNull(index)?.getOrNull(k) ?: 0L
        val to = frames.getOrNull(index + 1)?.getOrNull(k) ?: from
        from + (to - from) * fraction
    }
    val maxScore = frames.flatten().maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val order = scores.indices.sortedByDescending { scores[it] }
    val rankOf = IntArray(scores.size).also { ranks -> order.forEachIndexed { pos, k -> ranks[k] = pos } }
    val shownRound = if (progress.value < 0.1f) 0 else (index + if (fraction > 0.5f) 1 else 0).coerceAtMost(rounds)
    val rowHeight = 54.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.ground.copy(alpha = 0.97f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (atEnd) {
                    done()
                } else {
                    atEnd = true
                    stopMusic()
                    scope.launch { progress.snapTo(1f) }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (atEnd) "ENDSTAND" else "SPIELVERLAUF", color = theme.accent, fontFamily = theme.display, fontSize = 40.sp)
            Text(
                when {
                    atEnd -> "Nach $rounds ${if (rounds == 1) "Runde" else "Runden"} · Tippen schließt"
                    shownRound == 0 -> "Start · Tippen springt zum Endstand"
                    else -> "Nach Runde $shownRound von $rounds · Tippen springt zum Endstand"
                },
                color = theme.accent2,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(rowHeight * scores.size)) {
                replay.labels.forEachIndexed { k, label ->
                    key(k) {
                        val y by animateDpAsState(
                            targetValue = rowHeight * rankOf[k],
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                            label = "row$k",
                        )
                        val self = k == replay.selfIndex
                        Row(
                            modifier = Modifier
                                .offset(y = y)
                                .fillMaxWidth()
                                .height(rowHeight - 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(theme.panel)
                                .then(if (self) Modifier.border(2.dp, theme.accent, RoundedCornerShape(12.dp)) else Modifier)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.size(30.dp).background(if (rankOf[k] == 0) theme.accent else theme.line, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${rankOf[k] + 1}", color = if (rankOf[k] == 0) theme.ground else theme.text, fontFamily = theme.display, fontSize = 16.sp)
                            }
                            Text(
                                label,
                                color = theme.text,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(96.dp),
                            )
                            Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(theme.line)) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth((scores[k] / maxScore).toFloat().coerceIn(0f, 1f))
                                        .background(if (self) theme.accent else theme.accent2),
                                )
                            }
                            Text(
                                formatScore(Math.round(scores[k]).toLong(), replay.scoring),
                                color = theme.text,
                                fontFamily = theme.display,
                                fontSize = 18.sp,
                                modifier = Modifier.width(64.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
