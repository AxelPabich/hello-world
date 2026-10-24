package de.pabich.buzzer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.ChatLine
import de.pabich.buzzer.ConfigStore
import de.pabich.buzzer.ReceivedProfile
import de.pabich.buzzer.core.ALL_ADDRESS
import de.pabich.buzzer.core.ConfigFormat
import de.pabich.buzzer.core.MAX_CHAT_LENGTH
import de.pabich.buzzer.core.StoredProfile
import de.pabich.buzzer.describeProfile

@Composable
fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** „Gruppe verlassen/schließen“ mit der Möglichkeit, alle Diagnosedaten zu löschen. */
@Composable
fun LeaveDialog(title: String, text: String?, confirm: String, onConfirm: (deleteDiagnostics: Boolean) -> Unit, onDismiss: () -> Unit) {
    var delete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                text?.let { Text(it) }
                Spacer(Modifier.padding(4.dp))
                CheckRow("Diagnosedaten löschen", delete) { delete = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(delete) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Kurznachricht an einen Teilnehmer oder an alle. [recipients]: Adresse zu Name. */
@Composable
fun ChatDialog(
    recipients: List<Pair<String, String>>,
    onSend: (to: String, toName: String, text: String) -> Unit,
    onDismiss: () -> Unit,
    onTyping: () -> Unit = {},
    /** Bisheriger Verlauf (der Spielleiter liest ihn jederzeit); leer = nur Eingabe. */
    history: List<ChatLine> = emptyList(),
    /** Senden ist nur in den Pausen möglich; Lesen immer. */
    canSend: Boolean = true,
) {
    var to by remember { mutableStateOf(ALL_ADDRESS) }
    var text by remember { mutableStateOf("") }
    val all = listOf(ALL_ADDRESS to "Alle") + recipients
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kurznachricht") },
        text = {
            Column {
                if (history.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                        for (line in history) {
                            Text(
                                "${line.from}${if (line.toAll && !line.outgoing) " (an alle)" else ""}: ${line.text}",
                                fontSize = 14.sp,
                                color = if (line.outgoing) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (!canSend) {
                    Text("Senden ist nur in den Pausen möglich.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    for ((address, name) in all) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = to == address, onClick = { to = address }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = to == address, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(name)
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(MAX_CHAT_LENGTH)
                        if (text.isNotEmpty()) onTyping()
                    },
                    label = { Text("Nachricht (${text.length}/$MAX_CHAT_LENGTH)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSend && text.isNotBlank(),
                onClick = {
                    onSend(to, all.first { it.first == to }.second, text)
                    onDismiss()
                },
            ) { Text("Senden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (history.isNotEmpty() || !canSend) "Schließen" else "Abbrechen") } },
    )
}

/** Vom Spielleiter gesendetes Profil ansehen und unter wählbarem Namen speichern. */
@Composable
fun ReceivedProfileDialog(profile: ReceivedProfile, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profil „${profile.name}“ erhalten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((label, value) in describeProfile(profile.values)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Speichern als") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Verwerfen") } },
    )
}

/** Auswahl, was in die .buzzconf-Datei soll. */
@Composable
fun ExportDialog(profiles: List<StoredProfile>, onExport: (ConfigFormat.Parts) -> Unit, onDismiss: () -> Unit) {
    var nameAndSettings by remember { mutableStateOf(true) }
    var withProfiles by remember { mutableStateOf(true) }
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { profiles.forEach { put(it.name, true) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen exportieren") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CheckRow("Name und lokale Einstellungen", nameAndSettings) { nameAndSettings = it }
                CheckRow("Profile", withProfiles, enabled = profiles.isNotEmpty()) { withProfiles = it }
                if (withProfiles) {
                    for (p in profiles) {
                        Row(Modifier.padding(start = 24.dp)) {
                            CheckRow(p.name, selected[p.name] == true) { selected[p.name] = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val names = if (withProfiles) selected.filterValues { it }.keys.toSet() else emptySet()
                onExport(ConfigFormat.Parts(name = nameAndSettings, settings = nameAndSettings, profileNames = names))
            }) { Text("Datei wählen …") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Zeigt den Inhalt einer .buzzconf-Datei; übernommen wird nur, was angekreuzt ist. */
@Composable
fun ImportDialog(
    content: ConfigFormat.Content,
    profileExists: (String) -> Boolean,
    onImport: (name: Boolean, settings: Boolean, choices: List<ConfigStore.ProfileChoice>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(content.playerName != null) }
    var settings by remember { mutableStateOf(content.settings != null) }
    val importFlags = remember { mutableStateMapOf<String, Boolean>().apply { content.profiles.forEach { put(it.name, true) } } }
    val replaceFlags = remember { mutableStateMapOf<String, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen importieren") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Dateiformat Version ${content.version}", style = MaterialTheme.typography.bodySmall)
                content.playerName?.let { CheckRow("Name: $it", name) { v -> name = v } }
                if (content.settings != null) CheckRow("Lokale Einstellungen", settings) { settings = it }
                if (content.profiles.isNotEmpty()) {
                    Text("Profile", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                for (p in content.profiles) {
                    CheckRow(p.name, importFlags[p.name] == true) { importFlags[p.name] = it }
                    if (profileExists(p.name) && importFlags[p.name] == true) {
                        Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (replaceFlags[p.name] == true) "Vorhandenes ersetzen" else "Als Kopie ablegen",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Switch(checked = replaceFlags[p.name] == true, onCheckedChange = { replaceFlags[p.name] = it })
                        }
                    }
                }
                if (content.playerName == null && content.settings == null && content.profiles.isEmpty()) {
                    Text("Die Datei enthält nichts, was importiert werden kann.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onImport(
                    name,
                    settings,
                    content.profiles.map {
                        ConfigStore.ProfileChoice(it, importFlags[it.name] == true, replaceFlags[it.name] == true)
                    },
                )
            }) { Text("Importieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Eine neuere Version liegt bereit: jetzt installieren oder später. */
@Composable
fun UpdateDialog(vm: de.pabich.buzzer.BuzzerViewModel) {
    val update = vm.receivedUpdate ?: return
    if (!vm.showUpdateDialog) return
    // Beim Spielleiter erst in der Pause anbieten, nicht mitten in der Runde.
    if (vm.isMaster && !vm.masterIdle) return
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Neue Version: Build ${update.build}") },
        text = {
            Text(
                "Erhalten von ${update.from}. Die Installation ersetzt deine App (Build ${de.pabich.buzzer.AppUpdate.ownBuild}); " +
                    "Name, Profile und Statistik bleiben erhalten. Ein laufendes Spiel wird dabei verlassen – danach einfach neu beitreten.",
            )
        },
        confirmButton = { TextButton(onClick = { vm.installUpdate(context) }) { Text("Installieren") } },
        dismissButton = { TextButton(onClick = { vm.postponeUpdate() }) { Text("Später (bleibt bereit)") } },
    )
}

/** Spielregeln der Runde – beim Beitritt und zu Spielbeginn; verschwindet, sobald gespielt wird. */
@Composable
fun RulesDialog(rules: de.pabich.buzzer.core.Message.Rules, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rules.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                for ((label, value) in rules.lines) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(3.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Alles klar!") } },
    )
}

/** Selbsttest starten: Prüfungen auf diesem Gerät, auf Wunsch live mit den verbundenen Spielern. */
@Composable
fun SelfTestDialog(playersConnected: Boolean, onStart: (live: Boolean) -> Unit, onDismiss: () -> Unit) {
    var live by remember { mutableStateOf(playersConnected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Selbsttest") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("Prüft automatisch: Spiellogik mit simulierten Spielern (Buzzer, Quiz, Challenge, Bereit, Übernahme, Wiederbeitritt), Funk-Nachrichten, Klänge, Fragenpakete und die Release-Seite.")
                Spacer(Modifier.padding(4.dp))
                CheckRow("Live-Test mit den verbundenen Spielern", live, enabled = playersConnected) { live = it }
                Text(
                    "Die Spieler-Apps spielen dabei von selbst: Bereit-Abfrage, eine Buzzer-Runde, eine Quiz-Frage, danach schicken sie ihre Diagnose. " +
                        "Die Punkte werden zurückgesetzt; die Quiz-Statistik bleibt unberührt (Testspiel). Dauert etwa eine Minute.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.padding(4.dp))
                Text("Am Ende landet ein Bericht „Selbsttest-…“ mit allen Ergebnissen im Diagnose-Repository.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onStart(live && playersConnected) }) { Text("Starten") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Ergebnis des Selbsttests. */
@Composable
fun SelfTestResultsDialog(results: List<de.pabich.buzzer.core.SelfTest.Result>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Selbsttest: ${de.pabich.buzzer.core.SelfTest.summary(results)}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                for (r in results) {
                    Text((if (r.ok) "✅ " else "❌ ") + r.name, style = MaterialTheme.typography.bodyMedium)
                    if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(2.dp))
                }
                Spacer(Modifier.padding(4.dp))
                Text("Der Bericht wird im Diagnose-Repository abgelegt.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

