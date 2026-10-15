package de.pabich.buzzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import de.pabich.buzzer.ui.AboutScreen
import de.pabich.buzzer.ui.AppSettingsScreen
import de.pabich.buzzer.ui.BuzzerThemeProvider
import de.pabich.buzzer.ui.DiscoverScreen
import de.pabich.buzzer.ui.HelpScreen
import de.pabich.buzzer.ui.LocationDialog
import de.pabich.buzzer.ui.TokenGuideDialog
import de.pabich.buzzer.ui.Themes
import de.pabich.buzzer.ui.MasterScreen
import de.pabich.buzzer.ui.PlayerScreen
import de.pabich.buzzer.ui.StartScreen
import de.pabich.buzzer.ui.TransferBanner
import de.pabich.buzzer.ui.UpdateDialog
import de.pabich.buzzer.ui.UpdateScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BuzzerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Die App zeichnet hinter die Systemleisten; die nutzbare Fläche bestimmt unten EIN Abstand.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Diagnostics.install(applicationContext)
        // Während des Spiels darf das Display nicht ausgehen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val theme = Themes.byId(viewModel.designId)
            BuzzerThemeProvider(theme) {
                // Hintergrund bis unter die Leisten, Inhalt nur in der nutzbaren Fläche: Statusleiste,
                // Navigationsleiste, Taskleiste, schwebende Knöpfe und Kameraaussparung bleiben frei.
                Box(Modifier.fillMaxSize().background(theme.ground)) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.tappableElement))) {
                        App(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun App(vm: BuzzerViewModel) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Ungefährer Standort genügt; den genauen darf der Nutzer ablehnen.
        val granted = result.filterKeys { it !in OPTIONAL_PERMISSIONS }.values.all { it }
        Diagnostics.log("Berechtigungen: " + result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        if (granted) {
            pendingAction?.invoke()
        } else {
            vm.status = "Ohne die Berechtigungen für Bluetooth, Geräte in der Nähe und (ungefähren) Standort funktioniert das Spiel nicht."
        }
        pendingAction = null
    }

    fun withPermissions(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.all { it in OPTIONAL_PERMISSIONS }) {
            action()
        } else {
            pendingAction = action
            launcher.launch(missing.toTypedArray())
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.START -> StartScreen(
                vm = vm,
                onOpenGroup = { withPermissions { vm.openGroup() } },
                onSearch = { withPermissions { vm.searchGroups() } },
            )
            Screen.DISCOVER -> DiscoverScreen(vm)
            Screen.MASTER -> MasterScreen(vm)
            Screen.PLAYER -> PlayerScreen(vm)
            Screen.UPDATE -> UpdateScreen(vm)
            Screen.SETTINGS -> AppSettingsScreen(vm)
            Screen.HELP -> HelpScreen(vm)
            Screen.ABOUT -> AboutScreen(vm)
        }
        // Übertragung einer App-Version läuft neben dem Spiel – unten eingeblendet.
        if (vm.screen != Screen.UPDATE) TransferBanner(vm, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        // Spielstart-Animation (Rennszene) auf allen Geräten, Splash-Screen beim App-Start.
        vm.intro?.let { de.pabich.buzzer.ui.RaceIntro(it, vm.introSeq, onDone = vm::introDone) }
        vm.splash?.let { de.pabich.buzzer.ui.SplashScreen(it, onDone = { skipped -> vm.splashDone(skipped) }) }
    }
    // Neuere Version empfangen oder geladen: installieren?
    UpdateDialog(vm)
    TokenGuideDialog(vm)
    LocationDialog(vm)
}

/**
 * Laufzeit-Berechtigungen für Nearby Connections je nach Android-Version. Der Standort wird
 * immer angefragt, weil manche Geräte ihn für die Suche auch ab Android 12 verlangen
 * (Fehler 8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION).
 */
/** Ohne diese geht es auch: genauer Standort und die Benachrichtigung des Hintergrund-Dienstes. */
private val OPTIONAL_PERMISSIONS = setOf(Manifest.permission.ACCESS_FINE_LOCATION, "android.permission.POST_NOTIFICATIONS")

private fun requiredPermissions(): List<String> {
    val location = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    return when {
        Build.VERSION.SDK_INT >= 33 -> location + listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= 31 -> location + listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        else -> location
    }
}
