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
import androidx.compose.material3.Button
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

/**
 * Spielregeln der Runde. Mit Kennung ([Message.Rules.confirmId] ≠ 0) beginnt das Spiel erst, wenn alle
 * mit OK bestätigt haben: groß und gut lesbar, nicht wegtippbar. Sonst reine Information.
 */
@Composable
fun RulesDialog(rules: de.pabich.buzzer.core.Message.Rules, onConfirm: (() -> Unit)?, onDismiss: () -> Unit) {
    val confirming = rules.confirmId != 0
    AlertDialog(
        onDismissRequest = { if (!confirming) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = !confirming, dismissOnClickOutside = !confirming),
        title = { Text(rules.title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                for ((label, value) in rules.lines) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(5.dp))
                }
            }
        },
        confirmButton = {
            if (confirming) {
                Button(onClick = { onConfirm?.invoke() }) { Text("OK – ich bin bereit", style = MaterialTheme.typography.titleMedium) }
            } else {
                TextButton(onClick = onDismiss) { Text("Alles klar!") }
            }
        },
    )
}

/** Spielleiter: Stand der Regel-Bestätigung – wer hat, wer fehlt; er bestätigt selbst oder beginnt trotzdem. */
@Composable
fun MasterRulesDialog(
    state: de.pabich.buzzer.core.MasterEngine.RulesState,
    rules: List<Pair<String, String>>,
    title: String,
    onConfirm: () -> Unit,
    onStartAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                for ((label, value) in rules) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(5.dp))
                }
                Spacer(Modifier.padding(4.dp))
                Text("Die Runde beginnt, sobald alle bestätigt haben.", style = MaterialTheme.typography.labelLarge)
                if (state.confirmedNames.isNotEmpty()) Text("✅ " + state.confirmedNames.joinToString(), style = MaterialTheme.typography.bodyMedium)
                if (state.waitingNames.isNotEmpty()) Text("⏳ " + state.waitingNames.joinToString(), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (!state.masterConfirmed) Button(onClick = onConfirm) { Text("OK – ich bin bereit") }
            else TextButton(onClick = onStartAnyway) { Text("Trotzdem beginnen") }
        },
        dismissButton = {
            Row {
                if (!state.masterConfirmed) TextButton(onClick = onStartAnyway) { Text("Sofort beginnen") }
                TextButton(onClick = onCancel) { Text("Abbrechen") }
            }
        },
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


private val READ_SAMPLES = listOf(
    "Die Katze schläft auf dem Sofa.",
    "Welcher Fluss ist der längste Europas und mündet nach mehr als dreitausend Kilometern ins Kaspische Meer?",
    "Im Jahr 1989 fiel die Berliner Mauer.",
    "Wer schrieb den Roman „Die Verwandlung“, in dem ein Handlungsreisender eines Morgens als ungeheures Ungeziefer erwacht?",
    "Wie viele Beine hat eine Spinne?",
)

/**
 * Lesetest: fünf Beispielzeilen unterschiedlicher Länge, jede mit „Fertig“ bestätigen. Daraus entsteht das
 * Lesetempo (ms je Zeichen), das der Spielleiter für die Wartezeit zwischen Frage und Antworten nutzt.
 */
@Composable
fun ReadTestDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var index by remember { mutableStateOf(-1) }
    var startedAt by remember { mutableStateOf(0L) }
    var totalMs by remember { mutableStateOf(0L) }
    var totalChars by remember { mutableStateOf(0) }
    val done = index >= READ_SAMPLES.size
    val measured = if (totalChars > 0) (totalMs.toDouble() / totalChars).toInt().coerceIn(20, 400) else 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lesetest") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when {
                    index < 0 -> {
                        Text("Du liest nacheinander ${READ_SAMPLES.size} Beispielzeilen in deinem normalen Tempo und tippst jeweils „Fertig“. Daraus berechnet die App dein Lesetempo; der Spielleiter wartet dann nach jeder Frage lange genug.")
                        if (current > 0) Text("Bisher gemessen: $current ms je Zeichen", style = MaterialTheme.typography.bodySmall)
                    }
                    !done -> {
                        Text("Zeile ${index + 1} von ${READ_SAMPLES.size}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.padding(4.dp))
                        Text(READ_SAMPLES[index], style = MaterialTheme.typography.titleLarge)
                    }
                    else -> {
                        Text("Dein Lesetempo: $measured ms je Zeichen", style = MaterialTheme.typography.titleMedium)
                        Text("Etwa ${"%.1f".format(java.util.Locale.GERMANY, 1000.0 / measured)} Zeichen pro Sekunde. Mit „Übernehmen“ erfährt es der Spielleiter.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when {
                index < 0 -> TextButton(onClick = { index = 0; startedAt = android.os.SystemClock.uptimeMillis() }) { Text("Start") }
                !done -> TextButton(onClick = {
                    totalMs += android.os.SystemClock.uptimeMillis() - startedAt
                    totalChars += READ_SAMPLES[index].length
                    index++
                    startedAt = android.os.SystemClock.uptimeMillis()
                }) { Text("Fertig") }
                else -> TextButton(onClick = { onSave(measured); onDismiss() }) { Text("Übernehmen") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
