package de.pabich.buzzer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.pabich.buzzer.BuildConfig
import de.pabich.buzzer.BuzzerViewModel
import de.pabich.buzzer.Group
import de.pabich.buzzer.core.ConfigFormat
import de.pabich.buzzer.core.Display
import de.pabich.buzzer.core.MasterEngine
import de.pabich.buzzer.core.MasterEngine.Phase
import de.pabich.buzzer.core.PlayerEngine
import de.pabich.buzzer.core.StartMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun ScreenSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) { content() }
    }
}

@Composable
private fun StatusText(vm: BuzzerViewModel) {
    var showDiagnostics by remember { mutableStateOf(false) }
    vm.status?.let {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = { showDiagnostics = true }) { Text("Diagnose senden") }
                TextButton(onClick = { vm.status = null }) { Text("Ausblenden") }
            }
        }
    }
    if (showDiagnostics) DiagnosticsDialog(vm.status) { showDiagnostics = false }
}

// ---------------------------------------------------------------------- Start

@Composable
fun StartScreen(vm: BuzzerViewModel, onOpenGroup: () -> Unit, onSearch: () -> Unit) {
    ScreenSurface {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.weight(1f))
            Text("Buzzer", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = vm.myName,
                onValueChange = { vm.myName = it.take(24) },
                label = { Text("Dein Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            val nameOk = vm.myName.isNotBlank()
            Button(onClick = onOpenGroup, enabled = nameOk, modifier = Modifier.fillMaxWidth()) {
                Text("Gruppe eröffnen (Spielleiter)")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSearch, enabled = nameOk, modifier = Modifier.fillMaxWidth()) {
                Text("Gruppe suchen (Spieler)")
            }
            StatusText(vm)
            Spacer(Modifier.weight(1f))
            ConfigButtons(vm)
            var showDiagnostics by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Version ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showDiagnostics = true }) { Text("Diagnose senden") }
            }
            if (showDiagnostics) DiagnosticsDialog(vm.status) { showDiagnostics = false }
        }
    }
}

/** Einstellungen und Profile als .buzzconf sichern oder einlesen. */
@Composable
private fun ConfigButtons(vm: BuzzerViewModel) {
    var showExport by remember { mutableStateOf(false) }
    var exportParts by remember { mutableStateOf<ConfigFormat.Parts?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val parts = exportParts
        if (uri != null && parts != null) vm.exportConfig(uri, parts)
        exportParts = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Einstellungen:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { showExport = true }) { Text("Exportieren") }
        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Importieren") }
    }
    if (showExport) {
        ExportDialog(
            profiles = vm.profiles,
            onExport = { parts ->
                showExport = false
                exportParts = parts
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
                exportLauncher.launch("buzzer-$date.buzzconf")
            },
            onDismiss = { showExport = false },
        )
    }
    vm.importContent?.let { content ->
        ImportDialog(
            content = content,
            profileExists = vm::profileExists,
            onImport = { name, settings, choices -> vm.applyImport(name, settings, choices) },
            onDismiss = { vm.importContent = null },
        )
    }
}

// ---------------------------------------------------------------------- Gruppen suchen

@Composable
fun DiscoverScreen(vm: BuzzerViewModel) {
    BackHandler { vm.leave() }
    ScreenSurface {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Gefundene Gruppen", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            if (vm.groups.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Suche läuft …")
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(vm.groups, key = Group::endpointId) { group ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable(enabled = vm.connectingTo == null) { vm.join(group) },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (vm.connectingTo == group.endpointId) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Beitreten")
                            }
                        }
                    }
                }
            }
            if (vm.connectingTo != null) Text("Warte auf Bestätigung durch den Spielleiter …")
            StatusText(vm)
            OutlinedButton(onClick = { vm.leave() }, modifier = Modifier.fillMaxWidth()) { Text("Zurück") }
        }
    }
}

// ---------------------------------------------------------------------- Spieler

