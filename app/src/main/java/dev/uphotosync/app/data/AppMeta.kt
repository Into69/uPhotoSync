package dev.uphotosync.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * Small app-level flags that aren't backup settings: update checks, "what's new", and
 * when the free-up-space reminder last went out. Plain SharedPreferences, so a worker can
 * read them without going through DataStore.
 */
object AppMeta {
    private fun prefs(context: Context) = context.getSharedPreferences("app_meta", Context.MODE_PRIVATE)

    /** Weekly background check for a new release (on by default). */
    fun autoUpdateCheck(context: Context) = prefs(context).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdateCheck(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO_UPDATE, on) }

    /** The release version already announced in a notification, so it's only announced once. */
    fun notifiedUpdate(context: Context): String? = prefs(context).getString(KEY_NOTIFIED_UPDATE, null)

    fun setNotifiedUpdate(context: Context, version: String) = prefs(context).edit { putString(KEY_NOTIFIED_UPDATE, version) }

    /** The app version whose "what's new" has been shown (or skipped, after a fresh install). */
    fun seenVersion(context: Context): String? = prefs(context).getString(KEY_SEEN_VERSION, null)

    fun setSeenVersion(context: Context, version: String) = prefs(context).edit { putString(KEY_SEEN_VERSION, version) }

    /** When the "you can free up space" notification was last posted (epoch millis). */
    fun freeUpNotifiedAt(context: Context): Long = prefs(context).getLong(KEY_FREE_UP_AT, 0)

    fun setFreeUpNotifiedAt(context: Context, at: Long) = prefs(context).edit { putLong(KEY_FREE_UP_AT, at) }

    private const val KEY_AUTO_UPDATE = "auto_update_check"
    private const val KEY_NOTIFIED_UPDATE = "notified_update"
    private const val KEY_SEEN_VERSION = "seen_version"
    private const val KEY_FREE_UP_AT = "free_up_notified_at"
}
