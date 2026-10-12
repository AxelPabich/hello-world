package de.pabich.buzzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.pabich.buzzer.core.ScoreEntry

/** Balken für einen Punktestand, relativ zum höchsten Punktestand. */
@Composable
fun ScoreBar(score: Long, maxScore: Long, color: Color, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val fraction = if (maxScore > 0) (score.toFloat() / maxScore).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .height(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(if (highlight) color else color.copy(alpha = 0.65f)),
        )
    }
}

/** Rangliste der Spieler mit Balkengrafik; der eigene Eintrag ist hervorgehoben. */
@Composable
fun Scoreboard(entries: List<ScoreEntry>, maxScore: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text("Punktestand", color = color, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        entries.forEachIndexed { index, entry ->
            // Lücke andeuten, wenn Plätze ausgeblendet sind (gleiche Ränge zählen mit).
            if (index > 0 && entry.rank > index + 1) {
                Text("…", color = color.copy(alpha = 0.7f), modifier = Modifier.padding(start = 4.dp))
            }
            val weight = if (entry.isSelf) FontWeight.Bold else FontWeight.Normal
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${entry.rank}.", color = color, fontWeight = weight, modifier = Modifier.width(36.dp))
                Text(
                    entry.label,
                    color = color,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(110.dp),
                )
                ScoreBar(entry.score, maxScore, color, Modifier.weight(1f), highlight = entry.isSelf)
                Text(
                    entry.scoreText,
                    color = color,
                    fontWeight = weight,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}
