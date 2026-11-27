package io.novadeck.control.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Debug
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.R
import io.novadeck.control.core.FloatRingBuffer
import io.novadeck.control.core.NativeBridge
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.TelemetryEngine
import io.novadeck.control.core.formatBytesCompact
import io.novadeck.control.core.formatSpeedCompact
import io.novadeck.control.ui.components.DeckButton
import io.novadeck.control.ui.components.DeckChip
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.KeyValueRow
import io.novadeck.control.ui.components.SegmentedBarMeter
import io.novadeck.control.ui.components.ZeroAllocLineChart
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

@Composable
fun LiveMonitorScreen(
    telemetry: TelemetryEngine,
    perfController: PerformanceTierController,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = telemetry.tickVersion.longValue
    val scroll = rememberScrollState()
    val ultraLite = perfController.isUltraLite

    var activeTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 4)) }

    // Tab 1: Live Choreographer FPS meter
    val fpsRing = remember { FloatRingBuffer(60) }
    var measuredFps by remember { mutableFloatStateOf(telemetry.displayRefreshHz) }
    var frameTimeMs by remember { mutableFloatStateOf(16.6f) }
    var fpsTick by remember { mutableLongStateOf(0L) }

    // Tab 3: Native TCP Ping results
    var pinging by remember { mutableStateOf(false) }
    var pingCf by remember { mutableStateOf<DoubleArray?>(null) }
    var pingDns by remember { mutableStateOf<DoubleArray?>(null) }

    // Tab 2: Active process snapshot
    val runningProcs = remember(tick, activeTab) {
        if (activeTab != 2) emptyList() else loadRunningProcessList(context)
    }

    DisposableEffect(activeTab) {
        if (activeTab != 1) {
            onDispose { }
        } else {
            val choreo = Choreographer.getInstance()
            var lastNs = 0L
            var frames = 0
            var accumNs = 0L
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (lastNs != 0L) {
                        val dt = frameTimeNanos - lastNs
                        accumNs += dt
                        frames++
                        if (accumNs >= 500_000_000L) {
                            val fps = (frames.toDouble() / (accumNs / 1e9)).toFloat()
                            val ft = (accumNs.toDouble() / frames / 1e6).toFloat()
                            measuredFps = fps
                            frameTimeMs = ft
                            fpsRing.push(fps)
                            fpsTick++
                            frames = 0
                            accumNs = 0L
                        }
                    }
                    lastNs = frameTimeNanos
                    choreo.postFrameCallback(this)
                }
            }
            choreo.postFrameCallback(cb)
            onDispose {
                choreo.removeFrameCallback(cb)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Sub-navigation + LIVE/PAUSE toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DeckChip(stringResource(R.string.mon_tab_cpu), activeTab == 0, { activeTab = 0 })
                DeckChip(stringResource(R.string.mon_tab_gpu), activeTab == 1, { activeTab = 1 })
                DeckChip(stringResource(R.string.mon_tab_ram), activeTab == 2, { activeTab = 2 })
                DeckChip(stringResource(R.string.mon_tab_net), activeTab == 3, { activeTab = 3 })
                DeckChip(stringResource(R.string.mon_tab_batt), activeTab == 4, { activeTab = 4 })
            }
            DeckChip(
                text = if (telemetry.isPaused) stringResource(R.string.mon_paused) else stringResource(R.string.mon_live),
                selected = telemetry.isPaused,
                onClick = { telemetry.togglePause() },
                accent = if (telemetry.isPaused) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (activeTab) {
                0 -> {
                    // CPU CORES & LOADAVG
                    DeckPanel(
                        title = stringResource(R.string.mon_tag_cpu),
                        ledColor = if (telemetry.procStatAccessible) BrutalistColors.StatusNominal else BrutalistColors.StatusCaution,
                        headerAction = {
                            Text(
                                text = if (telemetry.procStatAccessible) {
                                    stringResource(R.string.mon_proc_ok)
                                } else {
                                    stringResource(R.string.mon_proc_fallback)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                color = if (telemetry.procStatAccessible) BrutalistColors.StatusNominal else BrutalistColors.StatusCaution
                            )
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${telemetry.totalCpuPct.toInt()}% TOTAL",
                                style = DeckType.MetricMono,
                                color = BrutalistColors.SignalAccent
                            )
                            Text(
                                text = String.format("TEMP: %.1f°C · %d CORES", telemetry.cpuTempC, telemetry.coreCount),
                                style = DeckType.DataMono,
                                color = BrutalistColors.TungstenMute
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        ZeroAllocLineChart(
                            primaryBuffer = telemetry.cpuHistory,
                            tickVersion = tick,
                            fixedMax = 100f,
                            height = 76.dp,
                            isUltraLite = ultraLite
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyValueRow(
                            "LOAD AVG (1m / 5m / 15m)",
                            String.format(Locale.US, "%.2f / %.2f / %.2f", telemetry.load1m, telemetry.load5m, telemetry.load15m)
                        )
                        if (telemetry.totalEntities > 0) {
                            KeyValueRow("KERNEL ENTITIES", "${telemetry.runningEntities} running / ${telemetry.totalEntities} total")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in 0 until telemetry.coreCount) {
                                val load = telemetry.coreLoads[i]
                                val curMhz = telemetry.coreCurMhz[i]
                                val maxMhz = telemetry.coreMaxMhz[i]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.mon_core_fmt, i),
                                        style = DeckType.Caption,
                                        color = BrutalistColors.BoneInk,
                                        modifier = Modifier.weight(0.20f)
                                    )
                                    SegmentedBarMeter(
                                        fraction = load / 100f,
                                        segments = 14,
                                        height = 8.dp,
                                        modifier = Modifier.weight(0.42f)
                                    )
                                    Text(
                                        text = if (curMhz > 0) {
                                            stringResource(R.string.mon_freq_fmt, curMhz, if (maxMhz > 0) maxMhz else curMhz)
                                        } else {
                                            "${load.toInt()}%"
                                        },
                                        style = DeckType.Caption,
                                        color = BrutalistColors.TungstenMute,
                                        modifier = Modifier.weight(0.38f)
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // GPU & DISPLAY FRAME PACING
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val glEs = am?.deviceConfigurationInfo?.glEsVersion ?: "3.0"
                    val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

                    DeckPanel(
                        title = stringResource(R.string.mon_tag_gpu),
                        ledColor = BrutalistColors.SignalAccent,
                        headerAction = {
                            Text(
                                text = String.format(Locale.US, "%.0f FPS", measuredFps),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = BrutalistColors.StatusNominal
                            )
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1f FPS", measuredFps),
                                style = DeckType.MetricMono,
                                color = BrutalistColors.SignalAccent
                            )
                            Text(
                                text = String.format(Locale.US, "FRAME: %.2f ms", frameTimeMs),
                                style = DeckType.DataMono,
                                color = BrutalistColors.TitaniumTrace
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        ZeroAllocLineChart(
                            primaryBuffer = fpsRing,
                            tickVersion = fpsTick,
                            fixedMax = telemetry.displayRefreshHz.coerceAtLeast(60f) * 1.1f,
                            height = 76.dp,
                            primaryColor = BrutalistColors.StatusNominal,
                            isUltraLite = ultraLite
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyValueRow("PANEL RESOLUTION", telemetry.displayResolution)
                        KeyValueRow("PIXEL DENSITY", "${telemetry.displayDpi} DPI")
                        KeyValueRow("ACTIVE REFRESH", "${telemetry.displayRefreshHz.toInt()} Hz")
                        KeyValueRow("SUPPORTED MODES", telemetry.displaySupportedModes)
                        KeyValueRow("OPENGL ES", glEs)
                        KeyValueRow("VULKAN API", if (hasVulkan) "HARDWARE SUPPORTED" else "NOT REPORTED")
                        KeyValueRow("RENDER PIPELINE", if (ultraLite) "ULTRA-LITE (0 OVERDRAW)" else "DECK FULL")
                    }
                }

                2 -> {
                    // MEMORY, ZRAM & PROCESSES
                    val rt = Runtime.getRuntime()
                    val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)
                    val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f)

                    DeckPanel(
                        title = stringResource(R.string.mon_tag_mem),
                        ledColor = BrutalistColors.TitaniumTrace,
                        headerAction = {
                            Text(
                                text = "${telemetry.ramUsedPct.toInt()}%",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = BrutalistColors.TitaniumTrace
                            )
                        }
                    ) {
                        SegmentedBarMeter(
                            fraction = telemetry.ramUsedPct / 100f,
                            segments = 24,
                            height = 10.dp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyValueRow(
                            stringResource(R.string.mon_mem_used),
                            "${formatBytesCompact(telemetry.memUsedBytes)} / ${formatBytesCompact(telemetry.memTotalBytes)}"
                        )
                        KeyValueRow(stringResource(R.string.mon_mem_avail), formatBytesCompact(telemetry.memAvailBytes))
                        KeyValueRow(stringResource(R.string.mon_mem_cached), formatBytesCompact(telemetry.memCachedBytes))
                        KeyValueRow(
                            stringResource(R.string.mon_mem_swap),
                            "${formatBytesCompact(telemetry.memSwapUsedBytes)} / ${formatBytesCompact(telemetry.memSwapTotalBytes)}"
                        )
                        KeyValueRow("APP JVM / NATIVE HEAP", String.format(Locale.US, "%.1f MB / %.1f MB", jvmUsedMb, nativeHeapMb))
                        Spacer(modifier = Modifier.height(6.dp))
                        ZeroAllocLineChart(
                            primaryBuffer = telemetry.ramHistory,
                            tickVersion = tick,
                            fixedMax = 100f,
                            height = 64.dp,
                            primaryColor = BrutalistColors.TitaniumTrace,
                            isUltraLite = ultraLite
                        )
                    }

                    DeckPanel(
                        title = stringResource(R.string.mon_tag_procs),
                        ledColor = BrutalistColors.SignalAccent
                    ) {
                        if (runningProcs.isEmpty()) {
                            Text(text = stringResource(R.string.mon_procs_restricted), style = DeckType.Body)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for ((name, pid, importance) in runningProcs) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                            .padding(horizontal = 8.dp, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = name,
                                            style = DeckType.Caption,
                                            color = BrutalistColors.BoneInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                        )
                                        Text(
                                            text = "PID:$pid · $importance",
                                            style = DeckType.Caption,
                                            color = BrutalistColors.TungstenMute
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // NETWORK LINK & NATIVE C++ TCP PING
                    DeckPanel(
                        title = stringResource(R.string.mon_tag_net),
                        ledColor = if (telemetry.netTransport == "OFFLINE") BrutalistColors.GraphiteFaint else BrutalistColors.StatusNominal,
                        headerAction = {
                            Text(
                                text = telemetry.netTransport,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = BrutalistColors.BoneInk
                            )
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                    .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(stringResource(R.string.mon_net_rx), style = DeckType.Caption, color = BrutalistColors.SignalAccent)
                                    Text(formatSpeedCompact(telemetry.netRxBytesPerSec), style = DeckType.DataMono)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                    .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(stringResource(R.string.mon_net_tx), style = DeckType.Caption, color = BrutalistColors.TitaniumTrace)
                                    Text(formatSpeedCompact(telemetry.netTxBytesPerSec), style = DeckType.DataMono)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        ZeroAllocLineChart(
                            primaryBuffer = telemetry.netRxHistory,
                            secondaryBuffer = telemetry.netTxHistory,
                            tickVersion = tick,
                            fixedMax = null,
                            height = 72.dp,
                            primaryColor = BrutalistColors.SignalAccent,
                            secondaryColor = BrutalistColors.TitaniumTrace,
                            isUltraLite = ultraLite
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        KeyValueRow(stringResource(R.string.mon_net_ip), telemetry.netLocalIp)
                        KeyValueRow(stringResource(R.string.mon_net_type), telemetry.netTransport)
                        if (telemetry.netTransport == "WI-FI") {
                            KeyValueRow(stringResource(R.string.mon_net_rssi), "${telemetry.wifiRssiDbm} dBm")
                            KeyValueRow("LINK SPEED / BAND", "${telemetry.wifiLinkSpeedMbps} Mbps · ${telemetry.wifiFreqMhz} MHz")
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        DeckButton(
                            text = if (pinging) stringResource(R.string.stor_btn_scanning) else stringResource(R.string.mon_btn_ping),
                            onClick = {
                                if (pinging) return@DeckButton
                                pinging = true
                                scope.launch {
                                    val (r1, r2) = withContext(Dispatchers.IO) {
                                        Pair(
                                            NativeBridge.safeMeasureTcpPing("1.1.1.1", 443, 4),
                                            NativeBridge.safeMeasureTcpPing("8.8.8.8", 53, 4)
                                        )
                                    }
                                    pingCf = r1
                                    pingDns = r2
                                    pinging = false
                                }
                            },
                            primary = true,
                            enabled = !pinging,
                            modifier = Modifier.fillMaxWidth()
                        )
                        pingCf?.let { r ->
                            Spacer(modifier = Modifier.height(6.dp))
                            KeyValueRow(
                                "TCP 1.1.1.1:443",
                                if (r[0] >= 0.0) String.format(Locale.US, "min %.1f / avg %.1f / jit %.1f ms", r[0], r[1], r[2]) else "UNREACHABLE",
                                valueColor = if (r[0] >= 0.0) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                            )
                        }
                        pingDns?.let { r ->
                            KeyValueRow(
                                "TCP 8.8.8.8:53",
                                if (r[0] >= 0.0) String.format(Locale.US, "min %.1f / avg %.1f / jit %.1f ms", r[0], r[1], r[2]) else "UNREACHABLE",
                                valueColor = if (r[0] >= 0.0) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                            )
                        }
                    }
                }

                else -> {
                    // BATTERY TELEMETRY
                    val battStateLabel = when (telemetry.batteryStatus) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> stringResource(R.string.mon_batt_charging)
                        BatteryManager.BATTERY_STATUS_FULL -> stringResource(R.string.mon_batt_full)
                        else -> stringResource(R.string.mon_batt_discharging)
                    }
                    val battHealthLabel = when (telemetry.batteryHealth) {
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> stringResource(R.string.mon_health_overheat)
                        BatteryManager.BATTERY_HEALTH_DEAD,
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> stringResource(R.string.mon_health_dead)
                        else -> stringResource(R.string.mon_health_good)
                    }
                    val powerWatts = (telemetry.batteryVoltageMv / 1000f) * (abs(telemetry.batteryCurrentMa) / 1000f)

                    DeckPanel(
                        title = stringResource(R.string.mon_tag_batt),
                        ledColor = if (telemetry.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
                            BrutalistColors.StatusNominal
                        } else {
                            BrutalistColors.SignalAccent
                        },
                        headerAction = {
                            Text(
                                text = "${telemetry.batteryPct}% · $battStateLabel",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = BrutalistColors.BoneInk
                            )
                        }
                    ) {
                        KeyValueRow(stringResource(R.string.mon_batt_current), "${telemetry.batteryCurrentMa} mA")
                        KeyValueRow(stringResource(R.string.mon_batt_voltage), "${telemetry.batteryVoltageMv} mV")
                        KeyValueRow(stringResource(R.string.mon_batt_power), String.format(Locale.US, "%.2f W", powerWatts))
                        if (telemetry.batteryChargeMah > 0) {
                            KeyValueRow("CHARGE COUNTER", "${telemetry.batteryChargeMah} mAh")
                        }
                        KeyValueRow(
                            stringResource(R.string.mon_batt_temp),
                            String.format(Locale.US, "%.1f °C", telemetry.batteryTempC),
                            valueColor = if (telemetry.batteryTempC > 42f) BrutalistColors.StatusCaution else BrutalistColors.BoneInk
                        )
                        KeyValueRow(stringResource(R.string.mon_batt_health), battHealthLabel)
                        KeyValueRow(stringResource(R.string.mon_batt_tech), telemetry.batteryTech)
                        Spacer(modifier = Modifier.height(6.dp))
                        ZeroAllocLineChart(
                            primaryBuffer = telemetry.battCurrentHistory,
                            tickVersion = tick,
                            fixedMax = null,
                            height = 64.dp,
                            primaryColor = BrutalistColors.StatusNominal,
                            isUltraLite = ultraLite
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

private fun loadRunningProcessList(context: Context): List<Triple<String, Int, String>> {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val list = am.runningAppProcesses ?: return emptyList()
        list.take(25).map { proc ->
            val imp = when (proc.importance) {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FG"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VIS"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SVC"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
                else -> "BG"
            }
            Triple(proc.processName ?: "proc", proc.pid, imp)
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
