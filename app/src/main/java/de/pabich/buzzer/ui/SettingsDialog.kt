package de.pabich.buzzer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.pabich.buzzer.BuzzerViewModel
import de.pabich.buzzer.core.DEFAULT_EMOJIS
import de.pabich.buzzer.core.DEFAULT_SOUNDS
import de.pabich.buzzer.core.SoundEvent
import de.pabich.buzzer.core.EMOJI_COUNT
import de.pabich.buzzer.core.MasterEngine
import de.pabich.buzzer.core.ScoringMode
import de.pabich.buzzer.core.Settings
import de.pabich.buzzer.core.StartMode
import java.util.Locale

private val TABS = listOf("Ablauf", "Zeiten", "Wertung", "Klang", "Profile")

private fun Long.secondsText() = String.format(Locale.GERMANY, "%.1f", this / 1000.0)
private fun String.secondsToMs(): Long? = replace(',', '.').toDoubleOrNull()?.let { (it * 1000).toLong() }

/**
 * Einstellungen des Masters in Tabs; nur zwischen den Runden. Bewusst KEIN eigenes Dialogfenster:
 * als Seite in der App gilt dieselbe Begrenzung auf die nutzbare Bildschirmfläche wie überall
 * (ein Dialogfenster lag auf Tablets mit Taskleiste unter den System-Knöpfen).
 */
