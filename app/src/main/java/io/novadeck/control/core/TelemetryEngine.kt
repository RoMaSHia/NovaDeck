package io.novadeck.control.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import kotlin.math.abs

/**
 * Zero-allocation telemetry orchestrator combining native C++ /proc + /sys readers
 * with safe Android API fallbacks when SELinux restricts raw kernel nodes.
 */
class TelemetryEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    val coreCount: Int = NativeBridge.safeCoreCount()

    // Pre-allocated native transfer buffers (zero allocation per tick)
    private val cpuBuf = FloatArray(2 + coreCount * 3)
    private val memThermalBuf = LongArray(8)
    private val sysStatsBuf = DoubleArray(6)
    private val amMemInfo = ActivityManager.MemoryInfo()
    private val batteryIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    // Pre-allocated per-core arrays
    val coreLoads = FloatArray(coreCount)
    val coreCurMhz = IntArray(coreCount)
    val coreMaxMhz = IntArray(coreCount)

    // Ring buffers (60 samples = 1 minute window at 1 Hz)
    val cpuHistory = FloatRingBuffer(60)
    val ramHistory = FloatRingBuffer(60)
    val netRxHistory = FloatRingBuffer(60)
    val netTxHistory = FloatRingBuffer(60)
    val battCurrentHistory = FloatRingBuffer(60)

    // Compose monotonic tick trigger (bumped once per poll cycle)
    val tickVersion = mutableLongStateOf(0L)
    var isPaused by mutableStateOf(false)
        private set

    fun togglePause() {
        isPaused = !isPaused
    }

    // Latest scalar telemetry fields (volatile for lock-free read after tickVersion bump)
    @Volatile var totalCpuPct: Float = 0f; private set
    @Volatile var procStatAccessible: Boolean = false; private set

    @Volatile var load1m: Float = 0f; private set
    @Volatile var load5m: Float = 0f; private set
    @Volatile var load15m: Float = 0f; private set
    @Volatile var runningEntities: Int = 0; private set
    @Volatile var totalEntities: Int = 0; private set
    @Volatile var selinuxEnforce: Int = -1; private set // 1=Enforcing, 0=Permissive, -1=Restricted

    @Volatile var memTotalBytes: Long = 0L; private set
    @Volatile var memUsedBytes: Long = 0L; private set
    @Volatile var memAvailBytes: Long = 0L; private set
    @Volatile var memCachedBytes: Long = 0L; private set
    @Volatile var memSwapTotalBytes: Long = 0L; private set
    @Volatile var memSwapUsedBytes: Long = 0L; private set
    @Volatile var ramUsedPct: Float = 0f; private set

    @Volatile var cpuTempC: Float = 0f; private set

    @Volatile var batteryPct: Int = 0; private set
    @Volatile var batteryTempC: Float = 0f; private set
    @Volatile var batteryVoltageMv: Int = 0; private set
    @Volatile var batteryCurrentMa: Int = 0; private set
    @Volatile var batteryChargeMah: Int = 0; private set
    @Volatile var batteryStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN; private set
    @Volatile var batteryHealth: Int = BatteryManager.BATTERY_HEALTH_GOOD; private set
    @Volatile var batteryTech: String = "Li-ion"; private set

    @Volatile var storageTotalBytes: Long = 0L; private set
    @Volatile var storageFreeBytes: Long = 0L; private set
    @Volatile var storageUsedPct: Float = 0f; private set

    @Volatile var netRxBytesPerSec: Float = 0f; private set
    @Volatile var netTxBytesPerSec: Float = 0f; private set
    @Volatile var netTransport: String = "OFFLINE"; private set
    @Volatile var netLocalIp: String = "—"; private set
    @Volatile var wifiRssiDbm: Int = 0; private set
    @Volatile var wifiLinkSpeedMbps: Int = 0; private set
    @Volatile var wifiFreqMhz: Int = 0; private set

    @Volatile var displayRefreshHz: Float = 60f; private set
    @Volatile var displaySupportedModes: String = "60 Hz"; private set
    @Volatile var displayResolution: String = "—"; private set
    @Volatile var displayDpi: Int = 0; private set

    @Volatile var uptimeMillis: Long = 0L; private set
    @Volatile var healthIndex: Int = 100; private set

    private var prevRxBytes: Long = -1L
    private var prevTxBytes: Long = -1L
    private var prevNetSampleMs: Long = 0L

    private var prevAppCpuMs: Long = Process.getElapsedCpuTime()
    private var prevWallMs: Long = SystemClock.elapsedRealtime()

    val deviceModel: String = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"
    val socBoard: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val soc = Build.SOC_MODEL
        if (soc.isNotBlank() && soc != "unknown") "$soc (${Build.HARDWARE})" else Build.HARDWARE
    } else {
        "${Build.HARDWARE} / ${Build.BOARD}"
    }
    val abiString: String = Build.SUPPORTED_ABIS.joinToString(", ")
    val androidVersionLabel: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val kernelVersion: String = System.getProperty("os.version") ?: "Linux"
    val buildFingerprint: String = Build.DISPLAY ?: Build.ID

    init {
        readDisplayMetrics()
    }

    @Suppress("DEPRECATION")
    private fun readDisplayMetrics() {
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                displayRefreshHz = display.refreshRate
                val modes = display.supportedModes
                val rates = modes.map { it.refreshRate.toInt() }.distinct().sorted()
                if (rates.isNotEmpty()) {
                    displaySupportedModes = rates.joinToString(" / ") { "${it}Hz" }
                }
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                displayResolution = "${metrics.widthPixels} × ${metrics.heightPixels}"
                displayDpi = metrics.densityDpi
            }
        } catch (_: Throwable) {
            // Safe ignore
        }
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                if (!isPaused) {
                    sampleOnce()
                    withContext(Dispatchers.Main.immediate) {
                        tickVersion.longValue++
                    }
                }
                delay(1000L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun sampleOnce() {
        val nowMs = SystemClock.elapsedRealtime()
        uptimeMillis = nowMs

        // 1. Native CPU /proc/stat + sysfs cpufreq
        val readCores = NativeBridge.safeReadCpuMetrics(cpuBuf)
        val hasProcStat = readCores > 0 && cpuBuf[1] > 0.5f
        procStatAccessible = hasProcStat

        var freqEstSum = 0f
        var freqEstCount = 0
        for (i in 0 until coreCount) {
            val curMhz = if (readCores > i) cpuBuf[2 + i * 3 + 1].toInt() else 0
            val maxMhz = if (readCores > i) cpuBuf[2 + i * 3 + 2].toInt() else 0
            coreCurMhz[i] = curMhz
            coreMaxMhz[i] = maxMhz

            if (hasProcStat && cpuBuf[2 + i * 3] >= 0f) {
                coreLoads[i] = cpuBuf[2 + i * 3].coerceIn(0f, 100f)
            } else if (curMhz > 0 && maxMhz > 0) {
                val est = ((curMhz.toFloat() / maxMhz.toFloat()) * 100f).coerceIn(3f, 100f)
                coreLoads[i] = est
                freqEstSum += est
                freqEstCount++
            } else {
                val appCpu = Process.getElapsedCpuTime()
                val dCpu = (appCpu - prevAppCpuMs).coerceAtLeast(0L)
                val dWall = (nowMs - prevWallMs).coerceAtLeast(1L)
                val est = ((dCpu.toFloat() / (dWall.toFloat() * coreCount)) * 100f).coerceIn(2f, 100f)
                coreLoads[i] = est
            }
        }
        prevAppCpuMs = Process.getElapsedCpuTime()
        prevWallMs = nowMs

        totalCpuPct = if (hasProcStat && cpuBuf[0] >= 0f) {
            cpuBuf[0].coerceIn(0f, 100f)
        } else if (freqEstCount > 0) {
            (freqEstSum / freqEstCount).coerceIn(0f, 100f)
        } else {
            var s = 0f
            for (i in 0 until coreCount) s += coreLoads[i]
            (s / coreCount).coerceIn(0f, 100f)
        }
        cpuHistory.push(totalCpuPct)

        // 1b. Native System Stats (/proc/loadavg + SELinux)
        val sysMask = NativeBridge.safeReadSystemStats(sysStatsBuf)
        if ((sysMask and 1) != 0) {
            load1m = sysStatsBuf[0].toFloat()
            load5m = sysStatsBuf[1].toFloat()
            load15m = sysStatsBuf[2].toFloat()
            runningEntities = sysStatsBuf[3].toInt()
            totalEntities = sysStatsBuf[4].toInt()
        }
        if ((sysMask and 2) != 0) {
            selinuxEnforce = sysStatsBuf[5].toInt()
        }

        // 2. Native /proc/meminfo & thermal + ActivityManager fallback
        val memStatus = NativeBridge.safeReadMemAndThermal(memThermalBuf)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.getMemoryInfo(amMemInfo)

        if ((memStatus and 1) != 0 && memThermalBuf[0] > 0L) {
            memTotalBytes = memThermalBuf[0]
            memAvailBytes = if (memThermalBuf[1] > 0L) memThermalBuf[1] else amMemInfo.availMem
            memUsedBytes = (memTotalBytes - memAvailBytes).coerceAtLeast(0L)
            memCachedBytes = memThermalBuf[3] + memThermalBuf[4]
            memSwapTotalBytes = memThermalBuf[5]
            memSwapUsedBytes = (memThermalBuf[5] - memThermalBuf[6]).coerceAtLeast(0L)
        } else {
            memTotalBytes = amMemInfo.totalMem
            memAvailBytes = amMemInfo.availMem
            memUsedBytes = (memTotalBytes - memAvailBytes).coerceAtLeast(0L)
            memCachedBytes = 0L
            memSwapTotalBytes = 0L
            memSwapUsedBytes = 0L
        }
        ramUsedPct = if (memTotalBytes > 0L) {
            ((memUsedBytes.toDouble() / memTotalBytes.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
        } else 0f
        ramHistory.push(ramUsedPct)

        // 3. Battery Telemetry
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val bIntent = try {
            context.registerReceiver(null, batteryIntentFilter)
        } catch (_: Throwable) {
            null
        }
        val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        }
        val rawTemp = bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        batteryTempC = rawTemp / 10f
        batteryVoltageMv = bIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        batteryStatus = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        batteryHealth = bIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            ?: BatteryManager.BATTERY_HEALTH_GOOD
        batteryTech = bIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

        val currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val ma = if (abs(currentUa) > 10000) currentUa / 1000 else currentUa
        batteryCurrentMa = ma
        battCurrentHistory.push(abs(ma).toFloat())

        val chargeUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        batteryChargeMah = if (chargeUah > 0) chargeUah / 1000 else 0

        cpuTempC = if ((memStatus and 2) != 0 && memThermalBuf[7] > 0L) {
            memThermalBuf[7] / 1000f
        } else {
            batteryTempC
        }

        // 4. Storage Partition Telemetry
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            storageTotalBytes = stat.totalBytes
            storageFreeBytes = stat.availableBytes
            val used = (storageTotalBytes - storageFreeBytes).coerceAtLeast(0L)
            storageUsedPct = if (storageTotalBytes > 0L) {
                ((used.toDouble() / storageTotalBytes.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
            } else 0f
        } catch (_: Throwable) {
            // Safe ignore
        }

        // 5. Network Speed & Link Telemetry
        val curRx = TrafficStats.getTotalRxBytes()
        val curTx = TrafficStats.getTotalTxBytes()
        if (prevRxBytes >= 0L && curRx >= prevRxBytes && prevNetSampleMs > 0L) {
            val dtSec = ((nowMs - prevNetSampleMs).coerceAtLeast(200L)) / 1000f
            netRxBytesPerSec = (curRx - prevRxBytes).toFloat() / dtSec
            netTxBytesPerSec = (curTx - prevTxBytes).coerceAtLeast(0L).toFloat() / dtSec
        }
        prevRxBytes = curRx
        prevTxBytes = curTx
        prevNetSampleMs = nowMs
        netRxHistory.push(netRxBytesPerSec / 1024f)
        netTxHistory.push(netTxBytesPerSec / 1024f)

        sampleNetworkMetadata()

        // 6. Composite System Health Index (0..100)
        var score = 100
        if (ramUsedPct > 90f) score -= 18 else if (ramUsedPct > 80f) score -= 8
        if (storageUsedPct > 92f) score -= 18 else if (storageUsedPct > 82f) score -= 8
        if (cpuTempC > 75f) score -= 20 else if (cpuTempC > 60f) score -= 8
        if (batteryTempC > 44f) score -= 15 else if (batteryTempC > 39f) score -= 5
        if (batteryHealth != BatteryManager.BATTERY_HEALTH_GOOD &&
            batteryHealth != BatteryManager.BATTERY_HEALTH_UNKNOWN) {
            score -= 12
        }
        healthIndex = score.coerceIn(0, 100)
    }

    @Suppress("DEPRECATION")
    private fun sampleNetworkMetadata() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            netTransport = when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "LINK"
            }

            var ipFound = "—"
            val linkProps = if (net != null) cm.getLinkProperties(net) else null
            if (linkProps != null) {
                for (la in linkProps.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ipFound = addr.hostAddress ?: "—"
                        break
                    }
                }
                if (ipFound == "—" && linkProps.linkAddresses.isNotEmpty()) {
                    ipFound = linkProps.linkAddresses.firstOrNull()?.address?.hostAddress ?: "—"
                }
            }
            netLocalIp = ipFound

            if (netTransport == "WI-FI") {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wm?.connectionInfo
                wifiRssiDbm = info?.rssi ?: -127
                wifiLinkSpeedMbps = info?.linkSpeed ?: 0
                wifiFreqMhz = info?.frequency ?: 0
            } else {
                wifiRssiDbm = 0
                wifiLinkSpeedMbps = 0
                wifiFreqMhz = 0
            }
        } catch (_: Throwable) {
            netTransport = "OFFLINE"
            netLocalIp = "—"
            wifiRssiDbm = 0
            wifiLinkSpeedMbps = 0
            wifiFreqMhz = 0
        }
    }
}

fun formatBytesCompact(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

fun formatSpeedCompact(bytesPerSec: Float): String {
    if (bytesPerSec <= 0f) return "0 B/s"
    val kb = 1024f
    val mb = kb * 1024f
    return when {
        bytesPerSec >= mb -> String.format("%.2f MB/s", bytesPerSec / mb)
        bytesPerSec >= kb -> String.format("%.1f KB/s", bytesPerSec / kb)
        else -> "${bytesPerSec.toInt()} B/s"
    }
}