@Composable
fun PlayerScreen(vm: BuzzerViewModel) {
    var confirmLeave by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    val view = vm.playerView ?: return

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Die Leiste gehört nicht zur Buzzer-Fläche: ein Emoticon ist kein Buzz.
            TopBar(
                emojis = if (vm.receiveEmojis) view.rules.emojis else emptyList(),
                onEmoji = { vm.sendEmote(it) },
            ) {
                if (view.rules.emojis.isNotEmpty()) {
                    BarButton(if (vm.receiveEmojis) "🔔" else "🔕") { vm.changeReceiveEmojis(!vm.receiveEmojis) }
                }
                if (view.rules.messagesEnabled) BarButton("✉", enabled = vm.canChat) { showChat = true }
            }
            BuzzerPad(
                view = view,
                onDown = { vm.onTouchDown(it) },
                onUp = { vm.onTouchUp(it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                chat = vm.chat,
            )
        }
        EmoteOverlay(vm.emotes, onDone = vm::emoteDone, modifier = Modifier.fillMaxSize())
    }

    if (showChat) {
        ChatDialog(
            recipients = view.roster,
            onSend = { to, name, text -> vm.sendChat(to, name, text) },
            onDismiss = { showChat = false },
        )
    }
    vm.receivedProfile?.let { profile ->
        ReceivedProfileDialog(
            profile = profile,
            onSave = { vm.saveReceivedProfile(it) },
            onDismiss = { vm.receivedProfile = null },
        )
    }
    if (confirmLeave) {
        LeaveDialog(
            title = "Gruppe verlassen?",
            text = null,
            confirm = "Verlassen",
            onConfirm = { delete -> confirmLeave = false; vm.leave(delete) },
            onDismiss = { confirmLeave = false },
        )
    }
}

// ---------------------------------------------------------------------- Master

