package de.pabich.buzzer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.BuildConfig
import de.pabich.buzzer.BuzzerViewModel
import de.pabich.buzzer.Group
import de.pabich.buzzer.Screen
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
private fun StatusText(vm: BuzzerViewModel) {
    val theme = LocalBuzzerTheme.current
    var showDiagnostics by remember { mutableStateOf(false) }
    vm.status?.let {
        Panel(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(text = it, color = Color(0xFFFF9A9A), fontSize = 15.sp)
            Row {
                TextButton(onClick = { showDiagnostics = true }) { Text("Diagnose senden", color = theme.accent) }
                TextButton(onClick = { vm.status = null }) { Text("Ausblenden", color = theme.muted) }
            }
        }
    }
    if (showDiagnostics) DiagnosticsDialog(vm.status) { showDiagnostics = false }
}

// ---------------------------------------------------------------------- Start

@Composable
fun StartScreen(vm: BuzzerViewModel, onOpenGroup: () -> Unit, onSearch: () -> Unit) {
    val theme = LocalBuzzerTheme.current
    ThemedScreen {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(120.dp)
                        .drawBehind {
                            drawCircle(Brush.radialGradient(listOf(theme.accent.copy(alpha = 0.35f), Color.Transparent), radius = size.minDimension), radius = size.minDimension)
                        }
                        .border(4.dp, theme.accent, CircleShape)
                        .padding(10.dp)
                        .background(theme.logo, CircleShape),
                )
                Spacer(Modifier.height(16.dp))
                BigTitle("Buzzer", size = 60.sp)
                Text("WER IST AM SCHNELLSTEN?", color = theme.muted, fontSize = 14.sp, letterSpacing = 3.sp)
            }
            ThemedTextField(vm.myName, { vm.myName = it.take(24) }, "Dein Name", Modifier.fillMaxWidth())
            val nameOk = vm.myName.isNotBlank()
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StartTile("Spiel leiten", "Gruppe eröffnen", filled = true, enabled = nameOk, onClick = onOpenGroup, modifier = Modifier.weight(1f))
                StartTile("Mitspielen", "Gruppe suchen", filled = false, enabled = nameOk, onClick = onSearch, modifier = Modifier.weight(1f))
            }
            if (!nameOk) Text("Bitte zuerst deinen Namen eingeben.", color = theme.muted, fontSize = 14.sp)
            StatusText(vm)
            Column {
                MenuRow("Profile & Einstellungen", { vm.navigate(Screen.SETTINGS) })
                MenuRow("Hilfe & Spielregeln", { vm.navigate(Screen.HELP) })
                MenuRow("Über & Diagnose", { vm.navigate(Screen.ABOUT) }, divider = false)
            }
            Text(
                "Version ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}",
                color = theme.muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StartTile(title: String, subtitle: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val theme = LocalBuzzerTheme.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(140.dp)
            .clip(shape)
            .background(if (filled) theme.accent else theme.panel)
            .then(if (filled) Modifier else Modifier.border(2.dp, theme.accent2, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        val textColor = if (filled) theme.ground else theme.text
        Text(title.uppercase(), color = textColor.copy(alpha = if (enabled) 1f else 0.5f), fontFamily = theme.display, fontSize = 24.sp)
        Text(subtitle, color = if (filled) theme.ground else theme.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------- Unterseiten des Startmenüs

@Composable
fun AppSettingsScreen(vm: BuzzerViewModel) {
    val theme = LocalBuzzerTheme.current
    BackHandler { vm.navigate(Screen.START) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    ThemedScreen {
        BackHeader("Einstellungen") { vm.navigate(Screen.START) }
        ScrollPage {
            SectionTitle("Dieses Gerät")
            ToggleRow("Töne", vm.soundOn) { vm.changeSoundOn(it) }
            ToggleRow("Emoticons empfangen und senden", vm.receiveEmojis) { vm.changeReceiveEmojis(it) }

            SectionTitle("Design")
            for (t in Themes.all) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = vm.designId == t.id, onClick = { vm.changeDesign(t.id) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.designId == t.id, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t.name, color = theme.text, fontSize = 16.sp)
                }
            }
            Text("Weitere Designs folgen.", color = theme.muted, fontSize = 13.sp)

            SectionTitle("Spielprofile")
            if (vm.profiles.isEmpty()) {
                Text("Noch keine Profile. Profile legt der Spielleiter in den Spieleinstellungen an.", color = theme.muted, fontSize = 14.sp)
            }
            for (p in vm.profiles) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = theme.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { renaming = p.name }) { Text("Umbenennen", color = theme.accent2) }
                    TextButton(onClick = { deleting = p.name }) { Text("Löschen", color = theme.accent2) }
                }
            }

            SectionTitle("Sichern")
            ConfigButtons(vm)
            StatusText(vm)
            Spacer(Modifier.height(24.dp))
        }
    }
    renaming?.let { old ->
        var newName by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Profil umbenennen") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = { vm.renameProfile(old, newName); renaming = null }) { Text("Umbenennen") }
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

@Composable
fun HelpScreen(vm: BuzzerViewModel) {
    val theme = LocalBuzzerTheme.current
    BackHandler { vm.navigate(Screen.START) }
    ThemedScreen {
        BackHeader("Spielregeln") { vm.navigate(Screen.START) }
        ScrollPage {
            val sections = listOf(
                "So geht's" to "Ein Gerät leitet das Spiel, alle anderen sind Buzzer. Der Spielleiter stellt die Frage mündlich und gibt dann die Buzzer frei.",
                "Buzzern" to "Wird dein Bildschirm gelb, tippst du so schnell wie möglich (im Halte-Modus: loslassen). Wer zu früh drückt, ist für diese Runde raus.",
                "Reihenfolge" to "Der Schnellste wird grün und darf antworten, alle anderen sehen rot ihren Platz. Gezählt wird die Reaktionszeit auf deinem Gerät – die Funk-Laufzeit wird herausgerechnet.",
                "Richtig oder falsch" to "Ist die Antwort falsch, kommt der Nächste dran. Bei einer begrenzten Antwortzeit zählt Zeitablauf als falsch.",
                "Gleichstand" to "Liegen zwei Reaktionen fast gleichauf, werden beide pink und spielen ein Sudden Death.",
                "In den Pausen" to "Rangliste, Kurznachrichten und – wenn der Spielleiter will – eine Animation des Spielverlaufs. Emoticons gehen immer.",
            )
            for ((title, text) in sections) {
                SectionTitle(title)
                Text(text, color = theme.text, fontSize = 16.sp, lineHeight = 22.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AboutScreen(vm: BuzzerViewModel) {
    val theme = LocalBuzzerTheme.current
    BackHandler { vm.navigate(Screen.START) }
    var showDiagnostics by remember { mutableStateOf(false) }
    ThemedScreen {
        BackHeader("Über & Diagnose") { vm.navigate(Screen.START) }
        ScrollPage {
            SectionTitle("Version")
            Text("Buzzer ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})", color = theme.text, fontSize = 16.sp)
            SectionTitle("Diagnose")
            Text(
                "Bei Problemen einen Bericht über „Teilen“ verschicken. Systeminformationen kommen nur hinein, wenn du sie ankreuzt.",
                color = theme.text,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Diagnose senden", { showDiagnostics = true }, Modifier.fillMaxWidth(), height = 52.dp)
            SectionTitle("Lizenzen")
            Text("Schriften Anton und Barlow – SIL Open Font License 1.1.", color = theme.muted, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showDiagnostics) DiagnosticsDialog(vm.status) { showDiagnostics = false }
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GhostButton("Exportieren", { showExport = true }, Modifier.weight(1f))
        GhostButton("Importieren", { importLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
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
    val theme = LocalBuzzerTheme.current
    BackHandler { vm.leave() }
    ThemedScreen {
        BackHeader("Gruppe suchen") { vm.leave() }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (vm.groups.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = theme.accent)
                    Spacer(Modifier.width(12.dp))
                    Text("Suche läuft …", color = theme.muted, fontSize = 16.sp)
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.groups, key = Group::endpointId) { group ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(theme.panel)
                            .clickable(enabled = vm.connectingTo == null) { vm.join(group) }
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.name, color = theme.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (vm.connectingTo == group.endpointId) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = theme.accent)
                        } else {
                            Text("BEITRETEN", color = theme.accent, fontFamily = theme.display, fontSize = 18.sp)
                        }
                    }
                }
            }
            if (vm.connectingTo != null) Text("Warte auf Bestätigung durch den Spielleiter …", color = theme.muted, fontSize = 15.sp)
            StatusText(vm)
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Die Leiste gehört nicht zur Buzzer-Fläche: ein Emoticon ist kein Buzz.
            TopBar(
                emojis = if (vm.receiveEmojis) view.rules.emojis else emptyList(),
                onEmoji = { vm.sendEmote(it) },
            ) {
                BarButton(if (vm.soundOn) "🔊" else "🔇") { vm.changeSoundOn(!vm.soundOn) }
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
                confettiSeq = vm.confettiSeq,
            )
        }
        EmoteOverlay(vm.emotes, onDone = vm::emoteDone, modifier = Modifier.fillMaxSize())
        vm.replay?.let { ReplayOverlay(it, onDone = vm::replayDone) }
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
    val theme = LocalBuzzerTheme.current
    val snapshot = vm.masterSnapshot ?: return
    var showSettings by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    val settings = snapshot.settings
    val pause = snapshot.phase == Phase.READY || snapshot.phase == Phase.ROUND_OVER

    Box(Modifier.fillMaxSize().background(theme.ground)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                emojis = if (settings.emojisEnabled && vm.receiveEmojis) settings.emojis else emptyList(),
                onEmoji = { vm.sendEmote(it) },
            ) {
                BarButton(if (vm.soundOn) "🔊" else "🔇") { vm.changeSoundOn(!vm.soundOn) }
                if (settings.messagesEnabled) BarButton("✉", enabled = vm.canChat) { showChat = true }
            }
            Column(Modifier.weight(2f).padding(horizontal = 16.dp)) {
                Row(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Kicker("Runde ${snapshot.round}" + if (snapshot.profileName.isNotEmpty()) " · ${snapshot.profileName}" else "")
                        BigTitle(phaseText(snapshot), color = theme.text, size = 30.sp)
                    }
                    Box {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(theme.panel)
                                .clickable { showMenu = true },
                            contentAlignment = Alignment.Center,
                        ) { Text("⋮", color = theme.text, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Spieleinstellungen") },
                                enabled = snapshot.phase == Phase.READY,
                                onClick = { showMenu = false; showSettings = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Spielverlauf zeigen") },
                                enabled = snapshot.canShowReplay,
                                onClick = { showMenu = false; vm.showReplay() },
                            )
                            DropdownMenuItem(
                                text = { Text("Profil an Spieler senden") },
                                enabled = snapshot.players.any { it.id != BuzzerViewModel.LOCAL_PLAYER_ID },
                                onClick = { showMenu = false; vm.shareProfile() },
                            )
                            DropdownMenuItem(
                                text = { Text("Neues Spiel (Punkte auf 0)") },
                                enabled = snapshot.phase == Phase.READY,
                                onClick = { showMenu = false; vm.newGame() },
                            )
                            DropdownMenuItem(
                                text = { Text("Gruppe schließen") },
                                onClick = { showMenu = false; confirmLeave = true },
                            )
                        }
                    }
                }
                if (snapshot.phase == Phase.READY) {
                    Text(
                        limitsText(PlayerEngine.Rules(reactionLimitMs = settings.reactionLimitMs, answerLimitMs = settings.answerLimit)),
                        color = theme.muted,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (snapshot.players.isEmpty()) {
                        item {
                            Text(
                                "Noch keine Spieler – warte auf Beitrittsanfragen …",
                                color = theme.muted,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                    val maxScore = snapshot.players.maxOfOrNull { it.scoreValue } ?: 0
                    items(snapshot.players, key = { it.id }) { PlayerCard(it, maxScore) }
                }
                if (pause) ChatPanel(vm.chat, theme.text, Modifier.padding(vertical = 4.dp))
                val deadline = snapshot.answerDeadline
                if (snapshot.phase == Phase.ANSWERING && deadline != null) {
                    AnswerCountdown(deadline, settings.answerLimitMs, theme.accent, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                StatusText(vm)
                MasterActions(vm, snapshot)
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
        vm.replay?.let { ReplayOverlay(it, onDone = vm::replayDone) }
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

/** Unten ein großer Hauptknopf, der je nach Phase wechselt. */
@Composable
private fun MasterActions(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot) {
    val theme = LocalBuzzerTheme.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (snapshot.phase) {
            Phase.READY -> PrimaryButton(
                if (snapshot.settings.startMode == StartMode.TAP) "Buzzer frei" else "Buzzer scharf",
                { vm.release() },
                Modifier.fillMaxWidth(),
                enabled = snapshot.players.isNotEmpty(),
                height = 64.dp,
            )
            Phase.ANSWERING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Richtig", { vm.markCorrect() }, Modifier.weight(1f), container = theme.good, content = Color.White, height = 64.dp)
                PrimaryButton("Falsch", { vm.markWrong() }, Modifier.weight(1f), container = theme.bad, content = Color.White, height = 64.dp)
            }
            Phase.ANSWER_TIMEOUT -> PrimaryButton("Zeit abgelaufen – weiter", { vm.proceed() }, Modifier.fillMaxWidth(), height = 64.dp)
            Phase.ROUND_OVER -> PrimaryButton("Nächste Runde", { vm.nextRound() }, Modifier.fillMaxWidth(), height = 64.dp)
            else -> Unit
        }
        val pause = snapshot.phase == Phase.READY || snapshot.phase == Phase.ROUND_OVER
        if (pause && snapshot.canShowReplay) GhostButton("Spielverlauf zeigen", { vm.showReplay() }, Modifier.fillMaxWidth())
        if (!pause && snapshot.phase != Phase.ANSWER_TIMEOUT) GhostButton("Runde abbrechen", { vm.nextRound() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PlayerCard(row: MasterEngine.PlayerRow, maxScore: Long) {
    val theme = LocalBuzzerTheme.current
    val ring = when (row.display) {
        Display.GREEN -> Color(0xFF3EDB78)
        Display.RED -> Color(0xFFFF6B6B)
        Display.PINK -> Color(0xFFFF7AB0)
        Display.YELLOW, Display.PRESSED -> theme.accent
        Display.BLUE -> Color(0xFF6F9BFF)
        else -> theme.muted
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.panel).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).border(4.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (row.place > 0) row.place.toString() else "–", color = ring, fontFamily = theme.display, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (row.connected) row.name else "${row.name} (getrennt)",
                    color = theme.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val detail = listOfNotNull(row.reactionMs?.let { "$it ms" }, row.rttMs?.let { "Funk $it ms" }).joinToString(" · ")
                Text(detail, color = theme.muted, fontSize = 13.sp)
            }
            ScoreBar(row.scoreValue, maxScore, theme.accent, Modifier.fillMaxWidth(), highlight = true)
        }
        Text(row.score, color = theme.text, fontFamily = theme.display, fontSize = 24.sp)
    }
}

private fun phaseText(snapshot: MasterEngine.Snapshot): String = when (snapshot.phase) {
    Phase.READY -> if (snapshot.players.isEmpty()) "Warte auf Spieler" else "Frage stellen"
    Phase.WAIT_HOLD -> "Finger auflegen …"
    Phase.COUNTDOWN -> "Gleich geht's los"
    Phase.RUNNING -> "Buzzer offen!"
    Phase.SUDDEN_DEATH_PREP -> "Sudden Death"
    Phase.ANSWERING -> "Antwort von ${snapshot.players.firstOrNull { it.display == Display.GREEN }?.name ?: "…"}"
    Phase.ANSWER_TIMEOUT -> "Zeit abgelaufen"
    Phase.ROUND_OVER -> "Runde beendet"
}
