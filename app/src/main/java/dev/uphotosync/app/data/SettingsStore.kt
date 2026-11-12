package dev.uphotosync.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class NasConfig(
    val host: String,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
    /** Folder inside the share that backups go to, chosen by the user; empty means the share root. */
    val destination: String = "",
    /** Require SMB3 encryption, so other devices on the network can't read the traffic. */
    val encrypt: Boolean = false,
) {
    val isComplete get() = host.isNotBlank() && share.isNotBlank() && username.isNotBlank()

    /**
     * Accepts what people actually paste: a host like "\\192.168.1.20" or "smb://nas/",
     * and a share field holding a whole path ("\\nas\Photos\Phone", "Photos/Phone").
     * The share field is reduced to just the share name; any folder part becomes the
     * destination (unless one is already set). Wrong share names are the usual cause
     * of STATUS_BAD_NETWORK_NAME.
     */
    fun normalized(): NasConfig {
        var newHost = stripScheme(host).let(NasPath::clean).substringBefore('\\')
        val rawShare = share.trim()
        val isUnc = rawShare.startsWith("\\\\") || rawShare.startsWith("//") || rawShare.startsWith("smb:", true)
        var parts = NasPath.clean(stripScheme(rawShare)).split('\\').filter { it.isNotEmpty() }
        if (parts.size > 1 && (isUnc || parts[0].equals(newHost, ignoreCase = true))) {
            if (newHost.isEmpty() || isUnc) newHost = parts[0]
            parts = parts.drop(1)
        }
        val folder = parts.drop(1).joinToString("\\")
        return copy(
            host = newHost,
            share = parts.firstOrNull().orEmpty(),
            destination = if (destination.isBlank() && folder.isNotEmpty()) folder else NasPath.clean(destination),
        )
    }

    private fun stripScheme(s: String) = s.trim().replace(Regex("^smb:", RegexOption.IGNORE_CASE), "")
}

enum class ViewMode { Thumbnails, Details }

/** Sort order for the Device and NAS tabs (NAS folders always come first). */
enum class SortOrder(val label: String) {
    DateNewest("Date: newest first"),
    DateOldest("Date: oldest first"),
    NameAZ("Name: A to Z"),
    NameZA("Name: Z to A"),
    SizeLargest("Size: largest first"),
    SizeSmallest("Size: smallest first");

    val byDate get() = this == DateNewest || this == DateOldest
}

