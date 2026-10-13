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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.LocalContext
import de.pabich.buzzer.AppUpdate
import de.pabich.buzzer.BuildConfig
import de.pabich.buzzer.BuzzerViewModel
import de.pabich.buzzer.core.GameKind
import de.pabich.buzzer.core.PROTOCOL_VERSION
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
    var showShare by remember { mutableStateOf(false) }
    if (showShare) ShareDialog { showShare = false }
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
                MenuRow("App weitergeben", { showShare = true })
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
                "Quiz" to "Im Quiz kommen die Fragen aus der Fragen-Datenbank. Die Frage erscheint auf allen Geräten gleichzeitig, 3 Sekunden später die vier Antworten – bei jedem in anderer Reihenfolge. Tippe deine Antwort; aufgelöst wird, wenn alle geantwortet haben oder die Zeit um ist.",
                "Wertung im Quiz" to "Schnellster punktet: nur die erste richtige Antwort. Jeder punktet: jede richtige Antwort. Countdown: nach der ersten richtigen Antwort läuft nur noch kurz die Zeit, alle Richtigen bekommen ihre Restzeit. Battle: nur der Schnellste bekommt seine Restzeit. Falsche Antworten kosten nie etwas.",
                "Challenge" to "Hältst du eine Auflösung für falsch, tippe „Challenge“. Der Spielleiter prüft die Quelle und entscheidet: Wertung bleibt, Frage annullieren, Antwort korrigieren oder Frage sperren.",
                "Versionen" to "Passen die App-Versionen nicht zusammen, holt sich das ältere Gerät die neuere Version direkt vom anderen (ohne Internet) oder lädt sie von der Release-Seite.",
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
            Text("Buzzer ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}, Protokoll $PROTOCOL_VERSION)", color = theme.text, fontSize = 16.sp)
            UpdateSection(vm)
            SectionTitle("Fragen-Datenbank")
            Text("${vm.quizStore.questions.size} Fragen in ${vm.quizStore.packs.size} Paketen" + if (vm.quizDataVersion > 0) " (aktualisiert)" else "", color = theme.text, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                GhostButton("Fragen aktualisieren", { vm.refreshQuestions() }, Modifier.weight(1f))
                val context = LocalContext.current
                GhostButton("Statistik teilen", { vm.shareQuizStats(context) }, Modifier.weight(1f))
            }
            StatusText(vm)
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
                items(vm.groups, key = Group::endpointId) { group -> GroupCard(vm, group) }
            }
            if (vm.connectingTo != null) Text("Warte auf Bestätigung durch den Spielleiter …", color = theme.muted, fontSize = 15.sp)
            StatusText(vm)
        }
    }
}

@Composable
private fun GroupCard(vm: BuzzerViewModel, group: Group) {
    val theme = LocalBuzzerTheme.current
    val peer = group.peer
    val own = AppUpdate.ownBuild
    val theirs = peer.build
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(theme.panel)
            .clickable(enabled = vm.connectingTo == null && peer.compatible) { vm.join(group) }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, color = theme.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            when {
                vm.connectingTo == group.endpointId -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = theme.accent)
                peer.compatible -> Text("BEITRETEN", color = theme.accent, fontFamily = theme.display, fontSize = 18.sp)
                else -> Text("ANDERE VERSION", color = Color(0xFFFF9A9A), fontFamily = theme.display, fontSize = 16.sp)
            }
        }
        val info = when {
            peer.legacy -> "Der Spielleiter hat eine alte Version (Build 11 oder älter). Er muss einmal über „App weitergeben“ oder die Release-Seite aktualisieren."
            !peer.compatible && theirs != null && theirs > own -> "Deine Version (Build $own) passt nicht zu Build $theirs des Spielleiters."
            !peer.compatible -> "Der Spielleiter hat eine ältere, nicht passende Version (Build $theirs)."
            theirs != null && theirs > own -> "Der Spielleiter hat eine neuere Version (Build $theirs) – du kannst trotzdem mitspielen."
            else -> null
        }
        if (info != null) Text(info, color = theme.muted, fontSize = 14.sp)
        if (!peer.legacy && theirs != null && theirs != own && vm.connectingTo == null) {
            val label = if (theirs > own) "Build $theirs vom Spielleiter holen" else "Meine Version (Build $own) an den Spielleiter geben"
            TextButton(onClick = { vm.joinForUpdate(group) }) { Text(label, color = theme.accent2, fontWeight = FontWeight.Bold) }
        }
        if (!peer.compatible && !peer.legacy && (theirs ?: 0) > own) {
            TextButton(onClick = { vm.navigate(Screen.ABOUT) }) { Text("Neueste Version von der Release-Seite laden", color = theme.accent2) }
        }
    }
}

