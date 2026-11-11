package dev.unifiphotosync.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
) {
    val isComplete get() = host.isNotBlank() && share.isNotBlank() && username.isNotBlank()
}

enum class ViewMode { Thumbnails, Details }

data class BackupPrefs(
    val autoBackup: Boolean = false,
    val unmeteredOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    /** MediaStore bucket ids to auto back up; empty means every album. */
    val albums: Set<String> = emptySet(),
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("host")
        val share = stringPreferencesKey("share")
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password_enc")
        val domain = stringPreferencesKey("domain")
        val destination = stringPreferencesKey("destination")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val unmeteredOnly = booleanPreferencesKey("unmetered_only")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val albums = stringSetPreferencesKey("albums")
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

    /** Whatever has been typed in so far, complete or not (for the settings form). */
    val draft: Flow<NasConfig> = context.dataStore.data.map { p ->
        NasConfig(
            host = p[Keys.host].orEmpty(),
            share = p[Keys.share].orEmpty(),
            username = p[Keys.username].orEmpty(),
            password = p[Keys.password]?.let(SecretBox::decrypt).orEmpty(),
            domain = p[Keys.domain].orEmpty(),
            destination = p[Keys.destination].orEmpty(),
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
        }
    }

    suspend fun savePrefs(b: BackupPrefs) {
        context.dataStore.edit { p ->
            p[Keys.autoBackup] = b.autoBackup
            p[Keys.unmeteredOnly] = b.unmeteredOnly
            p[Keys.chargingOnly] = b.chargingOnly
            p[Keys.albums] = b.albums
        }
    }
}
