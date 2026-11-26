package io.novadeck.control.ui.screens

import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.R
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.TelemetryEngine
import io.novadeck.control.core.formatBytesCompact
import io.novadeck.control.core.formatSpeedCompact
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.KeyValueRow
import io.novadeck.control.ui.components.SegmentedBarMeter
import io.novadeck.control.ui.components.ZeroAllocLineChart
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlin.math.abs

@Composable
fun LiveMonitorScreen(
    telemetry: TelemetryEngine,
    perfController: PerformanceTierController
) {
    val tick = telemetry.tickVersion.longValue
    val scroll = rememberScrollState()
    val ultraLite = perfController.isUltraLite

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 01 // CPU CORES & CLOCKS
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
                height = 72.dp,
                isUltraLite = ultraLite
            )
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

        // 02 // BATTERY TELEMETRY
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
            KeyValueRow(stringResource(R.string.mon_batt_power), String.format("%.2f W", powerWatts))
            KeyValueRow(
                stringResource(R.string.mon_batt_temp),
                String.format("%.1f °C", telemetry.batteryTempC),
                valueColor = if (telemetry.batteryTempC > 42f) BrutalistColors.StatusCaution else BrutalistColors.BoneInk
            )
            KeyValueRow(stringResource(R.string.mon_batt_health), battHealthLabel)
            KeyValueRow(stringResource(R.string.mon_batt_tech), telemetry.batteryTech)
            Spacer(modifier = Modifier.height(6.dp))
            ZeroAllocLineChart(
                primaryBuffer = telemetry.battCurrentHistory,
                tickVersion = tick,
                fixedMax = null,
                height = 56.dp,
                primaryColor = BrutalistColors.StatusNominal,
                isUltraLite = ultraLite
            )
        }

        // 03 // MEMORY & ZRAM
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
            Spacer(modifier = Modifier.height(6.dp))
            ZeroAllocLineChart(
                primaryBuffer = telemetry.ramHistory,
                tickVersion = tick,
                fixedMax = 100f,
                height = 56.dp,
                primaryColor = BrutalistColors.TitaniumTrace,
                isUltraLite = ultraLite
            )
        }

        // 04 // NETWORK LINK
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
                height = 68.dp,
                primaryColor = BrutalistColors.SignalAccent,
                secondaryColor = BrutalistColors.TitaniumTrace,
                isUltraLite = ultraLite
            )
            Spacer(modifier = Modifier.height(6.dp))
            KeyValueRow(stringResource(R.string.mon_net_ip), telemetry.netLocalIp)
            KeyValueRow(stringResource(R.string.mon_net_type), telemetry.netTransport)
            if (telemetry.netTransport == "WI-FI") {
                KeyValueRow(stringResource(R.string.mon_net_rssi), "${telemetry.wifiRssiDbm} dBm")
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}
