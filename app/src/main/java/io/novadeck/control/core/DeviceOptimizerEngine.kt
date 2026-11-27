package io.novadeck.control.core

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import io.novadeck.control.R
import java.io.File

enum class FindingSeverity {
    CRITICAL,
    WARN,
    OPTIMAL
}

enum class OptimizerAction {
    NONE,
    CLEAN_RAM_CACHE,
    OPEN_STORAGE_OPT,
    OPEN_AUTORUN,
    OPEN_BLOAT,
    OPEN_TELEMETRY_AUDIT,
    OPEN_LOCATION_SCAN_SETTINGS,
    OPEN_BATTERY_MONITOR
}

data class DiagnosticFinding(
    val id: String,
    val severity: FindingSeverity,
    val title: String,
    val whatIsWrong: String,
    val whatToDo: String,
    val actionLabel: String,
    val action: OptimizerAction
)

data class TelemetryCheckReport(
    val wifiScanAlwaysOn: Boolean,
    val bleScanAlwaysOn: Boolean,
    val diagnosticsUploadOn: Boolean,
    val adbEnabled: Boolean,
    val wirelessAdbEnabled: Boolean,
    val notificationReaders: List<String>,
    val accessibilityReaders: List<String>,
    val adIdTrackerApps: List<Pair<String, String>>,      // label, package
    val bgLocationApps: List<Pair<String, String>>,       // label, package
    val oemAnalyticsApps: List<Pair<String, String>>      // label, package
)

data class AppRiskProfile(
    val packageName: String,
    val isAutorun: Boolean,
    val isRunning: Boolean,
    val hasAdId: Boolean,
    val isOemBloat: Boolean,
    val daysSinceUsed: Int, // -1 if unknown without UsageStats
    val unnecessaryReasons: List<String>
)

data class FullDeviceDiagnosis(
    val optimizationScore: Int,
    val reclaimableCacheBytes: Long,
    val autorunCount: Int,
    val runningAutorunCount: Int,
    val bloatOrUnusedCount: Int,
    val telemetryFlagsActive: Int,
    val adTrackerCount: Int,
    val findings: List<DiagnosticFinding>
)

data class OptimizationExecutionResult(
    val ramFreedBytes: Long,
    val storageFreedBytes: Long,
    val killedPackagesCount: Int
)

object DeviceOptimizerEngine {

    private val oemBloatPackages = setOf(
        "com.miui.analytics",
        "com.miui.msa.global",
        "com.miui.daemon",
        "com.miui.hybrid",
        "com.miui.bugreport",
        "com.facebook.system",
        "com.facebook.appmanager",
        "com.facebook.services",
        "com.ironsource.appcloud.oobe",
        "com.aura.oobe",
        "com.digitalturbine.ignite",
        "com.heytap.mcs",
        "com.heytap.market",
        "com.coloros.athena",
        "com.samsung.android.app.spage",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.game.gos",
        "com.samsung.android.rubin.app",
        "com.google.android.feedback",
        "com.google.android.partnersetup",
        "com.huawei.himovie",
        "com.huawei.appmarket"
    )

