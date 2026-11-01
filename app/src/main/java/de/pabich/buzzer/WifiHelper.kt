package de.pabich.buzzer

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * WLAN für Update-Suche und Download (Punkt 116): prüfen, ob es an ist, und es einschalten lassen.
 * Ab Android 10 darf eine App WLAN nicht selbst einschalten – dann öffnet sich der WLAN-Schalter von
 * Android als Einblendung; bis Android 9 schaltet die App es direkt ein.
 */
object WifiHelper {

    fun isEnabled(context: Context): Boolean = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(true)

    /** Funktionierendes Internet, wahlweise nur über WLAN. */
    fun hasInternet(context: Context, wifiOnly: Boolean = false): Boolean = runCatching {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (!wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }.getOrDefault(true)

    /** WLAN einschalten bzw. den WLAN-Schalter von Android anzeigen; `false`, wenn beides nicht geht. */
    fun enable(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val ok = runCatching { (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(true) }.getOrDefault(false)
            if (ok) return true
        }
        val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.provider.Settings.Panel.ACTION_WIFI else android.provider.Settings.ACTION_WIFI_SETTINGS
        return runCatching { app.startActivity(Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrElse {
            runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
    }
}