data class BackupPrefs(
    val autoBackup: Boolean = false,
    val unmeteredOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    /** MediaStore bucket ids to auto back up; empty means every album. */
    val albums: Set<String> = emptySet(),
    /** Daily backup at a set time, on [scheduleDays]. */
    val scheduled: Boolean = false,
    /** Minutes after midnight, local time. */
    val scheduleMinutes: Int = 2 * 60,
    /** ISO days of week (1 = Monday ... 7 = Sunday). */
    val scheduleDays: Set<Int> = (1..7).toSet(),
    /** A scheduled run that can't start within this many hours of the set time is skipped for that day. */
    val scheduleWindowHours: Int = 1,
    /** With [unmeteredOnly]: only these Wi-Fi networks (SSIDs); empty means any Wi-Fi. */
    val wifiSsids: Set<String> = emptySet(),
    /** Phone deletes go to Android's trash (recoverable for 30 days) where supported (Android 11+). */
    val phoneTrash: Boolean = true,
    /** NAS deletes go to a .recycle folder in the share, emptied after 30 days. */
    val nasRecycle: Boolean = true,
    /** Files uploaded at the same time (1-4); more helps with many small photos. */
    val parallelUploads: Int = 3,
) {
    /** True when automatic backups are limited to specific Wi-Fi names. */
    val ssidRestricted get() = unmeteredOnly && wifiSsids.isNotEmpty()

    /** The Wi-Fi/charging/album options matter when either kind of automatic backup is on. */
    val anyAutomatic get() = autoBackup || scheduled
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("host")
        val share = stringPreferencesKey("share")
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password_enc")
        val domain = stringPreferencesKey("domain")
        val destination = stringPreferencesKey("destination")
        val encrypt = booleanPreferencesKey("encrypt")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val unmeteredOnly = booleanPreferencesKey("unmetered_only")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val albums = stringSetPreferencesKey("albums")
        val scheduled = booleanPreferencesKey("scheduled")
        val scheduleMinutes = intPreferencesKey("schedule_minutes")
        val scheduleDays = stringSetPreferencesKey("schedule_days")
        val scheduleWindowHours = intPreferencesKey("schedule_window_hours")
        val wifiSsids = stringSetPreferencesKey("wifi_ssids")
        val phoneTrash = booleanPreferencesKey("phone_trash")
        val nasRecycle = booleanPreferencesKey("nas_recycle")
        val parallelUploads = intPreferencesKey("parallel_uploads")
    }

    /** Thumbnails or details for a browser screen, remembered per [screen] ("device", "nas"). */
    fun viewMode(screen: String): Flow<ViewMode> = context.dataStore.data.map { p ->
        p[stringPreferencesKey("view_$screen")]
            ?.let { name -> ViewMode.entries.firstOrNull { it.name == name } }
            ?: ViewMode.Thumbnails
    }

    suspend fun setViewMode(screen: String, mode: ViewMode) {
        context.dataStore.edit { it[stringPreferencesKey("view_$screen")] = mode.name }
    }

    /** Sort order for a browser screen, remembered per [screen] ("device", "nas"). */
    fun sortOrder(screen: String, default: SortOrder): Flow<SortOrder> = context.dataStore.data.map { p ->
        p[stringPreferencesKey("sort_$screen")]?.let { name -> SortOrder.entries.firstOrNull { it.name == name } } ?: default
    }

    suspend fun setSortOrder(screen: String, order: SortOrder) {
        context.dataStore.edit { it[stringPreferencesKey("sort_$screen")] = order.name }
    }

    /** Whatever has been typed in so far, complete or not (for the settings form). */
    val draft: Flow<NasConfig> = context.dataStore.data.map { p ->
        NasConfig(
            host = p[Keys.host].orEmpty(),
            share = p[Keys.share].orEmpty(),
            username = p[Keys.username].orEmpty(),
            password = p[Keys.password]?.let(SecretBox::decrypt).orEmpty(),
            domain = p[Keys.domain].orEmpty(),
            destination = p[Keys.destination].orEmpty(),
            encrypt = p[Keys.encrypt] ?: false,
        )
    }

    /** The saved NAS connection, or null until one has been set up. */
    val config: Flow<NasConfig?> = draft.map { it.takeIf { c -> c.isComplete } }

    val prefs: Flow<BackupPrefs> = context.dataStore.data.map { p ->
        BackupPrefs(
            autoBackup = p[Keys.autoBackup] ?: false,
            unmeteredOnly = p[Keys.unmeteredOnly] ?: true,
            chargingOnly = p[Keys.chargingOnly] ?: false,
            albums = p[Keys.albums] ?: emptySet(),
            scheduled = p[Keys.scheduled] ?: false,
            scheduleMinutes = p[Keys.scheduleMinutes] ?: 2 * 60,
            scheduleDays = p[Keys.scheduleDays]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: (1..7).toSet(),
            scheduleWindowHours = (p[Keys.scheduleWindowHours] ?: 1).coerceIn(1, 12),
            wifiSsids = p[Keys.wifiSsids] ?: emptySet(),
            phoneTrash = p[Keys.phoneTrash] ?: true,
            nasRecycle = p[Keys.nasRecycle] ?: true,
            parallelUploads = (p[Keys.parallelUploads] ?: 3).coerceIn(1, 4),
        )
    }

    suspend fun saveConfig(c: NasConfig) {
        context.dataStore.edit { p ->
            p[Keys.host] = c.host.trim()
            p[Keys.share] = NasPath.clean(c.share)
            p[Keys.username] = c.username.trim()
            p[Keys.password] = SecretBox.encrypt(c.password)
            p[Keys.domain] = c.domain.trim()
            p[Keys.destination] = NasPath.clean(c.destination)
            p[Keys.encrypt] = c.encrypt
        }
    }

    suspend fun savePrefs(b: BackupPrefs) {
        context.dataStore.edit { p ->
            p[Keys.autoBackup] = b.autoBackup
            p[Keys.unmeteredOnly] = b.unmeteredOnly
            p[Keys.chargingOnly] = b.chargingOnly
            p[Keys.albums] = b.albums
            p[Keys.scheduled] = b.scheduled
            p[Keys.scheduleMinutes] = b.scheduleMinutes
            p[Keys.scheduleDays] = b.scheduleDays.mapTo(HashSet()) { it.toString() }
            p[Keys.scheduleWindowHours] = b.scheduleWindowHours.coerceIn(1, 12)
            p[Keys.wifiSsids] = b.wifiSsids
            p[Keys.phoneTrash] = b.phoneTrash
            p[Keys.nasRecycle] = b.nasRecycle
            p[Keys.parallelUploads] = b.parallelUploads.coerceIn(1, 4)
        }
    }
}
