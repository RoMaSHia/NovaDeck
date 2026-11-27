package io.novadeck.control.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.R
import io.novadeck.control.core.NativeBridge
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.TelemetryEngine
import io.novadeck.control.core.formatBytesCompact
import io.novadeck.control.core.formatSpeedCompact
import io.novadeck.control.ui.components.BrutalistArcGauge
import io.novadeck.control.ui.components.DeckButton
import io.novadeck.control.ui.components.DeckChip
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.KeyValueRow
import io.novadeck.control.ui.components.SegmentedBarMeter
import io.novadeck.control.ui.components.ZeroAllocLineChart
import io.novadeck.control.ui.navigation.DeckRoute
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType

private enum class ComponentInspect {
    CPU, DISPLAY_GPU, MEMORY, STORAGE, BATTERY, RADIO_SENSORS
}

@Composable
fun HomeScreen(
    telemetry: TelemetryEngine,
    perfController: PerformanceTierController,
    onNavigate: (DeckRoute) -> Unit,
    onOpenMonitorTab: (Int) -> Unit
) {
    val context = LocalContext.current
    val tick = telemetry.tickVersion.longValue
    val scroll = rememberScrollState()
    var inspectTarget by remember { mutableStateOf<ComponentInspect?>(null) }

    val sensorCount = remember {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sm?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0
    }
    val glEsVersion = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.deviceConfigurationInfo?.glEsVersion ?: "3.0"
    }
    val hasVulkan = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }
    val rootFlags = remember { NativeBridge.safeCheckRootIndicators() }

    val health = telemetry.healthIndex
    val healthColor = when {
        health >= 80 -> BrutalistColors.StatusNominal
        health >= 55 -> BrutalistColors.StatusCaution
        else -> BrutalistColors.StatusFault
    }
    val healthStatusText = when {
        health >= 80 -> stringResource(R.string.status_nominal)
        health >= 55 -> stringResource(R.string.status_elevated)
        else -> stringResource(R.string.status_critical)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 01 // HARDWARE IDENTITY
            DeckPanel(
                title = stringResource(R.string.home_tag_identity),
                ledColor = healthColor,
                headerAction = {
                    Box(
                        modifier = Modifier
                            .background(healthColor.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
                            .border(1.dp, healthColor, RoundedCornerShape(2.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$health% · $healthStatusText",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = healthColor
                        )
                    }
                }
            ) {
                KeyValueRow(stringResource(R.string.home_model), telemetry.deviceModel)
                KeyValueRow(stringResource(R.string.home_soc), telemetry.socBoard)
                KeyValueRow(
                    stringResource(R.string.home_arch),
                    "${telemetry.abiString} (${telemetry.coreCount}C)"
                )
                KeyValueRow(stringResource(R.string.home_android), telemetry.androidVersionLabel)
                KeyValueRow(stringResource(R.string.home_kernel), telemetry.kernelVersion)
                KeyValueRow(
                    stringResource(R.string.home_uptime),
                    formatUptime(telemetry.uptimeMillis),
                    valueColor = BrutalistColors.SignalAccent
                )
            }

            // 02 // LIVE VITALS MATRIX (tapping opens matching Monitor tab)
            DeckPanel(
                title = stringResource(R.string.home_tag_telemetry),
                ledColor = BrutalistColors.SignalAccent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BrutalistArcGauge(
                        percent = telemetry.totalCpuPct,
                        valueText = "${telemetry.totalCpuPct.toInt()}%",
                        label = stringResource(R.string.home_cpu_load),
                        accentColor = BrutalistColors.SignalAccent,
                        modifier = Modifier.clickable { onOpenMonitorTab(0) }
                    )
                    BrutalistArcGauge(
                        percent = telemetry.ramUsedPct,
                        valueText = "${telemetry.ramUsedPct.toInt()}%",
                        label = stringResource(R.string.home_ram_load),
                        accentColor = BrutalistColors.TitaniumTrace,
                        modifier = Modifier.clickable { onOpenMonitorTab(2) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniVitalTile(
                        label = stringResource(R.string.home_battery),
                        primary = "${telemetry.batteryPct}%",
                        secondary = String.format("%.1f°C · %d mA", telemetry.batteryTempC, telemetry.batteryCurrentMa),
                        fraction = telemetry.batteryPct / 100f,
                        accent = if (telemetry.batteryPct <= 20) BrutalistColors.StatusFault else BrutalistColors.StatusNominal,
                        onClick = { onOpenMonitorTab(4) },
                        modifier = Modifier.weight(1f)
                    )
                    MiniVitalTile(
                        label = stringResource(R.string.home_storage),
                        primary = "${telemetry.storageUsedPct.toInt()}%",
                        secondary = "${formatBytesCompact(telemetry.storageFreeBytes)} free",
                        fraction = telemetry.storageUsedPct / 100f,
                        accent = if (telemetry.storageUsedPct > 90f) BrutalistColors.StatusCaution else BrutalistColors.TitaniumTrace,
                        onClick = { onNavigate(DeckRoute.STORAGE) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniVitalTile(
                        label = stringResource(R.string.home_display_hz),
                        primary = "${telemetry.displayRefreshHz.toInt()} Hz",
                        secondary = "${telemetry.displayResolution} · GLES $glEsVersion",
                        fraction = (telemetry.displayRefreshHz / 120f).coerceIn(0.25f, 1f),
                        accent = BrutalistColors.TitaniumTrace,
                        onClick = { onOpenMonitorTab(1) },
                        modifier = Modifier.weight(1f)
                    )
                    MiniVitalTile(
                        label = stringResource(R.string.home_network),
                        primary = telemetry.netTransport,
                        secondary = "↓${formatSpeedCompact(telemetry.netRxBytesPerSec)} ↑${formatSpeedCompact(telemetry.netTxBytesPerSec)}",
                        fraction = ((telemetry.netRxBytesPerSec + telemetry.netTxBytesPerSec) / (2f * 1024f * 1024f)).coerceIn(0.05f, 1f),
                        accent = if (telemetry.netTransport == "OFFLINE") BrutalistColors.GraphiteFaint else BrutalistColors.StatusNominal,
                        onClick = { onOpenMonitorTab(3) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                ZeroAllocLineChart(
                    primaryBuffer = telemetry.cpuHistory,
                    secondaryBuffer = telemetry.ramHistory,
                    tickVersion = tick,
                    fixedMax = 100f,
                    height = 72.dp,
                    isUltraLite = perfController.isUltraLite
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "■ CPU ${telemetry.totalCpuPct.toInt()}% (${String.format("%.1f°C", telemetry.cpuTempC)})",
                        style = DeckType.Caption,
                        color = BrutalistColors.SignalAccent
                    )
                    Text(
                        text = "■ RAM ${formatBytesCompact(telemetry.memUsedBytes)} / ${formatBytesCompact(telemetry.memTotalBytes)}",
                        style = DeckType.Caption,
                        color = BrutalistColors.TitaniumTrace
                    )
                }
            }

            // 03 // HARDWARE COMPONENTS (tap to inspect low-level spec sheet)
            DeckPanel(
                title = stringResource(R.string.home_tag_components),
                ledColor = BrutalistColors.TitaniumTrace
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ComponentTile(
                            tag = "CPU",
                            summary = "${telemetry.coreCount} CORES · ${telemetry.coreMaxMhz.maxOrNull() ?: 0} MHz",
                            onClick = { inspectTarget = ComponentInspect.CPU },
                            modifier = Modifier.weight(1f)
                        )
                        ComponentTile(
                            tag = "GPU / DISP",
                            summary = "${telemetry.displayRefreshHz.toInt()}Hz · ${if (hasVulkan) "VK+GLES" else "GLES $glEsVersion"}",
                            onClick = { inspectTarget = ComponentInspect.DISPLAY_GPU },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ComponentTile(
                            tag = "MEMORY",
                            summary = "${formatBytesCompact(telemetry.memTotalBytes)} · ZRAM ${formatBytesCompact(telemetry.memSwapTotalBytes)}",
                            onClick = { inspectTarget = ComponentInspect.MEMORY },
                            modifier = Modifier.weight(1f)
                        )
                        ComponentTile(
                            tag = "STORAGE",
                            summary = "${formatBytesCompact(telemetry.storageFreeBytes)} / ${formatBytesCompact(telemetry.storageTotalBytes)}",
                            onClick = { inspectTarget = ComponentInspect.STORAGE },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ComponentTile(
                            tag = "BATTERY",
                            summary = "${telemetry.batteryPct}% · ${telemetry.batteryTech} · ${telemetry.batteryVoltageMv}mV",
                            onClick = { inspectTarget = ComponentInspect.BATTERY },
                            modifier = Modifier.weight(1f)
                        )
                        ComponentTile(
                            tag = "BUS / RADIO",
                            summary = "${telemetry.netTransport} · $sensorCount SENSORS",
                            onClick = { inspectTarget = ComponentInspect.RADIO_SENSORS },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 04 // TO REVIEW & MODULES
            DeckPanel(
                title = stringResource(R.string.home_tag_review),
                ledColor = if (rootFlags != 0 || telemetry.storageUsedPct > 85f) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReviewRow(
                        label = stringResource(R.string.home_rev_storage),
                        status = "${telemetry.storageUsedPct.toInt()}% used",
                        actionText = stringResource(R.string.nav_storage),
                        warn = telemetry.storageUsedPct > 85f,
                        onClick = { onNavigate(DeckRoute.STORAGE) }
                    )
                    ReviewRow(
                        label = stringResource(R.string.home_rev_security),
                        status = if (rootFlags == 0) stringResource(R.string.priv_val_clean) else stringResource(R.string.priv_val_detected),
                        actionText = stringResource(R.string.nav_privacy),
                        warn = rootFlags != 0,
                        onClick = { onNavigate(DeckRoute.PRIVACY) }
                    )
                    ReviewRow(
                        label = stringResource(R.string.home_rev_apps),
                        status = stringResource(R.string.home_mod_apps_desc),
                        actionText = stringResource(R.string.nav_apps),
                        warn = false,
                        onClick = { onNavigate(DeckRoute.APPS) }
                    )
                    ReviewRow(
                        label = stringResource(R.string.home_rev_bench),
                        status = stringResource(R.string.home_mod_bench_desc),
                        actionText = stringResource(R.string.nav_bench),
                        warn = false,
                        onClick = { onNavigate(DeckRoute.BENCHMARK) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Hardware Component Inspector Sheet
        val target = inspectTarget
        if (target != null) {
            ComponentInspectorSheet(
                target = target,
                telemetry = telemetry,
                glEsVersion = glEsVersion,
                hasVulkan = hasVulkan,
                sensorCount = sensorCount,
                onDismiss = { inspectTarget = null },
                onOpenMonitor = { tab ->
                    inspectTarget = null
                    onOpenMonitorTab(tab)
                },
                onOpenStorage = {
                    inspectTarget = null
                    onNavigate(DeckRoute.STORAGE)
                },
                onOpenSensors = {
                    inspectTarget = null
                    onNavigate(DeckRoute.HARDWARE)
                }
            )
        }
    }
}

@Composable
private fun ComponentInspectorSheet(
    target: ComponentInspect,
    telemetry: TelemetryEngine,
    glEsVersion: String,
    hasVulkan: Boolean,
    sensorCount: Int,
    onDismiss: () -> Unit,
    onOpenMonitor: (Int) -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSensors: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrutalistColors.ObsidianBg.copy(alpha = 0.88f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        DeckPanel(
            title = "HW // ${target.name}",
            ledColor = BrutalistColors.SignalAccent,
            borderColor = BrutalistColors.ActiveBorder,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            headerAction = {
                DeckChip(
                    text = stringResource(R.string.action_close),
                    selected = false,
                    onClick = onDismiss
                )
            }
        ) {
            when (target) {
                ComponentInspect.CPU -> {
                    KeyValueRow("SOC / HARDWARE", telemetry.socBoard)
                    KeyValueRow("ABI", telemetry.abiString)
                    KeyValueRow("CORES", "${telemetry.coreCount}")
                    KeyValueRow("MAX CLOCK", "${telemetry.coreMaxMhz.maxOrNull() ?: 0} MHz")
                    KeyValueRow("LOAD AVG", String.format("%.2f / %.2f / %.2f", telemetry.load1m, telemetry.load5m, telemetry.load15m))
                    KeyValueRow("CPU TEMP", String.format("%.1f °C", telemetry.cpuTempC))
                    Spacer(modifier = Modifier.height(10.dp))
                    DeckButton(stringResource(R.string.nav_monitor), onClick = { onOpenMonitor(0) }, primary = true, modifier = Modifier.fillMaxWidth())
                }
                ComponentInspect.DISPLAY_GPU -> {
                    KeyValueRow("RESOLUTION", telemetry.displayResolution)
                    KeyValueRow("DENSITY", "${telemetry.displayDpi} DPI")
                    KeyValueRow("ACTIVE REFRESH", "${telemetry.displayRefreshHz.toInt()} Hz")
                    KeyValueRow("SUPPORTED MODES", telemetry.displaySupportedModes)
                    KeyValueRow("OPENGL ES", glEsVersion)
                    KeyValueRow("VULKAN HW", if (hasVulkan) "SUPPORTED" else "NO")
                    Spacer(modifier = Modifier.height(10.dp))
                    DeckButton(stringResource(R.string.nav_monitor), onClick = { onOpenMonitor(1) }, primary = true, modifier = Modifier.fillMaxWidth())
                }
                ComponentInspect.MEMORY -> {
                    KeyValueRow("PHYSICAL TOTAL", formatBytesCompact(telemetry.memTotalBytes))
                    KeyValueRow("USED", formatBytesCompact(telemetry.memUsedBytes))
                    KeyValueRow("AVAILABLE", formatBytesCompact(telemetry.memAvailBytes))
                    KeyValueRow("CACHED / BUFFERS", formatBytesCompact(telemetry.memCachedBytes))
                    KeyValueRow("ZRAM / SWAP", "${formatBytesCompact(telemetry.memSwapUsedBytes)} / ${formatBytesCompact(telemetry.memSwapTotalBytes)}")
                    Spacer(modifier = Modifier.height(10.dp))
                    DeckButton(stringResource(R.string.nav_monitor), onClick = { onOpenMonitor(2) }, primary = true, modifier = Modifier.fillMaxWidth())
                }
                ComponentInspect.STORAGE -> {
                    KeyValueRow("PARTITION", "/data")
                    KeyValueRow("CAPACITY", formatBytesCompact(telemetry.storageTotalBytes))
                    KeyValueRow("FREE", formatBytesCompact(telemetry.storageFreeBytes))
                    KeyValueRow("OCCUPANCY", "${telemetry.storageUsedPct.toInt()}%")
                    Spacer(modifier = Modifier.height(10.dp))
                    DeckButton(stringResource(R.string.nav_storage), onClick = onOpenStorage, primary = true, modifier = Modifier.fillMaxWidth())
                }
                ComponentInspect.BATTERY -> {
                    KeyValueRow("LEVEL", "${telemetry.batteryPct}%")
                    KeyValueRow("CURRENT", "${telemetry.batteryCurrentMa} mA")
                    KeyValueRow("VOLTAGE", "${telemetry.batteryVoltageMv} mV")
                    KeyValueRow("CHARGE COUNTER", if (telemetry.batteryChargeMah > 0) "${telemetry.batteryChargeMah} mAh" else "N/A")
                    KeyValueRow("TEMPERATURE", String.format("%.1f °C", telemetry.batteryTempC))
                    KeyValueRow("CHEMISTRY", telemetry.batteryTech)
                    Spacer(modifier = Modifier.height(10.dp))
                    DeckButton(stringResource(R.string.nav_monitor), onClick = { onOpenMonitor(4) }, primary = true, modifier = Modifier.fillMaxWidth())
                }
                ComponentInspect.RADIO_SENSORS -> {
                    KeyValueRow("TRANSPORT", telemetry.netTransport)
                    KeyValueRow("LOCAL IP", telemetry.netLocalIp)
                    if (telemetry.netTransport == "WI-FI") {
                        KeyValueRow("WI-FI RSSI", "${telemetry.wifiRssiDbm} dBm")
                        KeyValueRow("LINK SPEED", "${telemetry.wifiLinkSpeedMbps} Mbps (${telemetry.wifiFreqMhz} MHz)")
                    }
                    KeyValueRow("HARDWARE SENSORS", "$sensorCount")
                    Spacer(modifier = Modifier.height(10.dp))
                    DeckButton(stringResource(R.string.nav_hardware), onClick = onOpenSensors, primary = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun MiniVitalTile(
    label: String,
    primary: String,
    secondary: String,
    fraction: Float,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(BrutalistColors.ObsidianBg)
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(9.dp)
    ) {
        Text(text = label, style = DeckType.Caption, color = BrutalistColors.TungstenMute)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = primary, style = DeckType.MetricMono, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = secondary,
            style = DeckType.Caption,
            color = BrutalistColors.BoneInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(5.dp))
        SegmentedBarMeter(fraction = fraction, segments = 12, height = 4.dp, activeColor = accent)
    }
}

@Composable
private fun ComponentTile(
    tag: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(BrutalistColors.SteelElevated)
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tag,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = BrutalistColors.SignalAccent
            )
            Text(text = "↗", style = DeckType.Caption, color = BrutalistColors.TungstenMute)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = summary,
            style = DeckType.Caption,
            color = BrutalistColors.BoneInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReviewRow(
    label: String,
    status: String,
    actionText: String,
    warn: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = label, style = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = status,
                style = DeckType.Caption,
                color = if (warn) BrutalistColors.StatusCaution else BrutalistColors.TungstenMute,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DeckChip(text = actionText, selected = warn, onClick = onClick)
    }
}

private fun formatUptime(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return String.format("%02d:%02d:%02d", h, m, s)
}
