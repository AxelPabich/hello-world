package de.pabich.buzzer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bildschirm im Stil des Designs: Grundfarbe, volle Fläche (Systemleisten sind schon ausgespart). */
@Composable
fun ThemedScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalBuzzerTheme.current
    Column(modifier.fillMaxSize().background(theme.ground), content = content)
}

@Composable
fun BigTitle(text: String, modifier: Modifier = Modifier, color: Color = LocalBuzzerTheme.current.accent, size: TextUnit = 40.sp) {
    Text(text.uppercase(), modifier = modifier, color = color, fontFamily = LocalBuzzerTheme.current.display, fontSize = size, lineHeight = size * 1.1f)
}

/** Kleine Überzeile in Großbuchstaben. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    Text(text.uppercase(), modifier = modifier, color = theme.accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = LocalBuzzerTheme.current.accent,
    content: Color = LocalBuzzerTheme.current.ground,
    height: Dp = 60.dp,
) {
    val theme = LocalBuzzerTheme.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = height),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = theme.line,
            disabledContentColor = theme.muted,
        ),
    ) {
        Text(text.uppercase(), fontFamily = theme.display, fontSize = 22.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val theme = LocalBuzzerTheme.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, theme.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.muted),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Menüzeile mit Pfeil. */
@Composable
fun MenuRow(label: String, onClick: () -> Unit, divider: Boolean = true) {
    val theme = LocalBuzzerTheme.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = theme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("›", color = theme.accent, fontSize = 24.sp)
        }
        if (divider) Row(Modifier.fillMaxWidth().height(1.dp).background(theme.line)) {}
    }
}

/** Kopfzeile mit Zurück-Knopf für Unterseiten. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    val theme = LocalBuzzerTheme.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) { Text("‹", color = theme.accent, fontSize = 32.sp) }
        BigTitle(title, size = 30.sp)
    }
}

@Composable
fun ThemedTextField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val theme = LocalBuzzerTheme.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = theme.accent,
            unfocusedBorderColor = theme.line,
            focusedContainerColor = theme.panel,
            unfocusedContainerColor = theme.panel,
            focusedLabelColor = theme.accent,
            unfocusedLabelColor = theme.muted,
            cursorColor = theme.accent,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = theme.text, fontFamily = theme.body),
        modifier = modifier,
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val theme = LocalBuzzerTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = theme.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = theme.accent, checkedThumbColor = theme.ground),
        )
    }
}

/** Abgesetzte Fläche (Karte). */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalBuzzerTheme.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(theme.panel).padding(14.dp), content = content)
}

/** Überschrift eines Abschnitts in Unterseiten. */
@Composable
fun SectionTitle(text: String) {
    Kicker(text, Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        content = content,
    )
}
