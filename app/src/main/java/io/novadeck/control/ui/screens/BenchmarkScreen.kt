package io.novadeck.control.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.novadeck.control.R
import io.novadeck.control.core.FloatRingBuffer
import io.novadeck.control.core.NativeBridge
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.TelemetryEngine
import io.novadeck.control.ui.components.DeckButton
import io.novadeck.control.ui.components.DeckChip
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.KeyValueRow
import io.novadeck.control.ui.components.SegmentedBarMeter
import io.novadeck.control.ui.components.ZeroAllocLineChart
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CpuBenchSnapshot(
    val intSt: Double,
    val intMt: Double,
    val vecSt: Double,
    val vecMt: Double,
    val shaSt: Double,
    val shaMt: Double,
    val lzSt: Double,
    val lzMt: Double,
    val ramRead: Double,
    val ramWrite: Double,
    val ramCopy: Double,
    val dramNs: Double,
    val score: Double
)

private data class StorageBenchSnapshot(
    val seqWriteMbps: Double,
    val seqReadMbps: Double,
    val rand4kMbps: Double,
    val rand4kIops: Double
)

@Composable
fun BenchmarkScreen(
    telemetry: TelemetryEngine,
    perfController: PerformanceTierController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ultraLite = perfController.isUltraLite

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val activeTab = pagerState.currentPage
    var runningSuite by remember { mutableStateOf(false) }
    var runningThrottle by remember { mutableStateOf(false) }
    var stressDurationSec by remember { mutableIntStateOf(30) }

    var cpuSnap by remember { mutableStateOf<CpuBenchSnapshot?>(null) }
    var ioSnap by remember { mutableStateOf<StorageBenchSnapshot?>(null) }

    val throttlePerfRing = remember { FloatRingBuffer(80) }
    val throttleTempRing = remember { FloatRingBuffer(80) }
    var throttleTick by remember { mutableLongStateOf(0L) }
    var throttleSliceIdx by remember { mutableIntStateOf(0) }
    var throttleTotalSlices by remember { mutableIntStateOf(40) }
    var throttleStabilityPct by remember { mutableFloatStateOf(100f) }
    var throttlePeakTemp by remember { mutableFloatStateOf(0f) }
    var throttlePeakGops by remember { mutableDoubleStateOf(0.0) }
    var throttleJob by remember { mutableStateOf<Job?>(null) }

    val historyLog = remember {
        mutableStateListOf<String>().apply { addAll(loadHistory(context)) }
    }

    fun appendHistory(entry: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        val line = "[$ts] $entry"
        historyLog.add(0, line)
        if (historyLog.size > 20) historyLog.removeAt(historyLog.lastIndex)
        saveHistory(context, historyLog)
    }

    DisposableEffect(Unit) {
        onDispose {
            throttleJob?.cancel()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            DeckChip(stringResource(R.string.bench_tab_suite), activeTab == 0, { scope.launch { pagerState.animateScrollToPage(0) } })
            DeckChip(stringResource(R.string.bench_tab_stress), activeTab == 1, { scope.launch { pagerState.animateScrollToPage(1) } })
            DeckChip("${stringResource(R.string.bench_tab_history)} (${historyLog.size})", activeTab == 2, { scope.launch { pagerState.animateScrollToPage(2) } })
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (page) {
                0 -> {
                    DeckPanel(
                        title = stringResource(R.string.bench_tag_cpu),
                        ledColor = if (runningSuite) BrutalistColors.SignalAccent else BrutalistColors.StatusNominal
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DeckButton(
                                text = if (runningSuite) stringResource(R.string.stor_btn_scanning) else stringResource(R.string.bench_btn_full),
                                onClick = {
                                    if (runningSuite || runningThrottle) return@DeckButton
                                    runningSuite = true
                                    scope.launch {
                                        val sizeMb = if (perfController.isLowRamHardware) 24 else 48
                                        val (cArr, ioArr) = withContext(Dispatchers.Default) {
                                            val cpu = NativeBridge.safeRunCpuBenchmark(telemetry.coreCount, 420)
                                            val io = NativeBridge.safeRunStorageBench(context.cacheDir.absolutePath, sizeMb)
                                            Pair(cpu, io)
                                        }
                                        if (cArr.size >= 13) {
                                            val cs = CpuBenchSnapshot(
                                                cArr[0], cArr[1], cArr[2], cArr[3],
                                                cArr[4], cArr[5], cArr[6], cArr[7],
                                                cArr[8], cArr[9], cArr[10], cArr[11], cArr[12]
                                            )
                                            cpuSnap = cs
                                            if (ioArr.size >= 4) {
                                                val ios = StorageBenchSnapshot(ioArr[0], ioArr[1], ioArr[2], ioArr[3])
                                                ioSnap = ios
                                                appendHistory(
                                                    String.format(
                                                        Locale.US,
                                                        "SUITE %.0f pts · SHA %.0f/%.0f MB/s · RAM %.1f GB/s · IO %.0f/%.0f MB/s",
                                                        cs.score, cs.shaSt, cs.shaMt, cs.ramCopy, ios.seqWriteMbps, ios.seqReadMbps
                                                    )
                                                )
                                            }
                                        }
                                        runningSuite = false
                                    }
                                },
                                primary = true,
                                enabled = !runningSuite && !runningThrottle,
                                modifier = Modifier.weight(1f)
                            )
                            DeckButton(
                                text = stringResource(R.string.bench_btn_io),
                                onClick = {
                                    if (runningSuite || runningThrottle) return@DeckButton
                                    runningSuite = true
                                    scope.launch {
                                        val sizeMb = if (perfController.isLowRamHardware) 24 else 48
                                        val ioArr = withContext(Dispatchers.IO) {
                                            NativeBridge.safeRunStorageBench(context.cacheDir.absolutePath, sizeMb)
                                        }
                                        if (ioArr.size >= 4) {
                                            ioSnap = StorageBenchSnapshot(ioArr[0], ioArr[1], ioArr[2], ioArr[3])
                                        }
                                        runningSuite = false
                                    }
                                },
                                enabled = !runningSuite && !runningThrottle
                            )
                        }

                        val c = cpuSnap
                        if (c != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            KeyValueRow(
                                stringResource(R.string.bench_score),
                                String.format(Locale.US, "%.0f PTS", c.score),
                                valueColor = BrutalistColors.SignalAccent
                            )
                            KeyValueRow(stringResource(R.string.bench_int_st), String.format(Locale.US, "%.2f / %.2f GOPS", c.intSt, c.intMt))
                            KeyValueRow(stringResource(R.string.bench_vec_st), String.format(Locale.US, "%.2f / %.2f GFLOPS", c.vecSt, c.vecMt))
                            KeyValueRow("SHA-256 (1T / ALL)", String.format(Locale.US, "%.1f / %.1f MB/s", c.shaSt, c.shaMt))
                            KeyValueRow("LZ COMPRESS (1T / ALL)", String.format(Locale.US, "%.1f / %.1f MB/s", c.lzSt, c.lzMt))
                            KeyValueRow("RAM R / W / COPY", String.format(Locale.US, "%.1f / %.1f / %.1f GB/s", c.ramRead, c.ramWrite, c.ramCopy))
                            KeyValueRow(stringResource(R.string.bench_dram_lat), String.format(Locale.US, "%.1f ns", c.dramNs))

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = stringResource(R.string.bench_ref_compare), style = DeckType.IndexTag)
                            Spacer(modifier = Modifier.height(6.dp))
                            val refs = listOf(
                                "Snapdragon 8 Gen 3" to 4200.0,
                                "Snapdragon 8 Gen 2" to 3350.0,
                                "Snapdragon 7+ Gen 2" to 2450.0,
                                "Helio G99" to 1100.0,
                                "THIS DEVICE" to c.score
                            ).sortedByDescending { it.second }
                            val maxRef = refs.maxOf { it.second }.coerceAtLeast(1.0)
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                for ((name, pts) in refs) {
                                    val isMe = name == "THIS DEVICE"
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(
                                                text = name,
                                                style = DeckType.Caption,
                                                color = if (isMe) BrutalistColors.SignalAccent else BrutalistColors.BoneInk
                                            )
                                            Text(
                                                text = String.format(Locale.US, "%.0f", pts),
                                                style = DeckType.Caption,
                                                color = if (isMe) BrutalistColors.SignalAccent else BrutalistColors.TungstenMute
                                            )
                                        }
                                        SegmentedBarMeter(
                                            fraction = (pts / maxRef).toFloat(),
                                            segments = 20,
                                            height = 5.dp,
                                            activeColor = if (isMe) BrutalistColors.SignalAccent else BrutalistColors.TitaniumTrace
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val io = ioSnap
                    if (io != null) {
                        DeckPanel(
                            title = stringResource(R.string.bench_tag_storage),
                            ledColor = BrutalistColors.TitaniumTrace
                        ) {
                            KeyValueRow(stringResource(R.string.bench_io_seq_w), String.format(Locale.US, "%.1f MB/s", io.seqWriteMbps))
                            KeyValueRow(stringResource(R.string.bench_io_seq_r), String.format(Locale.US, "%.1f MB/s", io.seqReadMbps))
                            KeyValueRow(stringResource(R.string.bench_io_rand), String.format(Locale.US, "%.1f MB/s", io.rand4kMbps))
                            KeyValueRow(stringResource(R.string.bench_io_iops), String.format(Locale.US, "%.0f IOPS", io.rand4kIops))
                        }
                    }
                }

                1 -> {
                    DeckPanel(
                        title = stringResource(R.string.bench_tag_throttle),
                        ledColor = if (runningThrottle) BrutalistColors.SignalAccent else BrutalistColors.StatusCaution
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DeckChip("15s", stressDurationSec == 15, { if (!runningThrottle) stressDurationSec = 15 }, modifier = Modifier.weight(1f))
                            DeckChip("30s", stressDurationSec == 30, { if (!runningThrottle) stressDurationSec = 30 }, modifier = Modifier.weight(1f))
                            DeckChip("60s", stressDurationSec == 60, { if (!runningThrottle) stressDurationSec = 60 }, modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        DeckButton(
                            text = if (runningThrottle) {
                                stringResource(R.string.bench_btn_stop)
                            } else {
                                "${stringResource(R.string.bench_btn_throttle)} (${stressDurationSec}s)"
                            },
                            onClick = {
                                if (runningThrottle) {
                                    throttleJob?.cancel()
                                    throttleJob = null
                                    runningThrottle = false
                                } else if (!runningSuite) {
                                    throttlePerfRing.clear()
                                    throttleTempRing.clear()
                                    throttlePeakGops = 0.0
                                    throttlePeakTemp = telemetry.cpuTempC
                                    throttleStabilityPct = 100f
                                    throttleSliceIdx = 0
                                    val slices = ((stressDurationSec * 1000) / 750).coerceIn(10, 80)
                                    throttleTotalSlices = slices
                                    runningThrottle = true
                                    throttleJob = scope.launch {
                                        try {
                                            var minPerfPct = 100f
                                            var lastPerfPct = 100f
                                            for (s in 1..slices) {
                                                if (!isActive) break
                                                val gops = withContext(Dispatchers.Default) {
                                                    NativeBridge.safeRunThrottleSlice(telemetry.coreCount, 750)
                                                }
                                                if (gops > throttlePeakGops) throttlePeakGops = gops
                                                val pct = if (throttlePeakGops > 0.0) {
                                                    ((gops / throttlePeakGops) * 100.0).toFloat().coerceIn(0f, 100f)
                                                } else 100f
                                                lastPerfPct = pct
                                                if (pct < minPerfPct) minPerfPct = pct

                                                val temp = telemetry.cpuTempC
                                                if (temp > throttlePeakTemp) throttlePeakTemp = temp

                                                throttlePerfRing.push(pct)
                                                throttleTempRing.push(temp)
                                                throttleStabilityPct = lastPerfPct
                                                throttleSliceIdx = s
                                                throttleTick++
                                            }
                                            appendHistory(
                                                String.format(
                                                    Locale.US,
                                                    "THROTTLE (${stressDurationSec}s) STAB %.1f%% (MIN %.1f%%) · PEAK %.1f°C · %.2f GOPS",
                                                    lastPerfPct, minPerfPct, throttlePeakTemp, throttlePeakGops
                                                )
                                            )
                                        } finally {
                                            runningThrottle = false
                                        }
                                    }
                                }
                            },
                            primary = !runningThrottle,
                            danger = runningThrottle,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (throttleSliceIdx > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.bench_throttle_stab, throttleStabilityPct, throttlePeakTemp) +
                                    " ($throttleSliceIdx/$throttleTotalSlices)",
                                style = DeckType.DataMono,
                                color = if (throttleStabilityPct >= 85f) BrutalistColors.StatusNominal else BrutalistColors.StatusCaution
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            ZeroAllocLineChart(
                                primaryBuffer = throttlePerfRing,
                                secondaryBuffer = throttleTempRing,
                                tickVersion = throttleTick,
                                fixedMax = 105f,
                                height = 88.dp,
                                primaryColor = BrutalistColors.SignalAccent,
                                secondaryColor = BrutalistColors.TitaniumTrace,
                                isUltraLite = ultraLite
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("■ PERF % OF PEAK", style = DeckType.Caption, color = BrutalistColors.SignalAccent)
                                Text("■ TEMP °C", style = DeckType.Caption, color = BrutalistColors.TitaniumTrace)
                            }
                        }
                    }
                }

                else -> {
                    DeckPanel(
                        title = stringResource(R.string.bench_tag_history),
                        ledColor = BrutalistColors.GraphiteFaint,
                        headerAction = {
                            if (historyLog.isNotEmpty()) {
                                DeckChip(
                                    text = stringResource(R.string.bench_btn_clear),
                                    selected = false,
                                    onClick = {
                                        historyLog.clear()
                                        saveHistory(context, emptyList())
                                    }
                                )
                            }
                        }
                    ) {
                        if (historyLog.isEmpty()) {
                            Text(text = stringResource(R.string.bench_history_empty), style = DeckType.Body)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (item in historyLog) {
                                    Text(
                                        text = item,
                                        style = DeckType.Caption,
                                        color = BrutalistColors.BoneInk,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                            .padding(6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

private fun loadHistory(context: Context): List<String> {
    val prefs = context.getSharedPreferences("novadeck_bench", Context.MODE_PRIVATE)
    val raw = prefs.getString("log", "") ?: ""
    return raw.lines().filter { it.isNotBlank() }
}

private fun saveHistory(context: Context, items: List<String>) {
    val prefs = context.getSharedPreferences("novadeck_bench", Context.MODE_PRIVATE)
    prefs.edit().putString("log", items.joinToString("\n")).apply()
}
