package de.pabich.buzzer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.pabich.buzzer.core.MasterEngine
import de.pabich.buzzer.core.ScoringMode
import de.pabich.buzzer.core.Settings
import de.pabich.buzzer.core.StartMode
import java.util.Locale

private fun Long.seconds() = String.format(Locale.GERMANY, "%.1f", this / 1000.0)
private fun String.secondsToMs(fallback: Long) =
    replace(',', '.').toDoubleOrNull()?.let { (it * 1000).toLong() } ?: fallback

@Composable
fun SettingsDialog(
    snapshot: MasterEngine.Snapshot,
    onDismiss: () -> Unit,
    onSave: (Settings) -> Unit,
    onNewGame: () -> Unit,
) {
    val s = snapshot.settings
    var startMode by remember { mutableStateOf(s.startMode) }
    var scoring by remember { mutableStateOf(s.scoring) }
    var masterPlays by remember { mutableStateOf(s.masterPlays) }
    var timeLimit by remember { mutableStateOf(s.timeLimitMs.seconds()) }
    var delayMin by remember { mutableStateOf(s.delayMinMs.seconds()) }
    var delayMax by remember { mutableStateOf(s.delayMaxMs.seconds()) }
    var sdMin by remember { mutableStateOf(s.suddenDeathDelayMinMs.seconds()) }
    var sdMax by remember { mutableStateOf(s.suddenDeathDelayMaxMs.seconds()) }
    var tie by remember { mutableStateOf(s.tieThresholdMs.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section("Startmodus")
                Choice("A – Halten und Loslassen", startMode == StartMode.HOLD_RELEASE) { startMode = StartMode.HOLD_RELEASE }
                Choice("B – Tippen", startMode == StartMode.TAP) { startMode = StartMode.TAP }

                Section("Zeiten (Sekunden)")
                NumberField("Zeitlimit pro Runde", timeLimit, enabled = !snapshot.timeLimitLocked) { timeLimit = it }
                Row {
                    NumberField("Verzögerung min", delayMin, Modifier.weight(1f)) { delayMin = it }
                    Spacer(Modifier.width(8.dp))
                    NumberField("max", delayMax, Modifier.weight(1f)) { delayMax = it }
                }
                Row {
                    NumberField("Sudden Death min", sdMin, Modifier.weight(1f)) { sdMin = it }
                    Spacer(Modifier.width(8.dp))
                    NumberField("max", sdMax, Modifier.weight(1f)) { sdMax = it }
                }
                NumberField("Gleichstands-Schwelle (ms)", tie) { tie = it }

                Section("Wertung" + if (snapshot.scoringLocked) " (gesperrt bis „Neues Spiel“)" else "")
                Choice("Richtige Antworten", scoring == ScoringMode.CORRECT_ANSWERS, !snapshot.scoringLocked) {
                    scoring = ScoringMode.CORRECT_ANSWERS
                }
                Choice("Restzeit aufaddieren", scoring == ScoringMode.REMAINING_TIME, !snapshot.scoringLocked) {
                    scoring = ScoringMode.REMAINING_TIME
                }

                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Master spielt mit", Modifier.weight(1f))
                    Switch(checked = masterPlays, onCheckedChange = { masterPlays = it })
                }
                if (masterPlays) {
                    Text(
                        "Hinweis: Fair nur, wenn du die Antworten nicht kennst.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                TextButton(onClick = onNewGame, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Neues Spiel (Punkte zurücksetzen)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    s.copy(
                        startMode = startMode,
                        scoring = scoring,
                        masterPlays = masterPlays,
                        timeLimitMs = timeLimit.secondsToMs(s.timeLimitMs),
                        delayMinMs = delayMin.secondsToMs(s.delayMinMs),
                        delayMaxMs = delayMax.secondsToMs(s.delayMaxMs),
                        suddenDeathDelayMinMs = sdMin.secondsToMs(s.suddenDeathDelayMinMs),
                        suddenDeathDelayMaxMs = sdMax.secondsToMs(s.suddenDeathDelayMaxMs),
                        tieThresholdMs = tie.toLongOrNull() ?: s.tieThresholdMs,
                    ),
                )
            }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.padding(vertical = 4.dp),
    )
}