/** Nur zum Aktualisieren verbunden: Fortschritt und Installation. */
@Composable
fun UpdateScreen(vm: BuzzerViewModel) {
    val theme = LocalBuzzerTheme.current
    BackHandler { vm.leave() }
    ThemedScreen {
        BackHeader("Aktualisieren") { vm.leave() }
        ScrollPage {
            val t = vm.transfer
            Text("Verbunden mit ${vm.updatePeerName ?: "dem Spielleiter"} – nur zum Aktualisieren.", color = theme.text, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            when {
                t == null -> Text("Warte auf die Übertragung …", color = theme.muted, fontSize = 15.sp)
                t.failed -> Text("Die Übertragung ist abgebrochen.", color = Color(0xFFFF9A9A), fontSize = 15.sp)
                else -> TimeBar(
                    t.progress,
                    (if (t.incoming) "Empfange Build ${t.build}" else "Sende Build ${t.build}") + " – ${(t.progress * 100).toInt()} %" + if (t.done) " · fertig" else "",
                    theme.accent2,
                    Modifier.fillMaxWidth(),
                )
            }
            if (t?.done == true && !t.incoming) {
                Spacer(Modifier.height(12.dp))
                Text("Fertig – der Spielleiter installiert jetzt die neue Version. Danach kannst du wieder beitreten.", color = theme.text, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
            StatusText(vm)
            GhostButton("Zurück zum Start", { vm.leave() }, Modifier.fillMaxWidth())
        }
    }
}

/** Version prüfen und die neueste aus dem Repository laden (Über-Seite). */
@Composable
private fun UpdateSection(vm: BuzzerViewModel) {
    val theme = LocalBuzzerTheme.current
    val repo = vm.repoBuild
    Spacer(Modifier.height(8.dp))
    when {
        vm.downloadProgress != null -> TimeBar(vm.downloadProgress ?: 0f, "Lade die neueste Version …", theme.accent2, Modifier.fillMaxWidth())
        repo == null -> GhostButton("Nach neuer Version suchen", { vm.checkRepoUpdate() }, Modifier.fillMaxWidth())
        repo > AppUpdate.ownBuild -> {
            Text("Build $repo ist verfügbar.", color = theme.text, fontSize = 15.sp)
            PrimaryButton("Herunterladen und installieren", { vm.downloadFromRepo() }, Modifier.fillMaxWidth().padding(top = 8.dp), height = 52.dp)
        }
        else -> Text("Du hast die neueste Version.", color = theme.muted, fontSize = 15.sp)
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
            if (view.kind == GameKind.QUIZ) {
                QuizPlayerContent(
                    view = view,
                    name = vm.myName.trim(),
                    emotes = vm.emotes,
                    onEmoteDone = vm::emoteDone,
                    chat = vm.chat,
                    soundOn = vm.soundOn,
                    confettiSeq = vm.confettiSeq,
                    onTap = { pos, time -> vm.tapAnswer(pos, time) },
                    onChallenge = { vm.challenge(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                BuzzerPad(
                    view = view,
                    onDown = { vm.onTouchDown(it) },
                    onUp = { vm.onTouchUp(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    chat = vm.chat,
                    confettiSeq = vm.confettiSeq,
                )
            }
        }
        // Solange die Antwortzeit läuft, fliegen Emoticons nur in ihrer Bahn über dem Fragenfeld.
        if (view.quiz?.timerRunning != true) EmoteOverlay(vm.emotes, onDone = vm::emoteDone, modifier = Modifier.fillMaxSize())
        if (view.paused) PauseOverlay()
        vm.replay?.let { ReplayOverlay(it, onDone = vm::replayDone) }
    }

    if (showChat) {
        ChatDialog(
            recipients = view.roster,
            onSend = { to, name, text -> vm.sendChat(to, name, text) },
            onDismiss = { showChat = false; vm.chatTypingDone() },
            onTyping = { vm.chatTyping() },
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
    var showCorrection by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
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
                if (settings.messagesEnabled) {
                    BarButton("✉", enabled = vm.canChat) { showChat = true }
                    // Jemand tippt gerade: mit der nächsten Frage noch kurz warten.
                    if (snapshot.someoneTyping) TypingDots(Modifier.padding(end = 6.dp))
                }
            }
            val choosing = vm.choosingKind || snapshot.kind == null
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        val kindLabel = if (choosing) "Spiel leiten" else snapshot.kind?.label ?: ""
                        Kicker(kindLabel + (if (snapshot.kind == GameKind.BUZZER && !choosing) " · Runde ${snapshot.round}" else "") + if (snapshot.profileName.isNotEmpty()) " · ${snapshot.profileName}" else "")
                        BigTitle(if (choosing) "Spielart wählen" else phaseText(snapshot), color = theme.text, size = 26.sp)
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
                                text = { Text("Spielart wechseln (Quiz/Buzzer)") },
                                enabled = snapshot.phase == Phase.READY && !choosing,
                                onClick = { showMenu = false; vm.openKindChoice() },
                            )
                            if (snapshot.kind == GameKind.QUIZ) {
                                DropdownMenuItem(
                                    text = { Text("Quiz abbrechen") },
                                    enabled = snapshot.phase != Phase.READY,
                                    onClick = { showMenu = false; vm.stopQuiz() },
                                )
                            }
                            val outdated = vm.outdatedPlayers
                            if (outdated.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Alle auf Build ${AppUpdate.ownBuild} bringen (${outdated.size})") },
                                    onClick = { showMenu = false; vm.pushUpdateToAll() },
                                )
                            }
                            vm.newerPlayer?.let { (id, name, build) ->
                                DropdownMenuItem(
                                    text = { Text("Build $build von $name holen") },
                                    onClick = { showMenu = false; vm.fetchUpdateFrom(id) },
                                )
                            }
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
                                text = { Text(if (snapshot.paused) "Pause beenden" else "Spiel pausieren") },
                                enabled = snapshot.paused || pause,
                                onClick = { showMenu = false; vm.setPaused(!snapshot.paused) },
                            )
                            DropdownMenuItem(
                                text = { Text("Letzte Runde korrigieren") },
                                enabled = snapshot.canCorrectLastRound,
                                onClick = { showMenu = false; showCorrection = true },
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
                                text = { Text("App weitergeben") },
                                onClick = { showMenu = false; showShare = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Gruppe schließen") },
                                onClick = { showMenu = false; confirmLeave = true },
                            )
                        }
                    }
                }
                if (snapshot.paused) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.accent).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("PAUSE – Spieler sehen „Kurze Pause“", color = theme.ground, fontFamily = theme.display, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.setPaused(false) }) { Text("Weiter", color = theme.ground, fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            when {
                choosing -> {
                    KindChoice(vm, Modifier.weight(1f).fillMaxWidth())
                    Box(Modifier.padding(horizontal = 16.dp)) { StatusText(vm) }
                }
                snapshot.kind == GameKind.QUIZ -> {
                    QuizMasterBody(vm, snapshot)
                    Box(Modifier.padding(horizontal = 16.dp)) { StatusText(vm) }
                }
                else -> BuzzerMasterBody(vm, snapshot)
            }
        }
        // Spielt der Master im Quiz mit, bleiben Emoticons während der Frage über seinem Antwortfeld.
        val ownAnswers = snapshot.kind == GameKind.QUIZ && settings.masterPlays &&
            (snapshot.phase == Phase.QUIZ_QUESTION || snapshot.phase == Phase.QUIZ_ANSWERS)
        EmoteOverlay(
            vm.emotes,
            onDone = vm::emoteDone,
            modifier = if (ownAnswers) Modifier.fillMaxWidth().fillMaxHeight(0.5f) else Modifier.fillMaxSize(),
        )
        vm.replay?.let { ReplayOverlay(it, onDone = vm::replayDone) }
    }

    vm.joinRequests.firstOrNull()?.let { request ->
        val peer = request.peer
        val own = AppUpdate.ownBuild
        when {
            peer.legacy -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Alte App-Version") },
                text = {
                    Text(
                        "${request.name} hat eine alte Version (Build 11 oder älter) und kann nicht mitspielen. " +
                            "Bitte die App über „App weitergeben“ (QR-Code oder Bluetooth) aktualisieren – danach geht es automatisch.",
                    )
                },
                confirmButton = { TextButton(onClick = { vm.rejectJoin(request); showShare = true }) { Text("App weitergeben") } },
                dismissButton = { TextButton(onClick = { vm.rejectJoin(request) }) { Text("Ablehnen") } },
            )
            peer.updateOnly || !peer.compatible -> AlertDialog(
                onDismissRequest = {},
                title = { Text("App aktualisieren") },
                text = {
                    val theirs = peer.build ?: 0
                    Text(
                        if (theirs > own) {
                            "${request.name} hat eine neuere Version (Build $theirs, du: Build $own). Verbinden, um sie zu übernehmen?\nCode: ${request.authCode}"
                        } else {
                            "${request.name} möchte deine Version (Build $own) übernehmen (hat Build $theirs). Nur zum Aktualisieren, nicht zum Mitspielen.\nCode: ${request.authCode}"
                        },
                    )
                },
                confirmButton = { TextButton(onClick = { vm.acceptJoin(request) }) { Text("Verbinden") } },
                dismissButton = { TextButton(onClick = { vm.rejectJoin(request) }) { Text("Ablehnen") } },
            )
            else -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Beitrittsanfrage") },
                text = {
                    val theirs = peer.build
                    val note = if (theirs != null && theirs != own) "\nVersion: Build $theirs (du: Build $own)" else ""
                    Text("${request.name} möchte beitreten.\nCode: ${request.authCode}$note")
                },
                confirmButton = { TextButton(onClick = { vm.acceptJoin(request) }) { Text("Annehmen") } },
                dismissButton = { TextButton(onClick = { vm.rejectJoin(request) }) { Text("Ablehnen") } },
            )
        }
    }
    if (showCorrection) {
        CorrectionDialog(
            snapshot = snapshot,
            onCorrect = { winner -> vm.correctLastRound(winner); showCorrection = false },
            onDismiss = { showCorrection = false },
        )
    }
    if (showShare) ShareDialog { showShare = false }
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

