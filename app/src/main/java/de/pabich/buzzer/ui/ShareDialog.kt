package de.pabich.buzzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import de.pabich.buzzer.ShareApp

/**
 * App weitergeben: per Bluetooth (oder Teilen-Auswahl) als Installationsdatei oder über einen
 * QR-Code mit dem Download-Link.
 */
@Composable
fun ShareDialog(onDismiss: () -> Unit) {
    val theme = LocalBuzzerTheme.current
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.panel,
        title = { BigTitle("App weitergeben", size = 28.sp) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("So bekommen Mitspieler die App ohne Suche:", color = theme.text, fontSize = 15.sp)
                PrimaryButton(
                    "Per Bluetooth senden",
                    { ShareApp.send(context, viaBluetooth = true) { error = it } },
                    Modifier.fillMaxWidth(),
                    height = 52.dp,
                )
                GhostButton(
                    "Teilen … (Quick Share, Messenger)",
                    { ShareApp.send(context, viaBluetooth = false) { error = it } },
                    Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color(0xFFFF9A9A), fontSize = 14.sp) }
                Text(
                    "Der Empfänger öffnet die Datei, erlaubt einmal „Installation aus unbekannten Quellen“ und tippt auf Installieren. " +
                        "Über Bluetooth dauert die Übertragung je nach Handy bis zu ein bis zwei Minuten.",
                    color = theme.muted,
                    fontSize = 13.sp,
                )
                Kicker("Oder QR-Code scannen", Modifier.padding(top = 8.dp))
                QrCode(ShareApp.DOWNLOAD_URL, Modifier.size(200.dp).align(Alignment.CenterHorizontally))
                Text("Der Link lädt immer die neueste Version.", color = theme.muted, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = theme.accent) } },
    )
}

/** QR-Code als schwarze Module auf weißer Fläche (mit Ruhezone, damit er sich gut scannen lässt). */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(modifier.background(Color.White).padding(12.dp)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