@Composable
fun SettingsDialog(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(snapshot.settings) }
    var profileName by remember { mutableStateOf(snapshot.profileName) }
    // Nach dem Laden eines Profils werden die Eingabefelder neu aufgebaut.
    var version by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onDismiss)
    run {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Einstellungen", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (profileName.isEmpty()) "Kein Profil" else "Profil: $profileName",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    vm.profileUpgrade?.takeIf { it.name == profileName }?.let {
                        Text(
                            "Neu in dieser Version: ${it.missingLabels.joinToString()}. " +
                                "Bitte prüfen – mit „Übernehmen“ wird das Profil ergänzt.",
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(8.dp),
                        )
                    }
                }
                TabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
                key(version) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        val change: (Settings) -> Unit = { draft = it }
                        when (tab) {
                            0 -> FlowTab(draft, change)
                            1 -> TimesTab(draft, snapshot, change)
                            2 -> ScoringTab(draft, snapshot, change) { vm.newGame(); onDismiss() }
                            3 -> SoundTab(draft, change)
                            else -> ProfilesTab(
                                vm = vm,
                                draft = draft,
                                profileName = profileName,
                                canShare = snapshot.players.any { it.id != BuzzerViewModel.LOCAL_PLAYER_ID },
                                onSaved = { profileName = it },
                                onLoaded = { name, settings ->
                                    draft = settings
                                    profileName = name
                                    version++
                                    tab = 0
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Abbrechen") }
                    Button(onClick = { vm.updateSettings(draft, profileName); onDismiss() }) { Text("Übernehmen") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Tabs

@Composable
private fun FlowTab(s: Settings, change: (Settings) -> Unit) {
    Section("Startmodus")
    Choice("A – Halten und Loslassen", s.startMode == StartMode.HOLD_RELEASE) { change(s.copy(startMode = StartMode.HOLD_RELEASE)) }
    Choice("B – Tippen", s.startMode == StartMode.TAP) { change(s.copy(startMode = StartMode.TAP)) }

    Section("Zufällige Verzögerung bis zur Freigabe (Sekunden)")
    Row {
        SecondsField("min", s.delayMinMs, Modifier.weight(1f)) { change(s.copy(delayMinMs = it)) }
        Spacer(Modifier.width(8.dp))
        SecondsField("max", s.delayMaxMs, Modifier.weight(1f)) { change(s.copy(delayMaxMs = it)) }
    }
    Row {
        SecondsField("Sudden Death min", s.suddenDeathDelayMinMs, Modifier.weight(1f)) { change(s.copy(suddenDeathDelayMinMs = it)) }
        Spacer(Modifier.width(8.dp))
        SecondsField("max", s.suddenDeathDelayMaxMs, Modifier.weight(1f)) { change(s.copy(suddenDeathDelayMaxMs = it)) }
    }
    IntField("Gleichstands-Schwelle (ms)", s.tieThresholdMs.toInt()) { change(s.copy(tieThresholdMs = it.toLong())) }

    Section("Teilnehmer")
    SwitchRow("Master spielt mit", s.masterPlays) { change(s.copy(masterPlays = it)) }
    if (s.masterPlays) Hint("Fair nur, wenn du die Antworten nicht kennst.")
    SwitchRow("Kurznachrichten in den Pausen", s.messagesEnabled) { change(s.copy(messagesEnabled = it)) }
    SwitchRow("Emoticons", s.emojisEnabled) { change(s.copy(emojisEnabled = it)) }
    if (s.emojisEnabled) {
        Hint("Auswahl der $EMOJI_COUNT Emoticons für dieses Profil:")
        val emojis = s.emojis
        for (rowStart in listOf(0, 4)) {
            Row {
                for (i in rowStart until minOf(rowStart + 4, EMOJI_COUNT)) {
                    var text by remember(i) { mutableStateOf(emojis.getOrElse(i) { DEFAULT_EMOJIS[i] }) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            text = v.take(8)
                            val list = emojis.toMutableList()
                            while (list.size < EMOJI_COUNT) list += DEFAULT_EMOJIS[list.size]
                            list[i] = text.trim().ifEmpty { DEFAULT_EMOJIS[i] }
                            change(s.copy(emojis = list))
                        },
                        singleLine = true,
                        modifier = Modifier.width(72.dp).padding(end = 6.dp, top = 4.dp),
                    )
                }
            }
        }
        TextButton(onClick = { change(s.copy(emojis = DEFAULT_EMOJIS)) }) { Text("Standard-Emoticons") }
    }
}

@Composable
private fun TimesTab(s: Settings, snapshot: MasterEngine.Snapshot, change: (Settings) -> Unit) {
    val restTime = s.scoring == ScoringMode.REMAINING_TIME
    Section("Maximale Reaktionszeit")
    SwitchRow("Begrenzen", s.reactionLimitEnabled || restTime, enabled = !restTime) { change(s.copy(reactionLimitEnabled = it)) }
    if (restTime) Hint("Bei der Wertung „Restzeit“ immer eingeschaltet.")
    if (s.reactionLimitEnabled || restTime) {
        SecondsField("Sekunden", s.timeLimitMs, enabled = !snapshot.timeLimitLocked) { change(s.copy(timeLimitMs = it)) }
        if (snapshot.timeLimitLocked) Hint("Bei Restzeit-Wertung bis „Neues Spiel“ gesperrt.")
    } else {
        Hint("Ohne Limit endet die Runde erst, wenn alle gedrückt haben (oder mit „Runde abbrechen“).")
    }

    Section("Maximale Antwortzeit")
    SwitchRow("Begrenzen", s.answerLimitEnabled) { change(s.copy(answerLimitEnabled = it)) }
    if (s.answerLimitEnabled) {
        SecondsField("Sekunden", s.answerLimitMs) { change(s.copy(answerLimitMs = it)) }
        Hint("Läuft die Zeit ab, gilt die Antwort als falsch.")
        Section("Danach")
        Choice("Automatisch zum nächsten Spieler", s.autoAdvanceOnTimeout) { change(s.copy(autoAdvanceOnTimeout = true)) }
        Choice("Warten, bis der Master „Weiter“ tippt", !s.autoAdvanceOnTimeout) { change(s.copy(autoAdvanceOnTimeout = false)) }
    }
    Hint("Beide Limits sehen alle Spieler vor jeder Freigabe.")
}

@Composable
private fun ScoringTab(s: Settings, snapshot: MasterEngine.Snapshot, change: (Settings) -> Unit, onNewGame: () -> Unit) {
    Section("Wertung" + if (snapshot.scoringLocked) " (gesperrt bis „Neues Spiel“)" else "")
    Choice("Richtige Antworten", s.scoring == ScoringMode.CORRECT_ANSWERS, !snapshot.scoringLocked) {
        change(s.copy(scoring = ScoringMode.CORRECT_ANSWERS))
    }
    Choice("Restzeit aufaddieren", s.scoring == ScoringMode.REMAINING_TIME, !snapshot.scoringLocked) {
        change(s.copy(scoring = ScoringMode.REMAINING_TIME, reactionLimitEnabled = true))
    }

    Section("Rangliste für die Spieler")
    SwitchRow("Namen anzeigen", s.boardShowNames) { change(s.copy(boardShowNames = it)) }
    IntField("Sichtbare Plätze ab Platz 1 (0 = alle)", s.boardPlaces) { change(s.copy(boardPlaces = it)) }
    Hint("Den eigenen Platz sieht jeder Spieler immer.")

    OutlinedButton(onClick = onNewGame, modifier = Modifier.padding(top = 16.dp)) {
        Text("Neues Spiel (Punkte zurücksetzen)")
    }
}

@Composable
private fun SoundTab(s: Settings, change: (Settings) -> Unit) {
    Section("Klänge dieses Profils")
    Hint("„Richtig“ und „Falsch“ hört nur der betroffene Spieler. Bei der Freigabe gibt es bewusst keinen Ton.")
    for (event in SoundEvent.entries) {
        SwitchRow(event.label, event in s.sounds) { on -> change(s.copy(sounds = if (on) s.sounds + event else s.sounds - event)) }
    }
    OutlinedButton(onClick = { change(s.copy(sounds = DEFAULT_SOUNDS)) }, modifier = Modifier.padding(top = 12.dp)) {
        Text("Auf Standard zurücksetzen")
    }
    Hint("Jedes Gerät kann Töne zusätzlich über 🔊 in der oberen Leiste stummschalten.")
}

@Composable
private fun ProfilesTab(
    vm: BuzzerViewModel,
    draft: Settings,
    profileName: String,
    canShare: Boolean,
    onSaved: (String) -> Unit,
    onLoaded: (String, Settings) -> Unit,
) {
    var saveName by remember { mutableStateOf(profileName.ifEmpty { "Mein Quiz" }) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Section("Aktuelle Einstellungen speichern")
    OutlinedTextField(
        value = saveName,
        onValueChange = { saveName = it.take(40) },
        label = { Text("Profilname") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.saveProfileAs(saveName, draft); onSaved(saveName.trim()) },
        enabled = saveName.isNotBlank(),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(if (vm.profiles.any { it.name == saveName.trim() }) "Profil überschreiben" else "Als Profil speichern")
    }

    Section("Gespeicherte Profile")
    if (vm.profiles.isEmpty()) Hint("Noch keine Profile.")
    for (p in vm.profiles) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.name + if (p.name == profileName) "  (aktiv)" else "", Modifier.weight(1f))
            TextButton(onClick = { vm.loadProfile(p.name)?.let { onLoaded(p.name, it) } }) { Text("Laden") }
            TextButton(onClick = { renaming = p.name }) { Text("Umbenennen") }
            TextButton(onClick = { deleting = p.name }) { Text("Löschen") }
        }
    }

    Section("Weitergeben")
    OutlinedButton(onClick = { vm.shareProfile() }, enabled = canShare) { Text("Laufendes Profil an Spieler senden") }
    Hint("Gesendet wird das zuletzt übernommene Profil. Die Spieler können es ansehen und speichern.")

    renaming?.let { old ->
        var newName by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Profil umbenennen") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it.take(40) }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    vm.renameProfile(old, newName)
                    if (profileName == old) onSaved(newName.trim())
                    renaming = null
                }) { Text("Umbenennen") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Abbrechen") } },
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Profil „$name“ löschen?") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(name); deleting = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
}

// ---------------------------------------------------------------------- Bausteine

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
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
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Sekunden-Eingabe; der Wert wird übernommen, sobald er lesbar ist. */
@Composable
private fun SecondsField(
    label: String,
    valueMs: Long,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onValue: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(valueMs.secondsText()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v; v.secondsToMs()?.let(onValue) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun IntField(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v; v.toIntOrNull()?.let(onValue) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