/** Buzzer-Modus: Spielerkarten, Hauptknopf und (wenn der Master mitspielt) sein Buzzer. */
@Composable
private fun ColumnScope.BuzzerMasterBody(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot) {
    val theme = LocalBuzzerTheme.current
    val settings = snapshot.settings
    val pause = snapshot.phase == Phase.READY || snapshot.phase == Phase.ROUND_OVER
    Column(Modifier.weight(2f).padding(horizontal = 16.dp)) {
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
            items(snapshot.players, key = { it.id }) { PlayerCard(it, maxScore, vm.peerBuilds[it.id]) }
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
                enabled = snapshot.players.isNotEmpty() && !snapshot.paused,
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
private fun PlayerCard(row: MasterEngine.PlayerRow, maxScore: Long, build: Int? = null) {
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
                // Abweichende App-Version zeigen, damit der Master sie angleichen kann.
                val version = build?.takeIf { it != AppUpdate.ownBuild }?.let { "Build $it" }
                val detail = listOfNotNull(row.reactionMs?.let { "$it ms" }, row.rttMs?.let { "Funk $it ms" }, version).joinToString(" · ")
                Text(detail, color = theme.muted, fontSize = 13.sp)
            }
            ScoreBar(row.scoreValue, maxScore, theme.accent, Modifier.fillMaxWidth(), highlight = true)
        }
        Text(row.score, color = theme.text, fontFamily = theme.display, fontSize = 24.sp)
    }
}

