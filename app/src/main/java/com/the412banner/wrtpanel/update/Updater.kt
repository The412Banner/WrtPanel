package com.the412banner.wrtpanel.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.the412banner.wrtpanel.BuildConfig
import com.the412banner.wrtpanel.MainActivity
import com.the412banner.wrtpanel.R
import com.the412banner.wrtpanel.core.AppSettings
import com.the412banner.wrtpanel.core.JsonCodec
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class AppRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val prerelease: Boolean,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val htmlUrl: String,
    val published: String,
)

/** Checks the repo's GitHub releases for a newer WrtPanel and installs it with PackageInstaller. */
class Updater(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Newest release (respecting [includePre]) or null. Drafts are never returned by the API to anonymous users. */
    suspend fun latest(includePre: Boolean): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=15")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "WrtPanel/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub returned HTTP ${r.code}")
            val arr = JsonCodec.parseToJsonElement(r.body?.string() ?: "[]").arr ?: return@withContext null
            arr.asSequence()
                .filter { it["draft"].bool != true }
                .filter { includePre || it["prerelease"].bool != true }
                .mapNotNull { rel ->
                    val apk = rel["assets"].arr?.firstOrNull { it["name"].str?.endsWith(".apk", true) == true } ?: return@mapNotNull null
                    AppRelease(
                        tag = rel["tag_name"].str ?: return@mapNotNull null,
                        name = rel["name"].str ?: rel["tag_name"].str ?: "",
                        notes = rel["body"].str ?: "",
                        prerelease = rel["prerelease"].bool == true,
                        apkUrl = apk["browser_download_url"].str ?: return@mapNotNull null,
                        apkName = apk["name"].str ?: "WrtPanel.apk",
                        apkSize = apk["size"].long ?: 0L,
                        htmlUrl = rel["html_url"].str ?: "",
                        published = rel["published_at"].str ?: "",
                    )
                }
                .sortedWith { a, b -> compareVersions(b.tag, a.tag) }
                .firstOrNull()
        }
    }

    fun isNewer(release: AppRelease): Boolean = compareVersions(release.tag, currentVersion) > 0

    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", "WrtPanel/${BuildConfig.VERSION_NAME}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download failed (HTTP ${r.code})")
            val body = r.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        out
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Hands the APK to PackageInstaller; the system asks the user to confirm. */
    fun install(apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    companion object {
        const val CHANNEL = "updates"
        const val WORK = "wrtpanel-update-check"

        /** Compares "v1.2.3", "1.2.3-pre4" style versions; a pre-release sorts before its final. */
        fun compareVersions(a: String, b: String): Int {
            fun parse(v: String): Pair<List<Int>, Int> {
                val s = v.trim().removePrefix("v").removePrefix("V")
                val main = s.substringBefore('-')
                val nums = main.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                val pre = if (s.contains('-')) Regex("(\\d+)$").find(s.substringAfter('-'))?.value?.toIntOrNull() ?: 0 else Int.MAX_VALUE
                return nums to pre
            }
            val (an, ap) = parse(a); val (bn, bp) = parse(b)
            for (i in 0 until maxOf(an.size, bn.size)) {
                val x = an.getOrElse(i) { 0 }; val y = bn.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return ap.compareTo(bp)
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
                }
            }
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun notify(context: Context, release: AppRelease) {
            ensureChannel(context)
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
            }
            val pi = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_update)
                .setContentTitle("WrtPanel ${release.tag.removePrefix("v")} is available")
                .setContentText("Tap to see what's new and update.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(42, n) }
        }
    }
}

/** Daily background check; notifies once per new release tag. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AppSettings(applicationContext)
        if (!settings.autoUpdateCheck) return Result.success()
        return try {
            val u = Updater(applicationContext)
            val rel = u.latest(settings.includePrereleases)
            settings.lastUpdateCheck = System.currentTimeMillis()
            if (rel != null && u.isNewer(rel) && settings.notifiedTag != rel.tag) {
                Updater.notify(applicationContext, rel)
                settings.notifiedTag = rel.tag
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
