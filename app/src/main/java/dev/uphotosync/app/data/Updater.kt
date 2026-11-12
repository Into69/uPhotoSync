package dev.uphotosync.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the app's GitHub releases, since it isn't on Google Play. Only APKs attached
 * to a release are offered, and Android checks that an update is signed with the same key as
 * the installed app, so a tampered file is refused at install time.
 */
class Updater(private val context: Context) {

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
    )

    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The newest published release, drafts left out (pre-releases count: every release so far is one). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = open(RELEASES_URL).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw IOException("GitHub is limiting requests. Try again in a while.")
                else -> throw IOException("GitHub answered ${conn.responseCode}")
            }
            val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            (0 until releases.length()).asSequence()
                .map { releases.getJSONObject(it) }
                .filter { !it.optBoolean("draft") }
                .mapNotNull { r ->
                    val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                        ?: return@mapNotNull null
                    Release(
                        version = r.optString("tag_name").removePrefix("v"),
                        notes = r.optString("body").trim(),
                        apkUrl = apk.getString("browser_download_url"),
                        apkSize = apk.optLong("size"),
                        pageUrl = r.optString("html_url"),
                    )
                }
                .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(release: Release) = compareVersions(release.version, currentVersion) > 0

    /** Downloads the release's APK into the cache, replacing any earlier download. */
    suspend fun download(release: Release, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "uPhotoSync-${release.version}.apk")
            val part = File(dir, target.name + ".part")
            val conn = open(release.apkUrl)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Download failed (${conn.responseCode})")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                var done = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (release.apkSize > 0 && done != release.apkSize) throw IOException("The download was incomplete. Try again.")
                if (!part.renameTo(target)) throw IOException("Couldn't save the update")
                target
            } catch (e: Throwable) {
                part.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }

    /** Android asks once whether this app may install apps ("Install unknown apps"). */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent() = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    /** Opens Android's installer for [apk]; it shows the update prompt. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true // release downloads redirect to GitHub's file host
        setRequestProperty("User-Agent", "uPhotoSync/$currentVersion")
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Into69/uPhotoSync/releases?per_page=20"
        private const val UPDATE_DIR = "updates"

        /** Compares dotted versions number by number ("0.1.10" is newer than "0.1.9"). */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
