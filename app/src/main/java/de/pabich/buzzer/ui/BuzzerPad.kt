package de.pabich.buzzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.core.Display
import de.pabich.buzzer.core.PlayerEngine

fun Display.color(): Color = when (this) {
    Display.BLACK -> Color.Black
    Display.BLUE -> Color(0xFF1565C0)
    Display.YELLOW -> Color(0xFFFFD600)
    Display.PRESSED -> Color(0xFF8A7A00)
    Display.GREEN -> Color(0xFF2E7D32)
    Display.RED -> Color(0xFFC62828)
    Display.PINK -> Color(0xFFE91E63)
    Display.GRAY -> Color(0xFF616161)
}

private fun Display.textColor(): Color = if (this == Display.YELLOW) Color.Black else Color.White

/**
 * Die ganze Fläche ist der Buzzer. Gemeldet wird der Zeitstempel des Touch-Ereignisses
 * (`uptimeMillis`), nicht der Zeitpunkt der Verarbeitung. Zwischen den Runden zeigt sie
 * zusätzlich die Rangliste (nicht im kompakten Feld des mitspielenden Masters).
 */
@Composable
fun BuzzerPad(
    view: PlayerEngine.View,
    onDown: (Long) -> Unit,
    onUp: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)
    val textColor = view.display.textColor()
    val showBoard = !compact && view.showBoard && view.board.isNotEmpty()
    Column(
        modifier = modifier
            .background(view.display.color())
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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (view.place > 0) {
                    Text(
                        text = view.place.toString(),
                        color = textColor,
                        fontSize = if (compact || showBoard) 96.sp else 180.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (view.hint.isNotEmpty()) {
                    Text(
                        text = view.hint,
                        color = textColor,
                        fontSize = if (compact) 18.sp else 26.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
        if (showBoard) {
            Scoreboard(
                entries = view.board,
                maxScore = view.boardMaxScore,
                color = textColor,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(horizontal = 20.dp),
            )
        }
        Text(
            text = "Punkte: ${view.score}",
            color = textColor,
            fontSize = 16.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}