@Composable
fun MasterScreen(vm: BuzzerViewModel) {
    val snapshot = vm.masterSnapshot ?: return
    var showSettings by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    val settings = snapshot.settings
    val pause = snapshot.phase == Phase.READY || snapshot.phase == Phase.ROUND_OVER

    ScreenSurface {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    emojis = if (settings.emojisEnabled && vm.receiveEmojis) settings.emojis else emptyList(),
                    onEmoji = { vm.sendEmote(it) },
                ) {
                    if (settings.emojisEnabled) {
                        BarButton(if (vm.receiveEmojis) "🔔" else "🔕") { vm.changeReceiveEmojis(!vm.receiveEmojis) }
                    }
                    if (settings.messagesEnabled) BarButton("✉", enabled = vm.canChat) { showChat = true }
                }
                Column(Modifier.weight(2f).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vm.myName.trim(), style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Runde ${snapshot.round} · ${phaseText(snapshot)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (snapshot.profileName.isNotEmpty()) {
                                Text("Profil: ${snapshot.profileName}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { showSettings = true }, enabled = snapshot.phase == Phase.READY) {
                            Text("Einstellungen")
                        }
                    }
                    if (snapshot.phase == Phase.READY) {
                        Text(
                            limitsText(
                                PlayerEngine.Rules(
                                    reactionLimitMs = settings.reactionLimitMs,
                                    answerLimitMs = settings.answerLimit,
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        if (snapshot.players.isEmpty()) {
                            item { Text("Noch keine Spieler – warte auf Beitrittsanfragen …", Modifier.padding(vertical = 16.dp)) }
                        }
                        val maxScore = snapshot.players.maxOfOrNull { it.scoreValue } ?: 0
                        items(snapshot.players, key = { it.id }) { PlayerRowItem(it, maxScore) }
                    }
                    if (pause) ChatPanel(vm.chat, MaterialTheme.colorScheme.onBackground, Modifier.padding(vertical = 4.dp))
                    val deadline = snapshot.answerDeadline
                    if (snapshot.phase == Phase.ANSWERING && deadline != null) {
                        AnswerCountdown(
                            deadline,
                            settings.answerLimitMs,
                            MaterialTheme.colorScheme.primary,
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    StatusText(vm)
                    MasterButtons(vm, snapshot)
                }
                if (settings.masterPlays) {
                    vm.playerView?.let { view ->
                        // Das Spielfeld des Masters nimmt 1/3 des Bildschirms ein.
                        BuzzerPad(
                            view = view,
                            onDown = { vm.onTouchDown(it) },
                            onUp = { vm.onTouchUp(it) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            compact = true,
                        )
                    }
                }
            }
            EmoteOverlay(vm.emotes, onDone = vm::emoteDone, modifier = Modifier.fillMaxSize())
        }
    }

    vm.joinRequests.firstOrNull()?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Beitrittsanfrage") },
            text = { Text("${request.name} möchte beitreten.\nCode: ${request.authCode}") },
            confirmButton = { TextButton(onClick = { vm.acceptJoin(request) }) { Text("Annehmen") } },
            dismissButton = { TextButton(onClick = { vm.rejectJoin(request) }) { Text("Ablehnen") } },
        )
    }
    if (showSettings) SettingsDialog(vm, snapshot) { showSettings = false }
    if (showChat) {
        ChatDialog(
            recipients = snapshot.players.filter { it.connected && it.id != BuzzerViewModel.LOCAL_PLAYER_ID }.map { it.id to it.name },
            onSend = { to, name, text -> vm.sendChat(to, name, text) },
            onDismiss = { showChat = false },
        )
    }
    if (confirmLeave) {
        LeaveDialog(
            title = "Gruppe schließen?",
            text = "Alle Spieler werden getrennt.",
            confirm = "Schließen",
            onConfirm = { delete -> confirmLeave = false; vm.leave(delete) },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun MasterButtons(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when (snapshot.phase) {
            Phase.READY -> Button(
                onClick = { vm.release() },
                enabled = snapshot.players.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (snapshot.settings.startMode == StartMode.TAP) "Buzzer frei" else "Buzzer scharf")
            }
            Phase.ANSWERING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.markCorrect() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Richtig") }
                Button(onClick = { vm.markWrong() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Falsch") }
            }
            Phase.ANSWER_TIMEOUT -> Button(onClick = { vm.proceed() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Zeit abgelaufen – Weiter")
            }
            else -> Unit
        }
        if (snapshot.phase != Phase.READY) {
            Spacer(Modifier.height(8.dp))
            if (snapshot.phase == Phase.ROUND_OVER) {
                Button(onClick = { vm.nextRound() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Nächste Runde") }
            } else {
                OutlinedButton(onClick = { vm.nextRound() }, modifier = Modifier.fillMaxWidth()) { Text("Runde abbrechen") }
            }
        }
    }
}

@Composable
private fun PlayerRowItem(row: MasterEngine.PlayerRow, maxScore: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(row.display.color()),
            contentAlignment = Alignment.Center,
        ) {
            if (row.place > 0) Text(row.place.toString(), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (row.connected) row.name else "${row.name} (getrennt)", style = MaterialTheme.typography.titleMedium)
            val details = listOfNotNull(
                row.reactionMs?.let { "$it ms" },
                row.rttMs?.let { "Funk $it ms" },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
            ScoreBar(
                row.scoreValue,
                maxScore,
                MaterialTheme.colorScheme.primary,
                Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp),
            )
        }
        Text(row.score, style = MaterialTheme.typography.titleMedium)
    }
}

private fun phaseText(snapshot: MasterEngine.Snapshot): String = when (snapshot.phase) {
    Phase.READY -> "Bereit – Frage stellen"
    Phase.WAIT_HOLD -> "Warte, bis alle den Finger auflegen"
    Phase.COUNTDOWN -> "Freigabe läuft …"
    Phase.RUNNING -> "Buzzer offen"
    Phase.SUDDEN_DEATH_PREP -> "Gleichstand – Sudden Death"
    Phase.ANSWERING -> "Antwort von ${snapshot.players.firstOrNull { it.display == Display.GREEN }?.name ?: "…"}"
    Phase.ANSWER_TIMEOUT -> "Antwortzeit abgelaufen"
    Phase.ROUND_OVER -> "Runde beendet"
}