private fun phaseText(snapshot: MasterEngine.Snapshot): String = when (snapshot.phase) {
    Phase.READY -> when {
        snapshot.kind == GameKind.QUIZ -> if (snapshot.quiz?.finished == true) "Quiz beendet" else "Quiz einrichten"
        snapshot.players.isEmpty() -> "Warte auf Spieler"
        else -> "Frage stellen"
    }
    Phase.WAIT_HOLD -> "Finger auflegen …"
    Phase.COUNTDOWN -> "Gleich geht's los"
    Phase.RUNNING -> "Buzzer offen!"
    Phase.SUDDEN_DEATH_PREP -> "Sudden Death"
    Phase.ANSWERING -> "Antwort von ${snapshot.players.firstOrNull { it.display == Display.GREEN }?.name ?: "…"}"
    Phase.ANSWER_TIMEOUT -> "Zeit abgelaufen"
    Phase.ROUND_OVER -> "Runde beendet"
    Phase.QUIZ_QUESTION, Phase.QUIZ_ANSWERS -> "Frage läuft"
    Phase.QUIZ_REVEAL -> "Auflösung"
}

/** Verdeckt das Spielfeld während einer Pause des Spielleiters. */
@Composable
fun PauseOverlay() {
    val theme = LocalBuzzerTheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.ground.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(96.dp).border(6.dp, theme.accent, CircleShape), contentAlignment = Alignment.Center) {
                Text("II", color = theme.accent, fontFamily = theme.display, fontSize = 44.sp)
            }
            BigTitle("Kurze Pause", size = 52.sp)
            Text("GLEICH GEHT ES WEITER", color = theme.accent2, fontFamily = theme.display, fontSize = 26.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Letzte Runde korrigieren: anderen Sieger wählen oder die Wertung stornieren. */
@Composable
private fun CorrectionDialog(snapshot: MasterEngine.Snapshot, onCorrect: (String?) -> Unit, onDismiss: () -> Unit) {
    val current = snapshot.lastRoundWinner
    var choice by remember { mutableStateOf(current) }
    val timeMode = snapshot.settings.scoring == de.pabich.buzzer.core.ScoringMode.REMAINING_TIME
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runde ${snapshot.lastRound ?: ""} korrigieren") },
        text = {
            Column {
                Text(
                    if (timeMode) {
                        "Der neue Sieger bekommt seine Restzeit aus dieser Runde gutgeschrieben (ohne Buzz: 0 s); dem bisherigen wird sie abgezogen."
                    } else {
                        "Der neue Sieger bekommt den Punkt, dem bisherigen wird er abgezogen."
                    },
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                for (p in snapshot.players.filter { it.connected }) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = choice == p.id, onClick = { choice = p.id }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == p.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(p.name + if (p.id == current) " (bisher)" else "")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = choice == null, onClick = { choice = null }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = choice == null, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Runde stornieren (niemand bekommt etwas)" + if (current == null) " (bisher)" else "")
                }
            }
        },
        confirmButton = { TextButton(enabled = choice != current, onClick = { onCorrect(choice) }) { Text("Korrigieren") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
