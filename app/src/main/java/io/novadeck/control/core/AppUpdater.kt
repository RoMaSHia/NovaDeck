package io.novadeck.control.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import io.novadeck.control.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    DOWNLOADING,
    PERMISSION_REQUIRED,
    READY_TO_INSTALL,
    ERROR
}

/**
 * In-place GitHub OTA self-updater.
 * Downloads signed APK into FileProvider-backed cacheDir and invokes Android's
 * package installer directly over the existing installation (no uninstall required).
 */
object AppUpdater {
    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/RoMaSHia/NovaDeck/main/version.json"
    private const val GITHUB_LATEST_API =
        "https://api.github.com/repos/RoMaSHia/NovaDeck/releases/latest"
    private const val FALLBACK_APK_URL =
        "https://github.com/RoMaSHia/NovaDeck/raw/main/dist/NovaDeck-release.apk"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    var status by mutableStateOf(UpdateStatus.IDLE)
        private set
    var downloadProgress by mutableIntStateOf(0)
        private set
    var remoteVersionName by mutableStateOf(BuildConfig.VERSION_NAME.removeSuffix("-debug"))
        private set
    var remoteVersionCode by mutableIntStateOf(BuildConfig.VERSION_CODE)
        private set
    var downloadUrl by mutableStateOf(FALLBACK_APK_URL)
        private set
    var changelog by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf("")
        private set
    var downloadedApkFile by mutableStateOf<File?>(null)
        private set

    fun checkForUpdate(context: Context) {
        if (status == UpdateStatus.CHECKING || status == UpdateStatus.DOWNLOADING) return
        status = UpdateStatus.CHECKING
        errorMessage = ""

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                var parsedCode = BuildConfig.VERSION_CODE
                var parsedName = BuildConfig.VERSION_NAME.removeSuffix("-debug")
                var parsedUrl = FALLBACK_APK_URL
                var parsedNotes = ""

                val rawJson = fetchText(VERSION_JSON_URL)
                if (rawJson != null) {
                    val obj = JSONObject(rawJson)
                    parsedCode = obj.optInt("versionCode", BuildConfig.VERSION_CODE)
                    parsedName = obj.optString("versionName", parsedName)
                    val releaseUrl = obj.optString("releaseUrl", "")
                    val apkUrl = obj.optString("apkUrl", FALLBACK_APK_URL)
                    parsedUrl = apkUrl.ifBlank { releaseUrl.ifBlank { FALLBACK_APK_URL } }
                    parsedNotes = obj.optString("changelog", "")
                } else {
                    val relJson = fetchText(GITHUB_LATEST_API)
                        ?: throw IllegalStateException("GitHub unreachable")
                    val obj = JSONObject(relJson)
                    parsedName = obj.optString("tag_name", parsedName).removePrefix("v")
                    parsedNotes = obj.optString("body", "")
                    val assets = obj.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                parsedUrl = asset.optString("browser_download_url", parsedUrl)
                                if (name.contains("release", ignoreCase = true)) break
                            }
                        }
                    }
                }

                val currentCleanName = BuildConfig.VERSION_NAME.removeSuffix("-debug")
                val hasNewer = parsedCode > BuildConfig.VERSION_CODE ||
                    (parsedCode == BuildConfig.VERSION_CODE &&
                        parsedName.isNotBlank() &&
                        parsedName != currentCleanName &&
                        compareSemver(parsedName, currentCleanName) > 0)

                withContext(Dispatchers.Main) {
                    remoteVersionCode = parsedCode
                    remoteVersionName = parsedName
                    downloadUrl = parsedUrl
                    changelog = parsedNotes
                    status = if (hasNewer) UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: "Network error"
                    status = UpdateStatus.ERROR
                }
            }
        }
    }

    fun downloadAndInstall(context: Context) {
        if (status == UpdateStatus.DOWNLOADING) return
        val appContext = context.applicationContext
        status = UpdateStatus.DOWNLOADING
        downloadProgress = 0
        errorMessage = ""

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val partFile = File(updatesDir, "NovaDeck-update.apk.part")
                val finalFile = File(updatesDir, "NovaDeck-release.apk")
                if (partFile.exists()) partFile.delete()

                val conn = openFollowingRedirects(downloadUrl)
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                var lastPct = -1

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main.immediate) {
                                        downloadProgress = pct
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }
                conn.disconnect()

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                withContext(Dispatchers.Main) {
                    downloadProgress = 100
                    downloadedApkFile = finalFile
                    installApkInPlace(context, finalFile)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: "Download failed"
                    status = UpdateStatus.ERROR
                }
            }
        }
    }

    fun installApkInPlace(context: Context, apkFile: File? = downloadedApkFile) {
        val file = apkFile ?: return
        if (!file.exists()) {
            status = UpdateStatus.ERROR
            errorMessage = "APK file missing"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            status = UpdateStatus.PERMISSION_REQUIRED
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        status = UpdateStatus.READY_TO_INSTALL
        context.startActivity(installIntent)
    }

    private fun fetchText(urlStr: String): String? {
        return try {
            val conn = openFollowingRedirects(urlStr)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            text
        } catch (_: Throwable) {
            null
        }
    }

    private fun openFollowingRedirects(urlStr: String): HttpURLConnection {
        var current = urlStr
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NovaDeck/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Empty redirect")
                current = URL(URL(current), loc).toString()
            } else {
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code")
                }
                return conn
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
