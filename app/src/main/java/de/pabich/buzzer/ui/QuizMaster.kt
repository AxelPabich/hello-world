package de.pabich.buzzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    val settings = snapshot.settings
    fun change(s: Settings) = vm.updateSettings(s)
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
        SetupTitle("Kategorien")
        FlowChips(
            items = categories,
            selected = { settings.quizCategories.isEmpty() || it in settings.quizCategories },
            onToggle = { cat ->
                val current = if (settings.quizCategories.isEmpty()) categories.toSet() else settings.quizCategories
                val next = if (cat in current) current - cat else current + cat
                change(settings.copy(quizCategories = if (next.size == categories.size) emptySet() else next.ifEmpty { setOf(cat) }))
            },
        )
        SetupTitle("Schwierigkeit")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (level in QuizLevel.entries) {
                val on = level in settings.quizLevels
                Chip(level.label, on, Modifier.weight(1f)) {
                    val next = if (on) settings.quizLevels - level else settings.quizLevels + level
                    if (next.isNotEmpty()) change(settings.copy(quizLevels = next))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stepper("Fragen", "${settings.quizCount}", Modifier.weight(1f),
                { change(settings.copy(quizCount = (settings.quizCount - 1).coerceAtLeast(1))) },
                { change(settings.copy(quizCount = (settings.quizCount + 1).coerceAtMost(50))) })
            Stepper("Antwortzeit", seconds(settings.quizAnswerMs).replace(",0 s", " s"), Modifier.weight(1f),
                { change(settings.copy(quizAnswerMs = (settings.quizAnswerMs - 5_000).coerceAtLeast(5_000))) },
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
                { change(settings.copy(quizCountdownMs = (settings.quizCountdownMs + 1_000).coerceAtMost(30_000))) })
        }
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
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuizHeader(quiz.number, quiz.total, quiz.question.category, quiz.question.level.label, Modifier.fillMaxWidth().padding(top = 10.dp))
            if (local == null) {
                // Der Master spielt nicht mit: Frage und Lösung nur für ihn.
                AnswerCountdown(quiz.deadline, quiz.durationMs, theme.accent2, Modifier.fillMaxWidth())
                Text(quiz.question.text, color = theme.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                for ((i, a) in quiz.question.answers.withIndex()) {
                    val correct = i == quiz.question.correctIndex
                    Text(
                        (if (correct) "✓ " else "· ") + a,
                        color = if (correct) Color(0xFF7CFF8A) else theme.muted,
                        fontSize = 14.sp,
                        fontWeight = if (correct) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Text("Lösung nur für dich sichtbar", color = theme.muted, fontSize = 12.sp)
            }
            val answered = quiz.rows.count { it.answered }
            Text("ANTWORTEN · $answered VON ${quiz.rows.size}", color = theme.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(theme.panel)) {
                for (row in quiz.rows) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .then(if (row.answered) Modifier.background(theme.accent2, CircleShape) else Modifier.border(2.dp, theme.line, CircleShape)),
                            contentAlignment = Alignment.Center,
                        ) { if (row.answered) Text("✓", color = theme.ground, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(10.dp))
                        Text(row.name, color = if (row.answered) theme.text else theme.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(row.elapsedMs?.let { seconds(it) } ?: "überlegt …", color = theme.muted, fontSize = 14.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Jetzt auflösen", { vm.revealNow() }, Modifier.weight(1f))
                GhostButton("Frage überspringen", { vm.skipQuestion() }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
        if (local != null) {
            QuizCard(
                quiz = local,
                onTap = { pos, time -> vm.tapAnswer(pos, time) },
                compact = true,
                title = "DEIN TIPP",
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
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
            Text("FRAGE ${quiz.number} / ${quiz.total} · AUFLÖSUNG", color = theme.accent, fontFamily = theme.display, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.panel).padding(12.dp)) {
                Text(q.text, color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(levelLine(vm, q.id, q.level), color = theme.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (quiz.annulled) {
                Text("Frage annulliert – keine Punkte.", color = Color(0xFFFF9A9A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            // Verteilung: wer hat was gewählt
            val total = quiz.rows.size.coerceAtLeast(1)
            for ((i, answer) in q.answers.withIndex()) {
                val names = quiz.rows.filter { it.chosen == i }.map { it.name }
                val correct = i == q.correctIndex && !quiz.annulled
                Column {
                    Row {
                        Text(answer, color = if (correct) theme.text else theme.muted, fontSize = 14.sp, fontWeight = if (correct) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${names.size}", color = theme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    if (names.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C2350))) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(names.size.toFloat() / total).background(if (correct) Color(0xFF1FA34A) else QuizRed),
                            )
                            Text(names.joinToString(" · "), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                        }
                    }
                }
            }
            val none = quiz.rows.filter { it.outcome == QuizOutcome.NONE }.map { it.name }
            if (none.isNotEmpty()) Text("Keine Antwort: ${none.joinToString()}", color = theme.muted, fontSize = 14.sp)
            val winners = quiz.rows.filter { it.gained > 0 }.sortedBy { it.elapsedMs ?: Long.MAX_VALUE }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (winners.isEmpty()) theme.line else QuizGreen).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    if (winners.isEmpty()) "Diesmal keine Punkte" else winners.joinToString { "${it.name} +${formatScore(it.gained, snapshot.scoring)}" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (quiz.challenges.isNotEmpty() || decisionsOpen) {
                ChallengeBox(quiz, onDecide = { decision ->
                    if (decision == ChallengeDecision.CORRECT) correcting = true else vm.decideChallenge(decision)
                    decisionsOpen = false
                }, sourceUrl = vm.quizStore.question(q.id)?.source.orEmpty())
            } else {
                quiz.decision?.let { Text("Entscheidung: ${it.label}", color = theme.muted, fontSize = 14.sp) }
                TextButton(onClick = { decisionsOpen = true }) { Text("Wertung dieser Frage ändern …", color = theme.accent2) }
            }
            if (vm.chat.isNotEmpty()) ChatPanel(vm.chat, theme.text, Modifier.fillMaxWidth(), maxLines = 3)
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Rangliste", { showBoard = true }, Modifier.height(60.dp))
            PrimaryButton(
                if (quiz.hasNext) "Nächste Frage" else "Ergebnis zeigen",
                { vm.nextQuestion() },
                Modifier.weight(1f),
                enabled = !snapshot.paused,
            )
        }
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
private fun ChallengeBox(quiz: MasterEngine.QuizSnapshot, onDecide: (ChallengeDecision) -> Unit, sourceUrl: String) {
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
    }
}

@Composable
private fun DecisionRow(decisions: List<ChallengeDecision>, onDecide: (ChallengeDecision) -> Unit) {
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
                    .clickable { onDecide(d) }
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
            PrimaryButton(
                "Quiz starten",
                { vm.startQuiz() },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                enabled = snapshot.players.any { it.connected } && !snapshot.paused,
                height = 60.dp,
            )
        }
    }
}
