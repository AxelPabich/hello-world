package de.pabich.buzzer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.pabich.buzzer.Diagnostics

/** Fragt, ob Systeminformationen mitgeschickt werden sollen, und öffnet dann „Teilen“. */
@Composable
fun DiagnosticsDialog(currentError: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var includeSystem by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnose senden") },
        text = {
            Column {
                Text("Der Bericht enthält die Fehlermeldung und die letzten Ereignisse der App.")
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(value = includeSystem, onValueChange = { includeSystem = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeSystem, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Systeminformationen mitsenden")
                }
                Text(
                    "Gerät, Android-Version, Play-Dienste, Standort an/aus und erteilte Berechtigungen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Diagnostics.share(context, Diagnostics.buildReport(context, currentError, includeSystem))
                onDismiss()
            }) { Text("Teilen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
