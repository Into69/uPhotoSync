package dev.unifiphotosync.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class MediaAccess {
    Full,

    /** Android 14+ "Select photos": only the photos the user picked are visible. */
    Partial,
    None,
}

/** Everything the app needs to back up in the background, and how to fix each gap. */
object AppPermissions {

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Media permissions to request; asking for all of them offers "Allow all" on Android 14+. */
    fun mediaRequest(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        // Keeps GPS data in backed-up photos.
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    /** Media permissions plus notifications, for the first-launch prompt. */
    fun firstLaunchRequest(): Array<String> =
        mediaRequest() + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    fun mediaAccess(context: Context): MediaAccess = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            when {
                granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                    granted(context, Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.Full
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
                // Images only (videos refused) still lets the app work, just not fully.
                granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.Partial
                else -> MediaAccess.None
            }
        granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.Full
        else -> MediaAccess.None
    }

    fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True if exempt from battery optimization, so WorkManager isn't deferred by Doze. */
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** True if the user restricted this app's background activity in system settings. */
    fun backgroundRestricted(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    /** Precise location: Android's gate for reading the Wi-Fi name (SSID). */
    fun preciseLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** "Allow all the time": needed for background backups to read the Wi-Fi name. */
    fun backgroundLocation(context: Context) = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** The system location switch; with it off, Android hides the Wi-Fi name. */
    fun locationEnabled(context: Context) =
        context.getSystemService(LocationManager::class.java).isLocationEnabled

    /** Everything needed to read the Wi-Fi name from a background backup. */
    fun wifiNameReadable(context: Context) =
        preciseLocation(context) && backgroundLocation(context) && locationEnabled(context)

    /** [needsWifiName]: automatic backups are limited to named Wi-Fi networks. */
    fun readyForBackground(context: Context, needsWifiName: Boolean = false) =
        mediaAccess(context) == MediaAccess.Full &&
            notificationsAllowed(context) &&
            batteryUnrestricted(context) &&
            !backgroundRestricted(context) &&
            (!needsWifiName || wifiNameReadable(context))

    fun locationSettingsIntent() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    // A sideloaded backup app is the intended use of this exemption.
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun appSettingsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
