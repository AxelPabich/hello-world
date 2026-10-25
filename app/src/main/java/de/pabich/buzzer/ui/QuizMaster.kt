package de.pabich.buzzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.BuzzerViewModel
import de.pabich.buzzer.core.ChallengeDecision
import de.pabich.buzzer.core.maxCountdownMs
import de.pabich.buzzer.core.Difficulty
import de.pabich.buzzer.core.GameKind
import de.pabich.buzzer.core.MasterEngine
import de.pabich.buzzer.core.MasterEngine.Phase
import de.pabich.buzzer.core.QuizLevel
import de.pabich.buzzer.core.QuizOutcome
import de.pabich.buzzer.core.QuizScoring
import de.pabich.buzzer.core.Settings
import de.pabich.buzzer.core.formatScore
import java.util.Locale
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------- Spielart wählen

@Composable
fun KindChoice(vm: BuzzerViewModel, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BigTitle("Welche Art von Spiel?", color = theme.text, size = 32.sp)
        Text("Du kannst nach jedem Spiel neu wählen. Ein Wechsel beginnt ein neues Spiel.", color = theme.muted, fontSize = 15.sp)
        KindTile(
            title = "Buzzer",
            text = "Du stellst die Fragen selbst. Wer zuerst drückt, darf antworten – du entscheidest Richtig oder Falsch.",
            color = theme.accent,
            selected = vm.masterSnapshot?.kind == GameKind.BUZZER,
            icon = { Box(Modifier.size(72.dp).background(theme.logo, CircleShape).border(5.dp, Color(0xFF8E1B1B), CircleShape)) },
            onClick = { vm.chooseKind(GameKind.BUZZER) },
        )
        KindTile(
            title = "Quiz",
            text = "Fragen mit vier Antworten aus der Fragen-Datenbank. Die App wertet selbst aus – du kannst mitspielen.",
            color = theme.accent2,
            selected = vm.masterSnapshot?.kind == GameKind.QUIZ,
            icon = {
                Column(Modifier.size(72.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (r in 0 until 2) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (c in 0 until 2) {
                                val color = when (r * 2 + c) {
                                    0 -> theme.accent2
                                    3 -> theme.accent
                                    else -> theme.line
                                }
                                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(color))
                            }
                        }
                    }
                }
            },
            onClick = { vm.chooseKind(GameKind.QUIZ) },
        )
        val count = vm.masterSnapshot?.players?.count { it.connected && it.id != BuzzerViewModel.LOCAL_PLAYER_ID } ?: 0
        Text(
            if (count == 0) "Mitspieler können schon jetzt beitreten." else "$count Mitspieler verbunden.",
            color = theme.muted,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun KindTile(title: String, text: String, color: Color, selected: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val theme = LocalBuzzerTheme.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.panel)
            .border(2.dp, if (selected) color else theme.line, shape)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), color = color, fontFamily = theme.display, fontSize = 26.sp)
            Text(text, color = theme.text, fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

// ---------------------------------------------------------------------- Quiz einrichten

@Composable
fun QuizSetup(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val context = LocalContext.current
    // Änderungen erst nach kurzer Ruhe übernehmen – nicht bei jedem Tipp auf +/−.
    var draft by remember(snapshot.settings) { mutableStateOf(snapshot.settings) }
    LaunchedEffect(draft) {
        if (draft != snapshot.settings) {
            delay(700)
            vm.updateSettings(draft)
        }
    }
    val settings = draft
    fun change(s: Settings) {
        draft = s
    }
    val dataVersion = vm.quizDataVersion
    val categories = remember(dataVersion) { vm.quizStore.categories }
    val players = snapshot.players.count { it.connected }
    val picked = remember(settings, dataVersion, players) { vm.pickQuestions(settings) }
    val finished = snapshot.quiz?.finished == true

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (finished) {
            Spacer(Modifier.height(6.dp))
            BigTitle("Quiz beendet", size = 30.sp)
            val max = snapshot.players.maxOfOrNull { it.scoreValue } ?: 0
            for ((i, p) in snapshot.players.filter { it.connected }.sortedByDescending { it.scoreValue }.withIndex()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", color = theme.accent, fontFamily = theme.display, fontSize = 20.sp, modifier = Modifier.width(36.dp))
                    Text(p.name, color = theme.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ScoreBar(p.scoreValue, max, theme.accent, Modifier.weight(1f), highlight = i == 0)
                    Text(p.score, color = theme.text, fontFamily = theme.display, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text("Neues Quiz:", color = theme.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
        TemplateHeader(vm, snapshot)
        SetupTitle("Kategorien")
        var categoryPopup by remember { mutableStateOf(false) }
        val chosen = if (settings.quizCategories.isEmpty()) categories.size else settings.quizCategories.count { it in categories }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.panel)
                .clickable { categoryPopup = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (chosen == categories.size) "Alle Kategorien ($chosen)" else "$chosen von ${categories.size} Kategorien",
                    color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                if (chosen != categories.size) {
                    Text(
                        categories.filter { it in settings.quizCategories }.joinToString(),
                        color = theme.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("Auswählen ▸", color = theme.accent2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        if (categoryPopup) {
            AlertDialog(
                onDismissRequest = { categoryPopup = false },
                containerColor = theme.panel,
                title = { Text("Kategorien", color = theme.text, fontFamily = theme.display) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Alle", chosen == categories.size, Modifier.weight(1f)) { change(settings.copy(quizCategories = emptySet())) }
                            Chip("Keine", false, Modifier.weight(1f)) {
                                change(settings.copy(quizCategories = categories.firstOrNull()?.let(::setOf) ?: emptySet()))
                            }
                        }
                        for (cat in categories) {
                            val on = settings.quizCategories.isEmpty() || cat in settings.quizCategories
                            Chip(cat, on, Modifier.fillMaxWidth()) {
                                val current = if (settings.quizCategories.isEmpty()) categories.toSet() else settings.quizCategories
                                val next = if (cat in current) current - cat else current + cat
                                change(settings.copy(quizCategories = if (next.size == categories.size) emptySet() else next.ifEmpty { setOf(cat) }))
                            }
                        }
                        Text("Mindestens eine Kategorie bleibt gewählt.", color = theme.muted, fontSize = 12.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { categoryPopup = false }) { Text("Fertig", color = theme.accent, fontWeight = FontWeight.Bold) } },
            )
        }
        SetupTitle("Schwierigkeit")
        // Die Stufen kommen aus der Fragendatenbank (index.json) – neue erscheinen hier von selbst.
        val levels = remember(dataVersion) { QuizLevel.entries }
        FlowChips(
            items = levels.map { it.label },
            selected = { label -> levels.first { it.label == label } in settings.quizLevels },
            onToggle = { label ->
                val level = levels.first { it.label == label }
                val next = if (level in settings.quizLevels) settings.quizLevels - level else settings.quizLevels + level
                if (next.isNotEmpty()) change(settings.copy(quizLevels = next))
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stepper("Fragen", "${settings.quizCount}", Modifier.weight(1f),
                { change(settings.copy(quizCount = (settings.quizCount - 1).coerceAtLeast(1))) },
                { change(settings.copy(quizCount = (settings.quizCount + 1).coerceAtMost(50))) })
            Stepper("Antwortzeit", seconds(settings.quizAnswerMs).replace(",0 s", " s"), Modifier.weight(1f),
                {
                    val answer = (settings.quizAnswerMs - 5_000).coerceAtLeast(5_000)
                    change(settings.copy(quizAnswerMs = answer, quizCountdownMs = settings.quizCountdownMs.coerceAtMost(maxCountdownMs(answer))))
                },
                { change(settings.copy(quizAnswerMs = (settings.quizAnswerMs + 5_000).coerceAtMost(120_000))) })
        }
        SetupTitle("Wertung")
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(theme.panel)) {
            for (mode in QuizScoring.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = settings.quizScoring == mode, onClick = { change(settings.copy(quizScoring = mode)) })
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.quizScoring == mode, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(mode.label, color = theme.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(mode.description, color = theme.muted, fontSize = 13.sp)
                    }
                }
            }
        }
        if (settings.quizScoring == QuizScoring.COUNTDOWN) {
            Stepper("Countdown nach erster richtiger Antwort", seconds(settings.quizCountdownMs).replace(",0 s", " s"), Modifier.fillMaxWidth(),
                { change(settings.copy(quizCountdownMs = (settings.quizCountdownMs - 1_000).coerceAtLeast(1_000))) },
                { change(settings.copy(quizCountdownMs = (settings.quizCountdownMs + 1_000).coerceAtMost(maxCountdownMs(settings.quizAnswerMs)))) })
            Text(
                "Nach der ersten richtigen Antwort bleiben den anderen noch ${seconds(settings.quizCountdownMs).replace(",0 s", " s")} " +
                    "(höchstens die halbe Antwortzeit).",
                color = LocalBuzzerTheme.current.muted,
                fontSize = 13.sp,
            )
        }
        // Optional: nach der Auflösung startet die Bereit-Abfrage von selbst.
        ToggleRow("Bereit-Abfrage automatisch", settings.quizAutoReadyMs > 0) { on ->
            change(settings.copy(quizAutoReadyMs = if (on) 10_000 else 0))
        }
        if (settings.quizAutoReadyMs > 0) {
            Stepper("… nach der Auflösung", seconds(settings.quizAutoReadyMs).replace(",0 s", " s"), Modifier.fillMaxWidth(),
                { change(settings.copy(quizAutoReadyMs = (settings.quizAutoReadyMs - 5_000).coerceAtLeast(5_000))) },
                { change(settings.copy(quizAutoReadyMs = (settings.quizAutoReadyMs + 5_000).coerceAtMost(120_000))) })
            Text(
                "Danach kommt bei allen „BEREIT?“; sind alle bereit, startet die nächste Frage. Bei einer offenen Challenge oder Pause wartet die Automatik.",
                color = LocalBuzzerTheme.current.muted,
                fontSize = 13.sp,
            )
        }
        ToggleRow("Antworten für jeden Spieler anders mischen", settings.quizShuffleAnswers) { change(settings.copy(quizShuffleAnswers = it)) }
        Text(
            if (settings.quizShuffleAnswers) "Jeder sieht die Antworten in eigener Reihenfolge."
            else "Alle sehen die Antworten in derselben Reihenfolge – gleiche Chancen bei Zeitwertung.",
            color = theme.muted, fontSize = 13.sp,
        )
        ToggleRow("Lesezeit der Frage anpassen", settings.quizReadAdapt) { change(settings.copy(quizReadAdapt = it)) }
        Text(
            "Die Pause zwischen Frage und Antworten richtet sich nach Fragenlänge und dem langsamsten Lesetempo der Spieler.",
            color = theme.muted, fontSize = 13.sp,
        )
        ToggleRow("Spielregeln vor Spielbeginn bestätigen", settings.rulesConfirm) { change(settings.copy(rulesConfirm = it)) }
        ToggleRow("Ich spiele mit", settings.masterPlays) { change(settings.copy(masterPlays = it)) }
        Row(
            Modifier.fillMaxWidth().border(1.dp, theme.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${picked.fresh} neue von ${picked.matching} passenden Fragen (${vm.quizStore.questions.size} insgesamt)",
                color = theme.text,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.refreshQuestions() }) { Text("Aktualisieren", color = theme.accent2, fontWeight = FontWeight.Bold) }
        }
        if (picked.fresh < settings.quizCount && picked.matching > 0) {
            Text(
                "Nicht genug neue Fragen – bekannte kommen erneut dran (die ältesten zuerst). Neue Fragen bei Claude anfordern und dann „Aktualisieren“.",
                color = theme.muted,
                fontSize = 13.sp,
            )
        }
        TextButton(onClick = { vm.shareQuizStats(context) }) { Text("Statistik teilen (.buzzstats)", color = theme.accent2) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SetupTitle(text: String) {
    val theme = LocalBuzzerTheme.current
    Text(text.uppercase(), color = theme.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = LocalBuzzerTheme.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (on) theme.accent2 else Color.Transparent)
            .border(2.dp, if (on) theme.accent2 else theme.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) theme.ground else theme.text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
    }
}

/** Einfacher Zeilenumbruch für Chips (ohne experimentelle FlowRow). */
@Composable
private fun FlowChips(items: List<String>, selected: (String) -> Boolean, onToggle: (String) -> Unit) {
    // Je zwei Kategorien pro Zeile – lange Namen passen so auch auf schmale Handys.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in row) Chip(item, selected(item), Modifier.weight(1f)) { onToggle(item) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, modifier: Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    val theme = LocalBuzzerTheme.current
    Column(modifier) {
        SetupTitle(label)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.panel),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clickable(onClick = onMinus), contentAlignment = Alignment.Center) {
                Text("−", color = theme.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text(value, color = theme.text, fontFamily = theme.display, fontSize = 20.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.size(48.dp).clickable(onClick = onPlus), contentAlignment = Alignment.Center) {
                Text("+", color = theme.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------- Frage läuft

@Composable
fun QuizMasterRunning(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot, quiz: MasterEngine.QuizSnapshot, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val local = vm.playerView?.quiz?.takeIf { snapshot.settings.masterPlays && it.number == quiz.number }
    // Wie bei den Spielern: Kopfzeile, große Frage mit den vier Antwortfeldern. Darunter nur, was der Spielleiter zusätzlich braucht.
    Column(modifier.padding(horizontal = 16.dp)) {
        QuizHeader(quiz.number, quiz.total, quiz.question.category, quiz.question.level.label, Modifier.fillMaxWidth().padding(top = 4.dp))
        if (local != null) {
            QuizCard(
                quiz = local,
                onTap = { pos, time -> vm.tapAnswer(pos, time) },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            )
        } else {
            // Der Master spielt nicht mit: dieselbe Karte, aber mit der markierten Lösung – nur für ihn.
            AnswerCountdown(quiz.deadline, quiz.durationMs, theme.accent2, Modifier.fillMaxWidth().padding(top = 8.dp))
            QuizCard(
                quiz = remember(quiz.number) { solutionView(quiz) },
                onTap = { _, _ -> },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            )
            Text("Lösung nur für dich sichtbar", color = theme.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        AnswerTracker(quiz, Modifier.padding(top = 8.dp))
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Jetzt auflösen", { vm.revealNow() }, Modifier.weight(1f))
            GhostButton("Frage überspringen", { vm.skipQuestion() }, Modifier.weight(1f))
        }
    }
}

/** Die Frage so, wie die Spieler sie sehen – mit der richtigen Antwort markiert (für einen Spielleiter, der nicht mitspielt). */
private fun solutionView(quiz: MasterEngine.QuizSnapshot): de.pabich.buzzer.core.PlayerEngine.QuizView {
    val q = quiz.question
    return de.pabich.buzzer.core.PlayerEngine.QuizView(
        number = quiz.number, total = quiz.total, category = q.category, level = q.level, text = q.text, answers = q.answers,
        questionAt = 0, answersAt = 0, durationMs = quiz.durationMs, deadlineMs = quiz.durationMs,
        stage = de.pabich.buzzer.core.PlayerEngine.QuizStage.REVEALED,
        result = de.pabich.buzzer.core.Message.QuizResult(
            number = quiz.number, correctPosition = q.correctIndex, chosenPosition = -1, outcome = QuizOutcome.NONE,
            gained = "", score = "", explanation = "", source = "", corrected = false,
        ),
    )
}

/** Wer schon geantwortet hat – eine Zeile zum Wischen, damit die Antwortfelder den Platz behalten. */
@Composable
private fun AnswerTracker(quiz: MasterEngine.QuizSnapshot, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    val answered = quiz.rows.count { it.answered }
    Column(modifier.fillMaxWidth()) {
        Text("ANTWORTEN · $answered VON ${quiz.rows.size}", color = theme.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(
            Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (row in quiz.rows) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(theme.panel).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .then(if (row.answered) Modifier.background(theme.accent2, CircleShape) else Modifier.border(2.dp, theme.line, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (row.answered) Text("✓", color = theme.ground, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        de.pabich.buzzer.core.withAvatar(row.avatar, row.name),
                        color = if (row.answered) theme.text else theme.muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        row.elapsedMs?.let { seconds(it) } ?: " …",
                        color = theme.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Auflösung

@Composable
fun QuizMasterReveal(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot, quiz: MasterEngine.QuizSnapshot, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    var correcting by remember(quiz.number) { mutableStateOf(false) }
    var decisionsOpen by remember(quiz.number) { mutableStateOf(false) }
    var showBoard by remember { mutableStateOf(false) }
    val q = quiz.question
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Wie bei den Spielern: Kopfzeile und – wenn der Spielleiter mitspielt – sein Ergebnis als Banner.
            QuizHeader(quiz.number, quiz.total, q.category, q.level.label, Modifier.fillMaxWidth().padding(top = 10.dp))
            vm.playerView?.quiz?.takeIf { snapshot.settings.masterPlays && it.number == quiz.number }?.result?.let { r ->
                QuizResultBanner(r, vm.soundOn, Modifier.fillMaxWidth())
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.panel).padding(12.dp)) {
                Text(q.text, color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(levelLine(vm, q.id, q.level), color = theme.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (quiz.annulled) {
                Text("Frage annulliert – keine Punkte.", color = Color(0xFFFF9A9A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            // Verteilung: wer hat was gewählt – die richtige Antwort als grüne Karte mit Haken.
            val total = quiz.rows.size.coerceAtLeast(1)
            for ((i, answer) in q.answers.withIndex()) {
                val names = quiz.rows.filter { it.chosen == i }.map { it.name }
                val isRight = i == q.correctIndex
                val correct = isRight && !quiz.annulled
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (correct) QuizGreen else theme.panel)
                        .then(
                            when {
                                correct -> Modifier.border(3.dp, Color(0xFF8CF5B0), RoundedCornerShape(12.dp))
                                isRight -> Modifier.border(2.dp, theme.muted, RoundedCornerShape(12.dp))
                                else -> Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = if (correct) 12.dp else 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (isRight) "✓ " else "") + answer + if (isRight && quiz.annulled) " (galt als richtig)" else "",
                            color = if (correct) Color.White else theme.muted,
                            fontSize = if (correct) 19.sp else 14.sp,
                            fontWeight = if (correct) FontWeight.ExtraBold else FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${names.size}",
                            color = if (correct) Color.White else theme.text,
                            fontSize = if (correct) 19.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (names.isNotEmpty()) {
                        Box(
                            Modifier.padding(top = 6.dp).fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (correct) Color(0xFF0B5228) else Color(0xFF1C2350)),
                        ) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(names.size.toFloat() / total)
                                    .background(if (correct) Color(0xFF1FA34A) else QuizRed),
                            )
                            Text(names.joinToString(" · "), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                        }
                    }
                }
            }
            val none = quiz.rows.filter { it.outcome == QuizOutcome.NONE }.map { it.name }
            if (none.isNotEmpty()) Text("Keine Antwort: ${none.joinToString()}", color = theme.muted, fontSize = 14.sp)
            val winners = quiz.rows.filter { it.gained > 0 }.sortedBy { it.elapsedMs ?: Long.MAX_VALUE }
            if (!quiz.resumed) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (winners.isEmpty()) theme.line else QuizGreen).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    if (winners.isEmpty()) "Diesmal keine Punkte" else winners.joinToString { "${it.name} +${formatScore(it.gained, snapshot.scoring)}" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (q.explanation.isNotEmpty()) {
                Text(q.explanation, color = theme.text, fontSize = 14.sp, lineHeight = 19.sp)
            }
            if (quiz.resumed) {
                Text("Diese Frage lief noch beim bisherigen Spielleiter – die Punkte sind übernommen.", color = theme.muted, fontSize = 14.sp)
            } else if (quiz.challenges.isNotEmpty() || decisionsOpen || snapshot.reviewing) {
                if (!snapshot.reviewing) {
                    if (quiz.challenges.isNotEmpty() || quiz.decision == null) {
                        TextButton(onClick = { vm.startReview() }) { Text("Spieler bitten zu warten (Laufband)", color = theme.accent2) }
                    }
                    TextButton(onClick = { decisionsOpen = false }) { Text("Schließen", color = theme.muted) }
                } else {
                    Text("Spieler sehen: „Bitte warte kurz – die Antworten werden überprüft“", color = theme.accent, fontSize = 13.sp)
                }
                ChallengeBox(quiz, onDecide = { decision ->
                    when (decision) {
                        ChallengeDecision.CORRECT -> correcting = true
                        ChallengeDecision.REPLACE -> vm.replaceQuestion()
                        else -> vm.decideChallenge(decision)
                    }
                    decisionsOpen = false
                }, sourceUrl = vm.quizStore.question(q.id)?.source.orEmpty(), replaceAvailable = remember(quiz.number) { vm.replacementQuestion() != null })
            } else {
                quiz.decision?.let { Text("Entscheidung: ${it.label}", color = theme.muted, fontSize = 14.sp) }
                // Der Spielleiter kann selbst eine Prüfung starten; die Spieler warten so lange.
                // Nach einer Entscheidung öffnet der Knopf nur die Auswahl – ohne Laufband bei den Spielern.
                if (quiz.decision == null) {
                    GhostButton("Antwort prüfen (Challenge)", { decisionsOpen = true; vm.startReview() }, Modifier.fillMaxWidth())
                } else {
                    GhostButton("Entscheidung ändern", { decisionsOpen = true }, Modifier.fillMaxWidth())
                }
            }
            if (vm.chat.isNotEmpty()) ChatPanel(vm.chat, theme.text, Modifier.fillMaxWidth(), maxLines = 3)
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Rangliste", { showBoard = true }, Modifier.height(60.dp))
            if (quiz.hasNext) GetReadyButton(vm, snapshot, Modifier.height(60.dp))
            PrimaryButton(
                if (quiz.hasNext) "Nächste Frage" else "Ergebnis zeigen",
                { vm.nextQuestion() },
                Modifier.weight(1f),
                enabled = !snapshot.paused,
            )
        }
    }
    vm.openChallengeNames?.let { names ->
        AlertDialog(
            onDismissRequest = { vm.answerOpenChallenge(keep = false) },
            title = { Text("Offene Challenge") },
            text = {
                Text(
                    (if (names.isNotEmpty()) "Offene Challenge von $names" else "Die Prüfung läuft noch") +
                        " – Wertung bleibt? Die Spieler sehen bis dahin das Laufband.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.answerOpenChallenge(keep = true) }) { Text("Wertung bleibt") } },
            dismissButton = { TextButton(onClick = { vm.answerOpenChallenge(keep = false); decisionsOpen = true }) { Text("Zurück zur Prüfung") } },
        )
    }
    if (correcting) {
        var choice by remember { mutableStateOf(q.correctIndex) }
        AlertDialog(
            onDismissRequest = { correcting = false },
            title = { Text("Richtige Antwort korrigieren") },
            text = {
                Column {
                    Text("Die Punkte dieser Frage werden neu berechnet. Die Korrektur gilt auch für spätere Spiele.", fontSize = 14.sp)
                    for ((i, a) in q.answers.withIndex()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = choice == i, onClick = { choice = i }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == i, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(a + if (i == q.correctIndex) " (bisher)" else "")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = choice != q.correctIndex, onClick = { vm.decideChallenge(ChallengeDecision.CORRECT, choice); correcting = false }) { Text("Korrigieren") }
            },
            dismissButton = { TextButton(onClick = { correcting = false }) { Text("Abbrechen") } },
        )
    }
    if (showBoard) {
        AlertDialog(
            onDismissRequest = { showBoard = false },
            title = { Text("Rangliste") },
            text = {
                Column {
                    for ((i, p) in snapshot.players.filter { it.connected }.sortedByDescending { it.scoreValue }.withIndex()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${i + 1}. ${p.name}", modifier = Modifier.weight(1f))
                            Text(p.score, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBoard = false }) { Text("Schließen") } },
        )
    }
}

@Composable
private fun ChallengeBox(quiz: MasterEngine.QuizSnapshot, onDecide: (ChallengeDecision) -> Unit, sourceUrl: String, replaceAvailable: Boolean) {
    val theme = LocalBuzzerTheme.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, theme.accent, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CHALLENGE", color = theme.accent, fontFamily = theme.display, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(quiz.challenges.joinToString { it.name }.ifEmpty { "Korrektur durch dich" }, color = theme.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (sourceUrl.isNotEmpty()) TextButton(onClick = { runCatching { uri.openUri(sourceUrl) } }) { Text("Quelle", color = theme.accent2) }
        }
        for (c in quiz.challenges.filter { it.reason.isNotEmpty() }) {
            Text("${c.name}: „${c.reason}“", color = theme.text, fontSize = 14.sp)
        }
        DecisionRow(listOf(ChallengeDecision.KEEP, ChallengeDecision.ANNUL), onDecide)
        DecisionRow(listOf(ChallengeDecision.CORRECT, ChallengeDecision.BLOCK), onDecide)
        DecisionRow(listOf(ChallengeDecision.REPLACE), onDecide, enabled = replaceAvailable)
        if (!replaceAvailable) Text("Keine Ersatzfrage verfügbar.", color = theme.muted, fontSize = 13.sp)
    }
}

@Composable
private fun DecisionRow(decisions: List<ChallengeDecision>, onDecide: (ChallengeDecision) -> Unit, enabled: Boolean = true) {
    val theme = LocalBuzzerTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (d in decisions) {
            val primary = d == ChallengeDecision.KEEP
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (primary) theme.accent else Color.Transparent)
                    .border(2.dp, if (primary) theme.accent else theme.line, RoundedCornerShape(12.dp))
                    .alpha(if (enabled) 1f else 0.4f)
                    .clickable(enabled = enabled) { onDecide(d) }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(d.label, color = if (primary) theme.ground else theme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** „Stufe – Autor: mittel · gemessen: leicht (14 Antworten, 57 % richtig)“ */
private fun levelLine(vm: BuzzerViewModel, id: String, author: QuizLevel): String {
    val q = vm.quizStore.question(id) ?: return "Stufe: ${author.label}"
    val s = vm.quizStore.stats.of(q)
    val measured = Difficulty.measured(s, q.level)
    val share = if (s.answers > 0) String.format(Locale.GERMANY, "%d %% richtig", Math.round(100.0 * s.correct / s.answers)) else "noch keine Antworten"
    return "Stufe – Autor: ${q.level.label.lowercase()} · gemessen: " +
        (measured?.label?.lowercase() ?: "offen") + " (${s.answers} Antworten, $share)"
}

/** Inhalt des Master-Bildschirms im Quiz-Modus (unter der oberen Leiste). */
@Composable
fun ColumnScope.QuizMasterBody(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot) {
    val quiz = snapshot.quiz
    when {
        snapshot.phase == Phase.QUIZ_REVEAL && quiz != null -> QuizMasterReveal(vm, snapshot, quiz, Modifier.weight(1f).fillMaxWidth())
        (snapshot.phase == Phase.QUIZ_QUESTION || snapshot.phase == Phase.QUIZ_ANSWERS) && quiz != null ->
            QuizMasterRunning(vm, snapshot, quiz, Modifier.weight(1f).fillMaxWidth())
        else -> {
            QuizSetup(vm, snapshot, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GetReadyButton(vm, snapshot, Modifier.height(60.dp))
                PrimaryButton(
                    "Quiz starten",
                    { vm.startQuiz() },
                    Modifier.weight(1f),
                    enabled = snapshot.players.any { it.connected } && !snapshot.paused,
                    height = 60.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- Quiz-Vorlagen

/** Nach „Quiz“: erst eine Quiz-Vorlage wählen (oder mit den aktuellen Einstellungen weiter). */
@Composable
fun QuizTemplateChoice(vm: BuzzerViewModel, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    var naming by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigTitle("Quiz-Vorlage wählen", color = theme.text, size = 28.sp)
        Text("Vorlagen enthalten Kategorien, Stufen, Anzahl, Zeiten und Wertung. Sie sind getrennt von den Buzzer-Profilen.", color = theme.muted, fontSize = 14.sp)
        val current = vm.masterSnapshot?.profileName.orEmpty()
        for (t in vm.quizTemplates) {
            val s = runCatching { t.decode().settings }.getOrNull()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(theme.panel)
                    .border(2.dp, if (t.name == current) theme.accent2 else theme.line, RoundedCornerShape(16.dp))
                    .clickable { vm.useQuizTemplate(t.name) }
                    .padding(16.dp),
            ) {
                Text(t.name, color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (s != null) {
                    Text(
                        "${s.quizCount} Fragen · ${seconds(s.quizAnswerMs)} · ${s.quizScoring.label} · " +
                            s.quizLevels.sortedBy { it.ordinal }.joinToString("/") { it.label } +
                            if (s.quizCategories.isEmpty()) " · alle Kategorien" else " · ${s.quizCategories.size} Kategorien",
                        color = theme.muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        if (vm.quizTemplates.isEmpty()) Text("Noch keine Quiz-Vorlagen.", color = theme.muted, fontSize = 15.sp)
        GhostButton("Mit den aktuellen Einstellungen weiter", { vm.useQuizTemplate(null) }, Modifier.fillMaxWidth())
        GhostButton("Neue Vorlage anlegen …", { naming = true }, Modifier.fillMaxWidth())
    }
    if (naming) NameDialog("Neue Quiz-Vorlage", "Familienabend leicht", { naming = false }) { name ->
        naming = false
        vm.useQuizTemplate(null)
        vm.saveQuizTemplate(name)
    }
}

/** Kopf der Quiz-Einrichtung: aktive Vorlage, speichern, wechseln, senden. */
@Composable
private fun TemplateHeader(vm: BuzzerViewModel, snapshot: MasterEngine.Snapshot) {
    val theme = LocalBuzzerTheme.current
    var naming by remember { mutableStateOf(false) }
    val name = snapshot.profileName
    val isTemplate = vm.quizTemplates.any { it.name == name }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.panel).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            if (isTemplate) "VORLAGE: ${name.uppercase()}" else "OHNE VORLAGE",
            color = theme.accent2,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Row {
            if (isTemplate) TextButton(onClick = { vm.saveQuizTemplate(name) }) { Text("Speichern", color = theme.accent) }
            TextButton(onClick = { naming = true }) { Text("Speichern unter …", color = theme.accent) }
            TextButton(onClick = { vm.choosingTemplate = true }) { Text("Wechseln", color = theme.accent) }
        }
        if (isTemplate && snapshot.players.any { it.connected && it.id != BuzzerViewModel.LOCAL_PLAYER_ID }) {
            TextButton(onClick = { vm.shareProfile() }) { Text("Vorlage an Spieler senden", color = theme.accent2) }
        }
    }
    if (naming) NameDialog("Quiz-Vorlage speichern", name.ifEmpty { "Mein Quiz" }, { naming = false }) {
        naming = false
        vm.saveQuizTemplate(it)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
