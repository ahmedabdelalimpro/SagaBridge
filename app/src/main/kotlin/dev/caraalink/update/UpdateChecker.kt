package dev.caraalink.update

import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.caraalink.BuildConfig
import dev.caraalink.MainActivity
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub's latest tagged Release for something newer than this install,
 * downloads its APK asset and hands it to the system installer.
 */
object UpdateChecker {

    private const val OWNER = "ahmedabdelalimpro"
    private const val REPO = "SagaBridge"
    private const val CHANNEL_ID = "app_updates"
    // Distinct from UsbLinkForegroundService.OPEN_TO_RECONNECT_ID (4) so notifications never overwrite each other.
    private const val NOTIFICATION_ID = 5

    /** [apkUrl] is null if the release has no `.apk` asset attached (nothing to auto-download). */
    private data class UpdateInfo(val title: String, val htmlUrl: String, val apkUrl: String?)

    private fun logger(context: Context) = DiagnosticLogger(context, "update-checker")

    /** Called from MainActivity.onCreate() -- silent unless something newer actually exists. */
    fun checkIfDue(context: Context) {
        if (!AppSettings.updateAutoCheck(context)) return
        checkNow(context, manual = false)
    }

    /** [manual] controls whether a "you're up to date" / error Toast shows; a found update always prompts. */
    fun checkNow(context: Context, manual: Boolean) {
        Thread({
            val result = runCatching { checkLatestRelease() }
            val outcome = result.getOrNull()
            logger(context).log("update check: ${result.exceptionOrNull() ?: outcome ?: "up to date"}")
            Handler(Looper.getMainLooper()).post {
                when {
                    result.isFailure -> if (manual) toast(context, "Update check failed: ${result.exceptionOrNull()?.message}")
                    outcome != null -> promptUpdate(context, outcome)
                    manual -> toast(context, "Already on the latest release")
                }
            }
        }, "UpdateChecker").start()
    }

    /**
     * Returns info for a newer tagged release, else null. `/releases/latest`
     * is a hard 404 (not an empty list) when the repo has never had a single
     * Release published. That's "nothing to compare against", not a
     * failure -- treated as up to date, no error surfaced.
     */
    private fun checkLatestRelease(): UpdateInfo? {
        val body = get("https://api.github.com/repos/$OWNER/$REPO/releases/latest") ?: return null
        val json = JSONObject(body)
        val tag = json.getString("tag_name").removePrefix("v")
        if (tag == BuildConfig.VERSION_NAME) return null
        return UpdateInfo("Update available: v$tag", json.getString("html_url"), apkAssetUrl(json))
    }

    /** First `.apk` asset's direct download URL, or null if the release has none attached. */
    private fun apkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) return asset.getString("browser_download_url")
        }
        return null
    }

    /** null on a 404 (nothing there to compare against); throws on any other non-2xx. */
    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            when (conn.responseCode) {
                in 200..299 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                404 -> null
                else -> error("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()

    private fun promptUpdate(context: Context, info: UpdateInfo) {
        notify(context, info)
        val activity = context as? android.app.Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(info.title)
            .setMessage(
                if (info.apkUrl != null) "Download and install now?" else "No APK attached to this release -- opening its GitHub page instead.",
            )
            .setPositiveButton("Download") { _, _ ->
                if (info.apkUrl != null) downloadAndInstall(context, info.apkUrl, info.title) else openPage(context, info.htmlUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openPage(context: Context, url: String) =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun downloadAndInstall(context: Context, apkUrl: String, title: String) {
        val appContext = context.applicationContext
        val dir = File(appContext.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        dest.delete()
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(title)
            .setDescription("Downloading SagaBridge update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(dest))
        val manager = appContext.getSystemService(DownloadManager::class.java)
        val id = manager.enqueue(request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { appContext.unregisterReceiver(this) }
                if (!dest.exists()) {
                    toast(appContext, "Update download failed")
                    return
                }
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", dest)
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                appContext.startActivity(install)
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        logger(appContext).log("downloading update APK from $apkUrl")
    }

    private fun notify(context: Context, info: UpdateInfo) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(dev.caraalink.R.drawable.ic_stat_bridge)
            .setContentTitle(info.title)
            .setSubText(appContext.getString(dev.caraalink.R.string.made_by))
            .setContentText("Tap to download and install")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}