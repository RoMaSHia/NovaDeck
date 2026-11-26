package io.novadeck.control.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.KeyValueRow
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
    val dramNs: Double,
    val score: Double,
    val secs: Double
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
    val scroll = rememberScrollState()
    val ultraLite = perfController.isUltraLite

    var runningCpu by remember { mutableStateOf(false) }
    var runningIo by remember { mutableStateOf(false) }
    var runningThrottle by remember { mutableStateOf(false) }

    var cpuSnap by remember { mutableStateOf<CpuBenchSnapshot?>(null) }
    var ioSnap by remember { mutableStateOf<StorageBenchSnapshot?>(null) }

    val throttlePerfRing = remember { FloatRingBuffer(40) }
    val throttleTempRing = remember { FloatRingBuffer(40) }
    var throttleTick by remember { mutableLongStateOf(0L) }
    var throttleSliceIdx by remember { mutableIntStateOf(0) }
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
        if (historyLog.size > 15) historyLog.removeAt(historyLog.lastIndex)
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
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 01 // NATIVE C++ CPU & MEMORY BENCHMARK
        DeckPanel(
            title = stringResource(R.string.bench_tag_cpu),
            ledColor = if (runningCpu) BrutalistColors.SignalAccent else BrutalistColors.StatusNominal
        ) {
            DeckButton(
                text = if (runningCpu) stringResource(R.string.stor_btn_scanning) else stringResource(R.string.bench_btn_cpu),
                onClick = {
                    if (runningCpu || runningThrottle) return@DeckButton
                    runningCpu = true
                    scope.launch {
                        val arr = withContext(Dispatchers.Default) {
                            NativeBridge.safeRunCpuBenchmark(telemetry.coreCount, 850)
                        }
                        if (arr.size >= 7) {
                            val snap = CpuBenchSnapshot(arr[0], arr[1], arr[2], arr[3], arr[4], arr[5], arr[6])
                            cpuSnap = snap
                            appendHistory(
                                String.format(
                                    Locale.US,
                                    "CPU %.0f pts · INT %.2f/%.2f · NEON %.2f/%.2f · DRAM %.1f ns",
                                    snap.score, snap.intSt, snap.intMt, snap.vecSt, snap.vecMt, snap.dramNs
                                )
                            )
                        }
                        runningCpu = false
                    }
                },
                primary = true,
                enabled = !runningCpu && !runningThrottle,
                modifier = Modifier.fillMaxWidth()
            )
            val c = cpuSnap
            if (c != null) {
                Spacer(modifier = Modifier.height(10.dp))
                KeyValueRow(
                    stringResource(R.string.bench_score),
                    String.format(Locale.US, "%.0f PTS", c.score),
                    valueColor = BrutalistColors.SignalAccent
                )
                KeyValueRow(stringResource(R.string.bench_int_st), String.format(Locale.US, "%.2f GOPS", c.intSt))
                KeyValueRow(stringResource(R.string.bench_int_mt), String.format(Locale.US, "%.2f GOPS", c.intMt))
                KeyValueRow(stringResource(R.string.bench_vec_st), String.format(Locale.US, "%.2f GFLOPS", c.vecSt))
                KeyValueRow(stringResource(R.string.bench_vec_mt), String.format(Locale.US, "%.2f GFLOPS", c.vecMt))
                KeyValueRow(stringResource(R.string.bench_dram_lat), String.format(Locale.US, "%.1f ns", c.dramNs))
            }
        }

        // 02 // STORAGE I/O BENCHMARK
        DeckPanel(
            title = stringResource(R.string.bench_tag_storage),
            ledColor = if (runningIo) BrutalistColors.SignalAccent else BrutalistColors.TitaniumTrace
        ) {
            DeckButton(
                text = if (runningIo) stringResource(R.string.stor_btn_scanning) else stringResource(R.string.bench_btn_io),
                onClick = {
                    if (runningIo || runningThrottle) return@DeckButton
                    runningIo = true
                    scope.launch {
                        val sizeMb = if (perfController.isLowRamHardware) 24 else 48
                        val arr = withContext(Dispatchers.IO) {
                            NativeBridge.safeRunStorageBench(context.cacheDir.absolutePath, sizeMb)
                        }
                        if (arr.size >= 4) {
                            val snap = StorageBenchSnapshot(arr[0], arr[1], arr[2], arr[3])
                            ioSnap = snap
                            appendHistory(
                                String.format(
                                    Locale.US,
                                    "IO W:%.1f MB/s · R:%.1f MB/s · 4K:%.0f IOPS",
                                    snap.seqWriteMbps, snap.seqReadMbps, snap.rand4kIops
                                )
                            )
                        }
                        runningIo = false
                    }
                },
                enabled = !runningIo && !runningThrottle,
                modifier = Modifier.fillMaxWidth()
            )
            val io = ioSnap
            if (io != null) {
                Spacer(modifier = Modifier.height(10.dp))
                KeyValueRow(stringResource(R.string.bench_io_seq_w), String.format(Locale.US, "%.1f MB/s", io.seqWriteMbps))
                KeyValueRow(stringResource(R.string.bench_io_seq_r), String.format(Locale.US, "%.1f MB/s", io.seqReadMbps))
                KeyValueRow(stringResource(R.string.bench_io_rand), String.format(Locale.US, "%.1f MB/s", io.rand4kMbps))
                KeyValueRow(stringResource(R.string.bench_io_iops), String.format(Locale.US, "%.0f IOPS", io.rand4kIops))
            }
        }

        // 03 // THERMAL THROTTLING STRESS TEST (30S)
        DeckPanel(
            title = stringResource(R.string.bench_tag_throttle),
            ledColor = if (runningThrottle) BrutalistColors.SignalAccent else BrutalistColors.StatusCaution
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DeckButton(
                    text = if (runningThrottle) {
                        stringResource(R.string.bench_btn_stop)
                    } else {
                        stringResource(R.string.bench_btn_throttle)
                    },
                    onClick = {
                        if (runningThrottle) {
                            throttleJob?.cancel()
                            throttleJob = null
                            runningThrottle = false
                        } else if (!runningCpu && !runningIo) {
                            throttlePerfRing.clear()
                            throttleTempRing.clear()
                            throttlePeakGops = 0.0
                            throttlePeakTemp = telemetry.cpuTempC
                            throttleStabilityPct = 100f
                            throttleSliceIdx = 0
                            runningThrottle = true
                            throttleJob = scope.launch {
                                try {
                                    val totalSlices = 40 // 40 * 750ms = 30s
                                    var minPerfPct = 100f
                                    var lastPerfPct = 100f
                                    for (s in 1..totalSlices) {
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
                                            "THROTTLE STAB %.1f%% (MIN %.1f%%) · PEAK %.1f°C · %.2f GOPS",
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
            }

            if (throttleSliceIdx > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bench_throttle_stab, throttleStabilityPct, throttlePeakTemp) +
                        " (${throttleSliceIdx}/40)",
                    style = DeckType.DataMono,
                    color = if (throttleStabilityPct >= 85f) BrutalistColors.StatusNominal else BrutalistColors.StatusCaution
                )
                Spacer(modifier = Modifier.height(6.dp))
                ZeroAllocLineChart(
                    primaryBuffer = throttlePerfRing,
                    secondaryBuffer = throttleTempRing,
                    tickVersion = throttleTick,
                    fixedMax = 105f,
                    height = 84.dp,
                    primaryColor = BrutalistColors.SignalAccent,
                    secondaryColor = BrutalistColors.TitaniumTrace,
                    isUltraLite = ultraLite
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("■ PERF % OF PEAK", style = DeckType.Caption, color = BrutalistColors.SignalAccent)
                    Text("■ TEMP °C", style = DeckType.Caption, color = BrutalistColors.TitaniumTrace)
                }
            }
        }

        // 04 // BENCHMARK HISTORY LOG
        DeckPanel(
            title = stringResource(R.string.bench_tag_history),
            ledColor = BrutalistColors.GraphiteFaint
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
        Spacer(modifier = Modifier.height(6.dp))
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
