package dev.uphotosync.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** The Wi-Fi network the phone is on, for "only back up on these networks". */
object WifiNetwork {

    /**
     * Name of the current Wi-Fi network, or null if the phone isn't on Wi-Fi or Android
     * hides the name (no location permission, or location turned off).
     */
    fun currentSsid(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        // Deprecated in Android 12 but still supported, and unlike the newer callback API it
        // works synchronously from a background job.
        @Suppress("DEPRECATION")
        val raw = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid
        return normalize(raw)
    }

    /** Strips Android's quotes; Android reports a hidden name as "<unknown ssid>". */
    fun normalize(raw: String?): String? =
        raw?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }

    /** Whether [ssid] is one of [allowed]. Names are compared exactly, as Wi-Fi does. */
    fun matches(ssid: String?, allowed: Set<String>): Boolean = ssid != null && ssid in allowed

    private const val UNKNOWN_SSID = "<unknown ssid>"
}
