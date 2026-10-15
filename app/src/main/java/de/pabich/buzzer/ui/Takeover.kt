package de.pabich.buzzer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.BuzzerViewModel
import de.pabich.buzzer.core.GameKind

/** Der Spielleiter ist weg (Punkt 59): übernehmen, auf den Nachfolger warten oder gehen. */
@Composable
fun TakeoverScreen(vm: BuzzerViewModel) {
    val theme = LocalBuzzerTheme.current
    val t = vm.takeover ?: return
    BackHandler { vm.leave() }
    val now = frameTime(t.deadline)
    val left = ((t.deadline - now + 999) / 1000).coerceAtLeast(0)
    val s = t.succession
    ThemedScreen {
        ScrollPage {
            Kicker("Spielleitung")
            BigTitle(
                when {
                    t.taker != null -> "${t.taker} übernimmt"
                    t.expected != null -> "Neuer Spielleiter: ${t.expected}"
                    else -> "Wer übernimmt?"
                },
                size = 34.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(t.reason, color = theme.text, fontSize = 17.sp)
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                val game = when (s.kind) {
                    GameKind.QUIZ -> if (s.quizNext in 1 until s.quiz.size) "Quiz, weiter mit Frage ${s.quizNext + 1} von ${s.quiz.size}" else "Quiz"
                    GameKind.BUZZER -> "Buzzer, ${s.round} Runden gespielt"
                    null -> "Spielart noch nicht gewählt"
                }
                Text(game, color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                val scores = s.scores.sortedByDescending { it.second }.take(8)
                if (scores.any { it.second > 0 }) {
                    Text("Punkte bleiben: " + scores.joinToString(" · ") { "${it.first} ${it.second}" }, color = theme.muted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (t.taker != null || t.expected != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = theme.accent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (t.taker != null) "Verbinde mit ${t.taker} …" else "Warte, bis ${t.expected} bereit ist …",
                        color = theme.muted,
                        fontSize = 16.sp,
                    )
                }
            } else {
                Text(
                    "Einer von euch kann das Spiel weiterführen – die Punkte und der Spielstand bleiben. " +
                        "Wer zuerst übernimmt, leitet; alle anderen verbinden sich automatisch.",
                    color = theme.text,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Ich übernehme", { vm.takeOver() }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Meldet sich niemand, endet das Spiel in $left s.",
                color = if (left <= 10) theme.accent else theme.muted,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(16.dp))
            StatusText(vm)
            GhostButton("Spiel verlassen", { vm.leave() }, Modifier.fillMaxWidth())
        }
    }
}

/** Spielleiter: an wen soll die Leitung gehen? */
@Composable
fun HandOverDialog(candidates: List<String>, onHandOver: (String) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalBuzzerTheme.current
    var chosen by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spielleitung übergeben") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Punkte und Spielstand gehen mit. Du spielst danach als Spieler weiter.")
                Spacer(Modifier.height(4.dp))
                if (candidates.isEmpty()) Text("Kein Spieler verbunden.", color = theme.muted)
                candidates.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = name }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == name, onClick = { chosen = name })
                        Text(name, fontSize = 17.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let(onHandOver) }, enabled = chosen != null) { Text("Übergeben") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Spielleiter verlässt das Spiel: für alle beenden oder die Spieler übernehmen lassen. */
@Composable
fun MasterLeaveDialog(
    canOpen: Boolean,
    onEnd: (deleteDiagnostics: Boolean) -> Unit,
    onOpen: (deleteDiagnostics: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var delete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spiel verlassen?") },
        text = {
            Column {
                Text(
                    if (canOpen) {
                        "„Spieler übernehmen“: Einer der Spieler kann das Spiel mit allen Punkten weiterführen " +
                            "(1 Minute Zeit). „Für alle beenden“ trennt alle Spieler."
                    } else {
                        "Alle Spieler werden getrennt."
                    },
                )
                Spacer(Modifier.padding(4.dp))
                CheckRow("Diagnosedaten löschen", delete) { delete = it }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canOpen) TextButton(onClick = { onOpen(delete) }) { Text("Spieler übernehmen") }
                TextButton(onClick = { onEnd(delete) }) { Text("Für alle beenden") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