    private val oemTelemetryPackages = setOf(
        "com.miui.analytics",
        "com.miui.msa.global",
        "com.miui.daemon",
        "com.miui.bugreport",
        "com.google.android.feedback",
        "com.samsung.android.rubin.app",
        "com.samsung.android.diagmonagent",
        "com.heytap.mcs",
        "com.facebook.services",
        "com.facebook.appmanager"
    )

    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
        }
    }

    fun queryLastUsedMap(context: Context): Map<String, Long> {
        if (!hasUsageStatsPermission(context)) return emptyMap()
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyMap()
            val now = System.currentTimeMillis()
            val start = now - 90L * 24L * 3600L * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now) ?: return emptyMap()
            val map = HashMap<String, Long>(stats.size)
            for (s in stats) {
                if (s.lastTimeUsed > 0L) {
                    val prev = map[s.packageName] ?: 0L
                    if (s.lastTimeUsed > prev) map[s.packageName] = s.lastTimeUsed
                }
            }
            map
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun getRunningPackageSet(context: Context): Set<String> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptySet()
            val list = am.runningAppProcesses ?: return emptySet()
            val out = HashSet<String>(list.size * 2)
            for (p in list) {
                p.pkgList?.forEach { out.add(it) }
                p.processName?.substringBefore(':')?.let { out.add(it) }
            }
            out
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun analyzePackages(context: Context): Map<String, AppRiskProfile> {
        val pm = context.packageManager
        val pkgs = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
        } catch (_: Throwable) {
            try {
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val runningSet = getRunningPackageSet(context)
        val lastUsedMap = queryLastUsedMap(context)
        val hasUsage = lastUsedMap.isNotEmpty()
        val nowMs = System.currentTimeMillis()
        val result = HashMap<String, AppRiskProfile>(pkgs.size)

        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val reqs = pi.requestedPermissions ?: emptyArray()
            val hasBootPerm = reqs.any {
                it == Manifest.permission.RECEIVE_BOOT_COMPLETED ||
                    it == Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            }
            val hasBootReceiver = pi.receivers?.any {
                it.name?.contains("Boot", ignoreCase = true) == true
            } == true
            val isAutorun = (hasBootPerm || hasBootReceiver) && pi.packageName != context.packageName

            val hasAdId = reqs.any {
                it == "com.google.android.gms.permission.AD_ID" ||
                    it == "android.permission.ACCESS_ADSERVICES_AD_ID"
            }
            val hasWake = reqs.any { it == Manifest.permission.WAKE_LOCK }
            val isOemBloat = oemBloatPackages.contains(pi.packageName) ||
                pi.packageName.contains(".analytics", ignoreCase = true) ||
                pi.packageName.contains(".adservices", ignoreCase = true) ||
                pi.packageName.contains(".msa.", ignoreCase = true)

            val lastUsed = lastUsedMap[pi.packageName] ?: 0L
            val daysSince = if (hasUsage) {
                if (lastUsed > 0L) ((nowMs - lastUsed) / 86_400_000L).toInt().coerceAtLeast(0) else 60
            } else {
                -1
            }

            val apkFile = ai.sourceDir?.let { File(it) }
            val apkMb = if (apkFile != null && apkFile.exists()) apkFile.length() / 1_048_576L else 0L

            val reasons = mutableListOf<String>()
            if (isOemBloat) {
                reasons.add(context.getString(R.string.apps_reason_bloat))
            }
            if (!isSys && pi.packageName != context.packageName) {
                if (hasUsage && daysSince >= 30) {
                    reasons.add(context.getString(R.string.apps_reason_unused, daysSince))
                } else if (!hasUsage && ((nowMs - pi.lastUpdateTime) / 86_400_000L) > 120 && apkMb >= 40) {
                    reasons.add(context.getString(R.string.apps_reason_dormant))
                }
                if (hasAdId && isAutorun && hasWake) {
                    reasons.add(context.getString(R.string.apps_reason_tracker_auto))
                }
                if (apkMb >= 100 && (!hasUsage || daysSince >= 14)) {
                    reasons.add(context.getString(R.string.apps_reason_heavy, apkMb))
                }
            }

            result[pi.packageName] = AppRiskProfile(
                packageName = pi.packageName,
                isAutorun = isAutorun,
                isRunning = runningSet.contains(pi.packageName),
                hasAdId = hasAdId,
                isOemBloat = isOemBloat,
                daysSinceUsed = daysSince,
                unnecessaryReasons = reasons
            )
        }
        return result
    }

    fun auditTelemetryAndDataSharing(context: Context): TelemetryCheckReport {
        val cr = context.contentResolver
        val wifiAlways = getSettingInt(cr, "wifi_scan_always_enabled") == 1
        val bleAlways = getSettingInt(cr, "ble_scan_always_enabled") == 1
        val diagUpload = getSettingInt(cr, "send_action_app_error") == 1 ||
            getSecureInt(cr, "send_action_app_error") == 1
        val adbOn = getSettingInt(cr, Settings.Global.ADB_ENABLED) == 1
        val wirelessAdbOn = getSettingInt(cr, "adb_wifi_enabled") == 1

        val notifRaw = getSecureString(cr, "enabled_notification_listeners")
        val notifList = notifRaw.split(':').filter { it.contains('/') }.map { it.substringBefore('/') }.distinct()

        val accRaw = getSecureString(cr, "enabled_accessibility_services")
        val accList = accRaw.split(':').filter { it.contains('/') }.map { it.substringBefore('/') }.distinct()

        val pm = context.packageManager
        val pkgs = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Throwable) {
            emptyList()
        }

        val adTrackers = mutableListOf<Pair<String, String>>()
        val bgLocs = mutableListOf<Pair<String, String>>()
        val oemAnalytics = mutableListOf<Pair<String, String>>()

        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (_: Throwable) {
                pi.packageName
            }
            if (oemTelemetryPackages.contains(pi.packageName)) {
                oemAnalytics.add(label to pi.packageName)
            }
            if (isSys) continue

            val reqs = pi.requestedPermissions ?: continue
            val flags = pi.requestedPermissionsFlags ?: IntArray(0)
            var hasAd = false
            var hasBgLoc = false

            for (i in reqs.indices) {
                val p = reqs[i]
                val granted = i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                if (p == "com.google.android.gms.permission.AD_ID" || p == "android.permission.ACCESS_ADSERVICES_AD_ID") {
                    hasAd = true
                }
                if (p == Manifest.permission.ACCESS_BACKGROUND_LOCATION && granted) {
                    hasBgLoc = true
                }
            }
            if (hasAd) adTrackers.add(label to pi.packageName)
            if (hasBgLoc) bgLocs.add(label to pi.packageName)
        }

        return TelemetryCheckReport(
            wifiScanAlwaysOn = wifiAlways,
            bleScanAlwaysOn = bleAlways,
            diagnosticsUploadOn = diagUpload,
            adbEnabled = adbOn,
            wirelessAdbEnabled = wirelessAdbOn,
            notificationReaders = notifList,
            accessibilityReaders = accList,
            adIdTrackerApps = adTrackers.sortedBy { it.first.lowercase() },
            bgLocationApps = bgLocs.sortedBy { it.first.lowercase() },
            oemAnalyticsApps = oemAnalytics.sortedBy { it.first.lowercase() }
        )
    }

    fun runFullDiagnosis(context: Context, telemetry: TelemetryEngine): FullDeviceDiagnosis {
        val profiles = analyzePackages(context)
        val telReport = auditTelemetryAndDataSharing(context)

        val autorunApps = profiles.values.filter { it.isAutorun }
        val runningAutorun = autorunApps.count { it.isRunning }
        val bloatOrUnused = profiles.values.count { it.unnecessaryReasons.isNotEmpty() }

        val cacheBytes = collectCleanableCacheBytes(context)

        var telFlags = 0
        if (telReport.wifiScanAlwaysOn) telFlags++
        if (telReport.bleScanAlwaysOn) telFlags++
        if (telReport.diagnosticsUploadOn) telFlags++
        if (telReport.adbEnabled) telFlags++
        if (telReport.oemAnalyticsApps.isNotEmpty()) telFlags++

        val findings = mutableListOf<DiagnosticFinding>()
        var score = 100

        // 1. RAM & Background Processes
        if (telemetry.ramUsedPct >= 75f || runningAutorun >= 3) {
            val sev = if (telemetry.ramUsedPct >= 88f) FindingSeverity.CRITICAL else FindingSeverity.WARN
            score -= if (sev == FindingSeverity.CRITICAL) 18 else 10
            findings.add(
                DiagnosticFinding(
                    id = "ram_pressure",
                    severity = sev,
                    title = context.getString(R.string.opt_find_ram_title, telemetry.ramUsedPct.toInt()),
                    whatIsWrong = context.getString(R.string.opt_find_ram_wrong, formatBytesCompact(telemetry.memAvailBytes), runningAutorun),
                    whatToDo = context.getString(R.string.opt_find_ram_do),
                    actionLabel = context.getString(R.string.opt_btn_trim_now),
                    action = OptimizerAction.CLEAN_RAM_CACHE
                )
            )
        }

        // 2. Storage & Residual Cache
        if (cacheBytes >= 512_000L || telemetry.storageUsedPct >= 82f) {
            val sev = if (telemetry.storageUsedPct >= 92f) FindingSeverity.CRITICAL else FindingSeverity.WARN
            score -= if (sev == FindingSeverity.CRITICAL) 18 else 8
            findings.add(
                DiagnosticFinding(
                    id = "storage_junk",
                    severity = sev,
                    title = context.getString(R.string.opt_find_stor_title, telemetry.storageUsedPct.toInt()),
                    whatIsWrong = context.getString(R.string.opt_find_stor_wrong, formatBytesCompact(cacheBytes), formatBytesCompact(telemetry.storageFreeBytes)),
                    whatToDo = context.getString(R.string.opt_find_stor_do),
                    actionLabel = context.getString(R.string.nav_storage),
                    action = OptimizerAction.OPEN_STORAGE_OPT
                )
            )
        }

        // 3. Autorun Overload
        if (autorunApps.size >= 8) {
            score -= if (runningAutorun >= 4) 12 else 6
            findings.add(
                DiagnosticFinding(
                    id = "autorun_load",
                    severity = FindingSeverity.WARN,
                    title = context.getString(R.string.opt_find_auto_title, autorunApps.size),
                    whatIsWrong = context.getString(R.string.opt_find_auto_wrong, runningAutorun),
                    whatToDo = context.getString(R.string.opt_find_auto_do),
                    actionLabel = context.getString(R.string.apps_filter_autorun),
                    action = OptimizerAction.OPEN_AUTORUN
                )
            )
        }

        // 4. Unnecessary / Bloatware Apps
        if (bloatOrUnused > 0) {
            score -= (bloatOrUnused * 2).coerceAtMost(14)
            findings.add(
                DiagnosticFinding(
                    id = "bloat_unused",
                    severity = FindingSeverity.WARN,
                    title = context.getString(R.string.opt_find_bloat_title, bloatOrUnused),
                    whatIsWrong = context.getString(R.string.opt_find_bloat_wrong),
                    whatToDo = context.getString(R.string.opt_find_bloat_do),
                    actionLabel = context.getString(R.string.apps_filter_bloat),
                    action = OptimizerAction.OPEN_BLOAT
                )
            )
        }

        // 5. Telemetry & Data-Sharing Checkboxes Left On
        if (telReport.wifiScanAlwaysOn || telReport.bleScanAlwaysOn) {
            score -= 10
            findings.add(
                DiagnosticFinding(
                    id = "bg_scan_checkboxes",
                    severity = FindingSeverity.WARN,
                    title = context.getString(R.string.opt_find_scan_title),
                    whatIsWrong = context.getString(R.string.opt_find_scan_wrong),
                    whatToDo = context.getString(R.string.opt_find_scan_do),
                    actionLabel = context.getString(R.string.opt_btn_disable_scan),
                    action = OptimizerAction.OPEN_LOCATION_SCAN_SETTINGS
                )
            )
        }

        if (telReport.diagnosticsUploadOn || telReport.adIdTrackerApps.isNotEmpty() || telReport.oemAnalyticsApps.isNotEmpty()) {
            score -= 10
            findings.add(
                DiagnosticFinding(
                    id = "telemetry_sharing",
                    severity = if (telReport.oemAnalyticsApps.isNotEmpty()) FindingSeverity.CRITICAL else FindingSeverity.WARN,
                    title = context.getString(R.string.opt_find_tel_title, telReport.adIdTrackerApps.size, telReport.oemAnalyticsApps.size),
                    whatIsWrong = context.getString(R.string.opt_find_tel_wrong),
                    whatToDo = context.getString(R.string.opt_find_tel_do),
                    actionLabel = context.getString(R.string.priv_tab_telemetry),
                    action = OptimizerAction.OPEN_TELEMETRY_AUDIT
                )
            )
        }

        // 6. Thermal & Battery Drain
        if (telemetry.cpuTempC >= 45f || telemetry.batteryTempC >= 41f || (telemetry.batteryPct <= 25 && telemetry.displayRefreshHz >= 90f)) {
            score -= 10
            findings.add(
                DiagnosticFinding(
                    id = "thermal_battery",
                    severity = FindingSeverity.WARN,
                    title = context.getString(R.string.opt_find_therm_title, telemetry.cpuTempC),
                    whatIsWrong = context.getString(R.string.opt_find_therm_wrong, telemetry.batteryPct, telemetry.displayRefreshHz.toInt()),
                    whatToDo = context.getString(R.string.opt_find_therm_do),
                    actionLabel = context.getString(R.string.mon_tab_batt),
                    action = OptimizerAction.OPEN_BATTERY_MONITOR
                )
            )
        }

        if (findings.isEmpty()) {
            findings.add(
                DiagnosticFinding(
                    id = "all_nominal",
                    severity = FindingSeverity.OPTIMAL,
                    title = context.getString(R.string.opt_find_ok_title),
                    whatIsWrong = context.getString(R.string.opt_find_ok_wrong),
                    whatToDo = context.getString(R.string.opt_find_ok_do),
                    actionLabel = "",
                    action = OptimizerAction.NONE
                )
            )
        }

        return FullDeviceDiagnosis(
            optimizationScore = score.coerceIn(0, 100),
            reclaimableCacheBytes = cacheBytes,
            autorunCount = autorunApps.size,
            runningAutorunCount = runningAutorun,
            bloatOrUnusedCount = bloatOrUnused,
            telemetryFlagsActive = telFlags,
            adTrackerCount = telReport.adIdTrackerApps.size,
            findings = findings
        )
    }

    fun executeOneTapOptimization(context: Context): OptimizationExecutionResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val beforeInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(beforeInfo)

        // 1. Trim background processes of non-essential user & bloat apps
        val pm = context.packageManager
        val pkgs = try {
            pm.getInstalledPackages(0)
        } catch (_: Throwable) {
            emptyList()
        }
        val runningSet = getRunningPackageSet(context)
        var killed = 0
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName
            if (pkg == context.packageName) continue
            val isUser = (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            if ((isUser || oemBloatPackages.contains(pkg)) && runningSet.contains(pkg)) {
                try {
                    am?.killBackgroundProcesses(pkg)
                    killed++
                } catch (_: Throwable) {
                }
            }
        }

        // 2. Clean accessible app & residual temp files via C++ engine
        val paths = collectCleanablePaths(context)
        val freedStorage = if (paths.isNotEmpty()) {
            NativeBridge.safeCleanTempFiles(paths.toTypedArray())
        } else 0L

        val afterInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(afterInfo)
        val freedRam = (afterInfo.availMem - beforeInfo.availMem).coerceAtLeast(0L)

        return OptimizationExecutionResult(
            ramFreedBytes = freedRam,
            storageFreedBytes = freedStorage,
            killedPackagesCount = killed
        )
    }

    fun openLocationScanningSettings(context: Context) {
        val intents = listOf(
            Intent("android.settings.LOCATION_SCANNING_SETTINGS"),
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return
            } catch (_: Throwable) {
            }
        }
    }

    fun openPrivacyDiagnosticsSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_PRIVACY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return
            } catch (_: Throwable) {
            }
        }
    }

    fun openAdsPrivacySettings(context: Context) {
        val intents = listOf(
            Intent("com.google.android.gms.settings.ADS_PRIVACY"),
            Intent("com.google.android.gms.settings.ACTION_ADS_SETTINGS"),
            Intent(Settings.ACTION_PRIVACY_SETTINGS)
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return
            } catch (_: Throwable) {
            }
        }
    }

    fun openNotificationListenersSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
        }
    }

    fun openAppDetails(context: Context, packageName: String) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun collectCleanablePaths(context: Context): List<String> {
        val out = mutableListOf<String>()
        for (dir in listOfNotNull(context.cacheDir, context.externalCacheDir)) {
            dir.walkTopDown().maxDepth(4).forEach { f ->
                if (f.isFile && !f.name.startsWith(".novadeck_io_bench") && !f.absolutePath.contains("/updates/")) {
                    out.add(f.absolutePath)
                }
            }
        }
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && ext.canRead()) {
            val thumbs = File(ext, "DCIM/.thumbnails")
            if (thumbs.exists() && thumbs.canRead()) {
                thumbs.listFiles()?.forEach { f ->
                    if (f.isFile) out.add(f.absolutePath)
                }
            }
        }
        return out
    }

    private fun collectCleanableCacheBytes(context: Context): Long {
        var total = 0L
        for (p in collectCleanablePaths(context)) {
            val f = File(p)
            if (f.exists()) total += f.length()
        }
        return total
    }

    private fun getSettingInt(cr: android.content.ContentResolver, key: String): Int {
        return try {
            Settings.Global.getInt(cr, key, 0)
        } catch (_: Throwable) {
            0
        }
    }

    private fun getSecureInt(cr: android.content.ContentResolver, key: String): Int {
        return try {
            Settings.Secure.getInt(cr, key, 0)
        } catch (_: Throwable) {
            0
        }
    }

    private fun getSecureString(cr: android.content.ContentResolver, key: String): String {
        return try {
            Settings.Secure.getString(cr, key) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
