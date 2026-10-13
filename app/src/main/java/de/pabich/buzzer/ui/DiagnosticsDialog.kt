package de.pabich.buzzer.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.BuzzerViewModel
import de.pabich.buzzer.DiagnoseUpload
import de.pabich.buzzer.Diagnostics

/**
 * Diagnosebericht: Systeminformationen nur auf Wunsch. Verschicken über „Teilen“, als Spieler
 * an den Spielleiter oder als Spielleiter direkt ins private GitHub-Repository.
 */
@Composable
fun DiagnosticsDialog(vm: BuzzerViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var includeSystem by remember { mutableStateOf(false) }
    fun report() = Diagnostics.buildReport(context, vm.status, includeSystem)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnose senden") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    "Wozu: Gerätemodell, Android-Version, Play-Dienste, Standort an/aus und die erteilten Berechtigungen " +
                        "zeigen, warum etwas auf einem bestimmten Handy nicht klappt (z. B. ausgeschalteter Standort).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Schutz: keine Standortdaten, keine Kontakte, keine Telefonnummer. Der Bericht geht über die direkte " +
                        "Verbindung zum Spielleiter und landet in seinem privaten GitHub-Repository – oder nur dorthin, " +
                        "wohin du ihn selbst teilst.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (vm.canSendToMaster) {
                    TextButton(onClick = { vm.sendDiagnoseToMaster(report()); onDismiss() }) { Text("An den Spielleiter senden") }
                }
                if (vm.isMaster) {
                    TextButton(onClick = { vm.addOwnReport(report()); onDismiss() }) { Text("In GitHub ablegen") }
                }
                Row {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    TextButton(onClick = {
                        Diagnostics.share(context, report())
                        onDismiss()
                    }) { Text("Teilen") }
                }
            }
        },
    )
}

/** Anleitung beim ersten Hochladen: Repository und Fine-grained Token anlegen, Token speichern. */
@Composable
fun TokenGuideDialog(vm: BuzzerViewModel) {
    if (!vm.showTokenGuide) return
    val uri = LocalUriHandler.current
    var token by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf(vm.diagnoseUpload.repo) }
    AlertDialog(
        onDismissRequest = { vm.showTokenGuide = false },
        title = { Text("Diagnosen in GitHub ablegen") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("Einmalig einrichten – danach landen alle Berichte automatisch in deinem privaten Repository.", fontSize = 14.sp)
                Step("1. Privates Repository anlegen", "Name „buzzer-diagnose“, „Private“ wählen, Haken bei „Add a README file“.")
                TextButton(onClick = { runCatching { uri.openUri(DiagnoseUpload.NEW_REPO_URL) } }) { Text("github.com/new öffnen") }
                Step(
                    "2. Fine-grained Token anlegen",
                    "Name „Buzzer-Diagnose“, Ablauf z. B. 1 Jahr. Repository access: „Only select repositories“ → buzzer-diagnose. " +
                        "Permissions → Repository permissions → Contents: „Read and write“ (alles andere aus). " +
                        "„Generate token“ und den Token kopieren – GitHub zeigt ihn nur einmal.",
                )
                TextButton(onClick = { runCatching { uri.openUri(DiagnoseUpload.NEW_TOKEN_URL) } }) { Text("Token-Seite öffnen") }
                Step("3. Hier einfügen", "Der Token bleibt nur auf diesem Gerät, verschlüsselt im Android-Keystore. Er darf ausschließlich in dieses Repository schreiben; löschen kannst du ihn jederzeit auf GitHub.")
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it.trim() },
                    label = { Text("Repository (Besitzer/Name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Token (github_pat_…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = token.length >= 20 && repo.contains('/'), onClick = { vm.saveTokenAndUpload(token, repo) }) {
                Text("Prüfen und speichern")
            }
        },
        dismissButton = { TextButton(onClick = { vm.showTokenGuide = false }) { Text("Später") } },
    )
}

@Composable
private fun Step(title: String, text: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
    Text(text, fontSize = 14.sp)
}

/** Die Suche braucht den eingeschalteten Standort (manche Geräte melden sonst Fehler 8034). */
@Composable
fun LocationDialog(vm: BuzzerViewModel) {
    if (!vm.locationOff) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { vm.locationOff = false },
        title = { Text("Standort einschalten") },
        text = {
            Text(
                "Android verlangt für die Suche nach Gruppen in der Nähe den eingeschalteten Standort. " +
                    "Buzzer liest den Standort nicht aus und verschickt ihn nicht. " +
                    "Am schnellsten: Schnelleinstellungen herunterziehen und „Standort“ antippen.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                vm.locationOff = false
            }) { Text("Einstellungen öffnen") }
        },
        dismissButton = { TextButton(onClick = { vm.searchGroups(ignoreLocation = true) }) { Text("Trotzdem suchen") } },
    )
}
