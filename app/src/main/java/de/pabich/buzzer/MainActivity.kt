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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import de.pabich.buzzer.ui.DiscoverScreen
import de.pabich.buzzer.ui.MasterScreen
import de.pabich.buzzer.ui.PlayerScreen
import de.pabich.buzzer.ui.StartScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BuzzerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.install(applicationContext)
        // Während des Spiels darf das Display nicht ausgehen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(viewModel)
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
        val granted = result.filterKeys { it != Manifest.permission.ACCESS_FINE_LOCATION }.values.all { it }
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
        if (missing.all { it == Manifest.permission.ACCESS_FINE_LOCATION }) {
            action()
        } else {
            pendingAction = action
            launcher.launch(missing.toTypedArray())
        }
    }

    when (vm.screen) {
        Screen.START -> StartScreen(
            vm = vm,
            onOpenGroup = { withPermissions { vm.openGroup() } },
            onSearch = { withPermissions { vm.searchGroups() } },
        )
        Screen.DISCOVER -> DiscoverScreen(vm)
        Screen.MASTER -> MasterScreen(vm)
        Screen.PLAYER -> PlayerScreen(vm)
    }
}

/**
 * Laufzeit-Berechtigungen für Nearby Connections je nach Android-Version. Der Standort wird
 * immer angefragt, weil manche Geräte ihn für die Suche auch ab Android 12 verlangen
 * (Fehler 8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION).
 */
private fun requiredPermissions(): List<String> {
    val location = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    return when {
        Build.VERSION.SDK_INT >= 33 -> location + listOf(
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
