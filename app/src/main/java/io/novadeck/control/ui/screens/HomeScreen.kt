package io.novadeck.control.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.R
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.TelemetryEngine
import io.novadeck.control.core.formatBytesCompact
import io.novadeck.control.ui.components.BrutalistArcGauge
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.KeyValueRow
import io.novadeck.control.ui.components.OtaUpdaterControls
import io.novadeck.control.ui.components.ZeroAllocLineChart
import io.novadeck.control.ui.navigation.DeckRoute
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType

@Composable
fun HomeScreen(
    telemetry: TelemetryEngine,
    perfController: PerformanceTierController,
    onNavigate: (DeckRoute) -> Unit
) {
    val tick = telemetry.tickVersion.longValue
    val scroll = rememberScrollState()

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
                stringResource(R.string.home_ram_cap),
                formatBytesCompact(telemetry.memTotalBytes)
            )
            KeyValueRow(
                stringResource(R.string.home_uptime),
                formatUptime(telemetry.uptimeMillis),
                valueColor = BrutalistColors.SignalAccent
            )
        }

        // 02 // LIVE TELEMETRY MATRIX
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
                    accentColor = BrutalistColors.SignalAccent
                )
                BrutalistArcGauge(
                    percent = telemetry.ramUsedPct,
                    valueText = "${telemetry.ramUsedPct.toInt()}%",
                    label = stringResource(R.string.home_ram_load),
                    accentColor = BrutalistColors.TitaniumTrace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniTelemetryBox(
                    label = stringResource(R.string.home_battery),
                    primary = "${telemetry.batteryPct}%",
                    secondary = String.format("%.1f°C · %d mA", telemetry.batteryTempC, telemetry.batteryCurrentMa),
                    accent = if (telemetry.batteryPct <= 20) BrutalistColors.StatusFault else BrutalistColors.StatusNominal,
                    modifier = Modifier.weight(1f)
                )
                MiniTelemetryBox(
                    label = stringResource(R.string.home_storage),
                    primary = "${telemetry.storageUsedPct.toInt()}%",
                    secondary = "${formatBytesCompact(telemetry.storageFreeBytes)} free",
                    accent = if (telemetry.storageUsedPct > 90f) BrutalistColors.StatusCaution else BrutalistColors.TitaniumTrace,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            ZeroAllocLineChart(
                primaryBuffer = telemetry.cpuHistory,
                secondaryBuffer = telemetry.ramHistory,
                tickVersion = tick,
                fixedMax = 100f,
                height = 76.dp,
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

        // 03 // DIAGNOSTIC MODULES QUICK MATRIX
        DeckPanel(
            title = stringResource(R.string.home_tag_modules),
            ledColor = BrutalistColors.TitaniumTrace
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModuleTile(
                        code = "M1",
                        title = stringResource(R.string.nav_monitor),
                        desc = stringResource(R.string.home_mod_monitor_desc),
                        onClick = { onNavigate(DeckRoute.MONITOR) },
                        modifier = Modifier.weight(1f)
                    )
                    ModuleTile(
                        code = "M2",
                        title = stringResource(R.string.nav_storage),
                        desc = stringResource(R.string.home_mod_storage_desc),
                        onClick = { onNavigate(DeckRoute.STORAGE) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModuleTile(
                        code = "M3",
                        title = stringResource(R.string.nav_apps),
                        desc = stringResource(R.string.home_mod_apps_desc),
                        onClick = { onNavigate(DeckRoute.APPS) },
                        modifier = Modifier.weight(1f)
                    )
                    ModuleTile(
                        code = "M4",
                        title = stringResource(R.string.nav_hardware),
                        desc = stringResource(R.string.home_mod_hardware_desc),
                        onClick = { onNavigate(DeckRoute.HARDWARE) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModuleTile(
                        code = "M5",
                        title = stringResource(R.string.nav_privacy),
                        desc = stringResource(R.string.home_mod_privacy_desc),
                        onClick = { onNavigate(DeckRoute.PRIVACY) },
                        modifier = Modifier.weight(1f)
                    )
                    ModuleTile(
                        code = "M6",
                        title = stringResource(R.string.nav_bench),
                        desc = stringResource(R.string.home_mod_bench_desc),
                        onClick = { onNavigate(DeckRoute.BENCHMARK) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        // 04 // IN-PLACE SELF-UPDATER
        DeckPanel(
            title = stringResource(R.string.update_tag),
            ledColor = BrutalistColors.StatusNominal
        ) {
            OtaUpdaterControls()
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun MiniTelemetryBox(
    label: String,
    primary: String,
    secondary: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .padding(10.dp)
    ) {
        Text(text = label, style = DeckType.Caption, color = BrutalistColors.TungstenMute)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = primary, style = DeckType.MetricMono, color = accent)
        Text(
            text = secondary,
            style = DeckType.Caption,
            color = BrutalistColors.BoneInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModuleTile(
    code: String,
    title: String,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(BrutalistColors.SteelElevated)
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = BrutalistColors.BoneInk
            )
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = BrutalistColors.SignalAccent
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = desc,
            style = DeckType.Caption,
            color = BrutalistColors.TungstenMute,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatUptime(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return String.format("%02d:%02d:%02d", h, m, s)
}
