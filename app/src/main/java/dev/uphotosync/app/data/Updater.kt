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
            val code = conn.responseCode
            val rateLimited = code == 429 ||
                (code == HttpURLConnection.HTTP_FORBIDDEN && conn.getHeaderField("X-RateLimit-Remaining") == "0")
            when {
                code == HttpURLConnection.HTTP_OK -> Unit
                rateLimited -> throw IOException("GitHub is limiting requests. Try again in a while.")
                code == HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("The app's GitHub releases weren't found")
                else -> throw IOException("GitHub refused the request ($code)")
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
                    // An asset still uploading (or otherwise incomplete) has no link yet: skip that release.
                    val url = apk.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        ?: return@mapNotNull null
                    val version = r.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    Release(
                        version = version,
                        notes = r.optString("body").trim(),
                        apkUrl = url,
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
            val target = File(dir, "uPhotoSync-${release.version}.apk")
            // Clear out leftovers, but only versions that are already installed (or older) and
            // unfinished downloads. Android's installer may still be reading a newer APK
            // from an earlier prompt, and deleting it under it makes the install fail.
            dir.listFiles()?.forEach { f ->
                val v = f.name.removePrefix("uPhotoSync-").removeSuffix(".apk")
                val stale = f.name.endsWith(".part") ||
                    (f.name.endsWith(".apk") && compareVersions(v, currentVersion) <= 0)
                if (stale) f.delete()
            }
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

        /**
         * Compares dotted versions number by number ("0.1.10" is newer than "0.1.9"). A suffix
         * after "-" marks a pre-release, which comes before the plain version
         * ("0.2.0-beta" < "0.2.0"), and suffixes compare with their numbers as numbers
         * ("rc2" < "rc10").
         */
        fun compareVersions(a: String, b: String): Int {
            val (aMain, aPre) = a.trim().split('-', limit = 2).let { it[0] to it.getOrNull(1) }
            val (bMain, bPre) = b.trim().split('-', limit = 2).let { it[0] to it.getOrNull(1) }
            val x = aMain.split('.').map { it.toIntOrNull() ?: 0 }
            val y = bMain.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return when {
                aPre == null && bPre == null -> 0
                aPre == null -> 1
                bPre == null -> -1
                else -> naturalCompare(aPre.lowercase(), bPre.lowercase())
            }
        }

        private val CHUNK = Regex("\\d+|\\D+")

        /** Text compared piece by piece, with runs of digits compared as numbers. */
        private fun naturalCompare(a: String, b: String): Int {
            val x = CHUNK.findAll(a).map { it.value }.toList()
            val y = CHUNK.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    val pn = p.trimStart('0')
                    val qn = q.trimStart('0')
                    if (pn.length != qn.length) pn.length.compareTo(qn.length) else pn.compareTo(qn)
                } else {
                    p.compareTo(q)
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}
