package io.novadeck.control.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.novadeck.control.R
import io.novadeck.control.core.FloatRingBuffer
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.ui.components.DeckButton
import io.novadeck.control.ui.components.DeckChip
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.SegmentedBarMeter
import io.novadeck.control.ui.components.ZeroAllocLineChart
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private data class TouchPoint(val x: Float, val y: Float, val pressure: Float)
private data class CameraHardwareInfo(
    val id: String,
    val facing: String,
    val megapixels: String,
    val sensorSizeMm: String,
    val apertures: String,
    val focalLengths: String,
    val capabilities: String
)
private data class SensorDescriptor(
    val name: String,
    val vendor: String,
    val powerMa: Float,
    val maxRange: Float
)

@Composable
fun HardwareTesterScreen(
    perfController: PerformanceTierController,
    onSwipeLockChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ultraLite = perfController.isUltraLite

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val activeTab = pagerState.currentPage

    // Fullscreen display overlays
    var showDeadPixelTest by remember { mutableStateOf(false) }
    var deadPixelColorIdx by remember { mutableIntStateOf(0) }
    var showGradientTest by remember { mutableStateOf(false) }
    var showMotionTest by remember { mutableStateOf(false) }
    var showMultiTouchTest by remember { mutableStateOf(false) }

    val anyFullscreenOpen = showDeadPixelTest || showGradientTest || showMotionTest || showMultiTouchTest
    LaunchedEffect(anyFullscreenOpen) {
        onSwipeLockChange(anyFullscreenOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onSwipeLockChange(false) }
    }

    // Audio synthesizer (0=L, 1=R, 2=Stereo, 3=Out-of-phase, 4=Sweep, 5=WhiteNoise)
    var tonePlaying by remember { mutableStateOf(false) }
    var toneFreqHz by remember { mutableFloatStateOf(440f) }
    var toneMode by remember { mutableIntStateOf(2) }
    var toneJob by remember { mutableStateOf<Job?>(null) }

    // Microphone visualizer
    var micRunning by remember { mutableStateOf(false) }
    var micDbFs by remember { mutableFloatStateOf(-90f) }
    var micTick by remember { mutableLongStateOf(0L) }
    val micRing = remember { FloatRingBuffer(60) }
    var micJob by remember { mutableStateOf<Job?>(null) }

    // Flashlight
    var torchOn by remember { mutableStateOf(false) }
    val torchCameraId = remember { findTorchCameraId(context) }

    // Key & Gamepad events log
    val keyLog = remember { mutableStateListOf<String>() }
    val keyFocusRequester = remember { FocusRequester() }

    // Camera2 & Sensor list
    val cameraList = remember { enumerateCameras(context) }
    val allSensors = remember {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sm?.getSensorList(Sensor.TYPE_ALL)?.map {
            SensorDescriptor(it.name, it.vendor, it.power, it.maximumRange)
        } ?: emptyList()
    }

    // Live 60Hz sensors
    val accelRing = remember { FloatRingBuffer(60) }
    val gyroRing = remember { FloatRingBuffer(60) }
    val magRing = remember { FloatRingBuffer(60) }
    val lightRing = remember { FloatRingBuffer(60) }
    val proxRing = remember { FloatRingBuffer(60) }
    val baroRing = remember { FloatRingBuffer(60) }

    var hasAccel by remember { mutableStateOf(false) }
    var hasGyro by remember { mutableStateOf(false) }
    var hasMag by remember { mutableStateOf(false) }
    var hasLight by remember { mutableStateOf(false) }
    var hasProx by remember { mutableStateOf(false) }
    var hasBaro by remember { mutableStateOf(false) }

    val accelVec = remember { FloatArray(3) }
    val gyroVec = remember { FloatArray(3) }
    val magVec = remember { FloatArray(3) }
    var lightLux by remember { mutableFloatStateOf(0f) }
    var proxCm by remember { mutableFloatStateOf(0f) }
    var baroHpa by remember { mutableFloatStateOf(0f) }
    var sensorTick by remember { mutableLongStateOf(0L) }

    fun stopTone() {
        toneJob?.cancel()
        toneJob = null
        tonePlaying = false
    }

    fun startTone(mode: Int) {
        stopTone()
        toneMode = mode
        tonePlaying = true
        toneJob = scope.launch(Dispatchers.IO) {
            val sr = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val frameCount = minBuf / 4
            val pcm = ShortArray(frameCount * 2)
            var phase = 0.0
            var sweepPos = 0.0
            var lfsr = 0xACE1U
            try {
                track.play()
                while (isActive) {
                    if (mode == 5) {
                        // White noise
                        for (i in 0 until frameCount) {
                            val bit = ((lfsr shr 0) xor (lfsr shr 2) xor (lfsr shr 3) xor (lfsr shr 5)) and 1U
                            lfsr = (lfsr shr 1) or (bit shl 15)
                            val sample = (((lfsr.toInt() and 0xFFFF) - 32768) / 4).toShort()
                            pcm[i * 2] = sample
                            pcm[i * 2 + 1] = sample
                        }
                    } else {
                        for (i in 0 until frameCount) {
                            val freq = if (mode == 4) {
                                // Logarithmic 20 Hz -> 16 kHz sweep over 4s
                                sweepPos = (sweepPos + 1.0 / (sr * 4.0)) % 1.0
                                20.0 * (800.0).pow(sweepPos)
                            } else {
                                toneFreqHz.toDouble()
                            }
                            val step = 2.0 * PI * freq / sr
                            val s = (sin(phase) * 16000.0).toInt().toShort()
                            phase += step
                            if (phase > 2.0 * PI) phase -= 2.0 * PI
                            pcm[i * 2] = if (mode == 1) 0 else s
                            pcm[i * 2 + 1] = when (mode) {
                                0 -> 0
                                3 -> (-s.toInt()).toShort() // Out-of-phase check
                                else -> s
                            }
                        }
                    }
                    track.write(pcm, 0, pcm.size)
                }
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun stopMic() {
        micJob?.cancel()
        micJob = null
        micRunning = false
    }

    fun startMic() {
        stopMic()
        micRunning = true
        micJob = scope.launch(Dispatchers.IO) {
            val sr = 44100
            val bufSize = AudioRecord.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sr,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
            } catch (_: SecurityException) {
                micRunning = false
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                micRunning = false
                return@launch
            }
            val samples = ShortArray(1024)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(samples, 0, samples.size)
                    if (read > 0) {
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val v = samples[i].toDouble()
                            sumSq += v * v
                        }
                        val rms = sqrt(sumSq / read)
                        val db = if (rms > 1.0) (20.0 * log10(rms / 32767.0)).toFloat() else -90f
                        val clampedDb = db.coerceIn(-90f, 0f)
                        withContext(Dispatchers.Main.immediate) {
                            micDbFs = clampedDb
                            micRing.push((clampedDb + 90f).coerceIn(0f, 90f))
                            micTick++
                        }
                    }
                }
            } finally {
                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMic()
    }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sAccel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sGyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val sMag = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val sLight = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
        val sProx = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val sBaro = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)

        hasAccel = sAccel != null
        hasGyro = sGyro != null
        hasMag = sMag != null
        hasLight = sLight != null
        hasProx = sProx != null
        hasBaro = sBaro != null

        var lastUiNotifyNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelVec[0] = event.values[0]
                        accelVec[1] = event.values[1]
                        accelVec[2] = event.values[2]
                        accelRing.push(sqrt(accelVec[0] * accelVec[0] + accelVec[1] * accelVec[1] + accelVec[2] * accelVec[2]))
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroVec[0] = event.values[0]
                        gyroVec[1] = event.values[1]
                        gyroVec[2] = event.values[2]
                        gyroRing.push(sqrt(gyroVec[0] * gyroVec[0] + gyroVec[1] * gyroVec[1] + gyroVec[2] * gyroVec[2]))
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magVec[0] = event.values[0]
                        magVec[1] = event.values[1]
                        magVec[2] = event.values[2]
                        magRing.push(sqrt(magVec[0] * magVec[0] + magVec[1] * magVec[1] + magVec[2] * magVec[2]))
                    }
                    Sensor.TYPE_LIGHT -> {
                        lightLux = event.values[0]
                        lightRing.push(lightLux)
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        proxCm = event.values[0]
                        proxRing.push(proxCm)
                    }
                    Sensor.TYPE_PRESSURE -> {
                        baroHpa = event.values[0]
                        baroRing.push(baroHpa)
                    }
                }
                val nowNs = event.timestamp
                if (nowNs - lastUiNotifyNs >= 16_000_000L) {
                    lastUiNotifyNs = nowNs
                    sensorTick++
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val delay = SensorManager.SENSOR_DELAY_GAME
        if (sAccel != null) sm.registerListener(listener, sAccel, delay)
        if (sGyro != null) sm.registerListener(listener, sGyro, delay)
        if (sMag != null) sm.registerListener(listener, sMag, delay)
        if (sLight != null) sm.registerListener(listener, sLight, delay)
        if (sProx != null) sm.registerListener(listener, sProx, delay)
        if (sBaro != null) sm.registerListener(listener, sBaro, delay)

        onDispose {
            sm?.unregisterListener(listener)
            stopTone()
            stopMic()
            if (torchOn && torchCameraId != null) {
                setTorch(context, torchCameraId, false)
            }
        }
    }

    // Fullscreen Overlay: Dead Pixel Color Cycler
    if (showDeadPixelTest) {
        val colors = remember {
            listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black, Color.Cyan, Color.Magenta, Color.Yellow)
        }
        val bg = colors[deadPixelColorIdx % colors.size]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { deadPixelColorIdx = (deadPixelColorIdx + 1) % colors.size },
                        onDoubleTap = { showDeadPixelTest = false }
                    )
                }
        ) {
            Text(
                text = stringResource(R.string.hw_dead_pixel_hint),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (bg == Color.White || bg == Color.Yellow || bg == Color.Cyan) Color.Black else Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        return
    }

    // Fullscreen Overlay: Color Gradient & OLED Black Crush Test
    if (showGradientTest) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { showGradientTest = false })
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val bands = listOf(Color.White, Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta, Color.Yellow)
                for (c in bands) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Brush.horizontalGradient(listOf(Color.Black, c)))
                    )
                }
                // OLED near-black crush blocks (1%..12% luminance)
                Row(modifier = Modifier.fillMaxWidth().weight(0.9f)) {
                    for (step in 1..8) {
                        val lum = (step * 0.015f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(Color(lum, lum, lum)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${(lum * 100).toInt()}%", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color.Gray)
                        }
                    }
                }
            }
            DeckButton(
                text = stringResource(R.string.hw_exit),
                onClick = { showGradientTest = false },
                primary = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            )
        }
        return
    }

    // Fullscreen Overlay: 120Hz Frame Pacing Motion Test
    if (showMotionTest) {
        var phase by remember { mutableFloatStateOf(0f) }
        var liveFps by remember { mutableFloatStateOf(60f) }
        LaunchedEffect(Unit) {
            var prevNs = 0L
            while (isActive) {
                withFrameNanos { ns ->
                    if (prevNs != 0L) {
                        val dt = (ns - prevNs) / 1e9f
                        if (dt > 0f) {
                            liveFps = 1f / dt
                            phase = (phase + dt * 0.65f) % 1f
                        }
                    }
                    prevNs = ns
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BrutalistColors.ObsidianBg)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val laneCount = 4
                val laneH = size.height / laneCount
                val barW = 48.dp.toPx()
                for (l in 0 until laneCount) {
                    val speedMul = l + 1
                    val p = (phase * speedMul) % 1f
                    val x = p * (size.width + barW) - barW
                    val y = l * laneH + laneH * 0.2f
                    drawRect(
                        color = if (l % 2 == 0) BrutalistColors.SignalAccent else BrutalistColors.BoneInk,
                        topLeft = Offset(x, y),
                        size = Size(barW, laneH * 0.6f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "MOTION TEST · %.1f FPS", liveFps),
                    style = DeckType.MetricMono,
                    color = BrutalistColors.StatusNominal
                )
                DeckButton(text = stringResource(R.string.hw_exit), onClick = { showMotionTest = false }, primary = true)
            }
        }
        return
    }

    // Fullscreen Overlay: Multi-Touch + Sampling Rate Hz Visualizer
    if (showMultiTouchTest) {
        val activePointers = remember { mutableStateMapOf<Long, TouchPoint>() }
        var touchHz by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BrutalistColors.ObsidianBg)
                .pointerInput(Unit) {
                    var lastTouchMs = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val nowMs = System.currentTimeMillis()
                            if (lastTouchMs > 0L) {
                                val dt = (nowMs - lastTouchMs).coerceAtLeast(1L)
                                if (dt < 200L) touchHz = 1000f / dt
                            }
                            lastTouchMs = nowMs
                            for (change in event.changes) {
                                if (change.pressed) {
                                    activePointers[change.id.value] = TouchPoint(
                                        change.position.x,
                                        change.position.y,
                                        change.pressure
                                    )
                                } else {
                                    activePointers.remove(change.id.value)
                                }
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for ((_, pt) in activePointers) {
                    drawLine(BrutalistColors.HairlineBorder, Offset(0f, pt.y), Offset(size.width, pt.y), 1.dp.toPx())
                    drawLine(BrutalistColors.HairlineBorder, Offset(pt.x, 0f), Offset(pt.x, size.height), 1.dp.toPx())
                    drawCircle(
                        color = BrutalistColors.SignalAccent,
                        radius = 34.dp.toPx(),
                        center = Offset(pt.x, pt.y),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(BrutalistColors.BoneInk, radius = 4.dp.toPx(), center = Offset(pt.x, pt.y))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TOUCHES: ${activePointers.size} · ${touchHz.toInt()} Hz",
                    style = DeckType.MetricMono,
                    color = BrutalistColors.BoneInk
                )
                DeckButton(text = stringResource(R.string.hw_exit), onClick = { showMultiTouchTest = false }, primary = true)
            }
        }
        return
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
            DeckChip(stringResource(R.string.hw_tab_display), activeTab == 0, { scope.launch { pagerState.animateScrollToPage(0) } })
            DeckChip(stringResource(R.string.hw_tab_audio), activeTab == 1, { scope.launch { pagerState.animateScrollToPage(1) } })
            DeckChip(stringResource(R.string.hw_tab_keys), activeTab == 2, { scope.launch { pagerState.animateScrollToPage(2) } })
            DeckChip(stringResource(R.string.hw_tab_sensors), activeTab == 3, { scope.launch { pagerState.animateScrollToPage(3) } })
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            userScrollEnabled = !anyFullscreenOpen,
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
                    // DISPLAY & TOUCH
                    DeckPanel(
                        title = stringResource(R.string.hw_tag_display),
                        ledColor = BrutalistColors.TitaniumTrace
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DeckButton(
                                    text = stringResource(R.string.hw_btn_dead_pixel),
                                    onClick = { deadPixelColorIdx = 0; showDeadPixelTest = true },
                                    modifier = Modifier.weight(1f)
                                )
                                DeckButton(
                                    text = stringResource(R.string.hw_btn_multitouch),
                                    onClick = { showMultiTouchTest = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DeckButton(
                                    text = stringResource(R.string.hw_btn_gradient),
                                    onClick = { showGradientTest = true },
                                    modifier = Modifier.weight(1f)
                                )
                                DeckButton(
                                    text = stringResource(R.string.hw_btn_motion),
                                    onClick = { showMotionTest = true },
                                    primary = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // AUDIO, PHASE, SWEEP, MIC, VIBRO, FLASHLIGHT
                    DeckPanel(
                        title = stringResource(R.string.hw_tag_audio),
                        ledColor = if (tonePlaying || micRunning || torchOn) BrutalistColors.SignalAccent else BrutalistColors.StatusNominal
                    ) {
                        Text(text = stringResource(R.string.hw_freq_fmt, toneFreqHz.toInt()), style = DeckType.DataMono)
                        Slider(
                            value = toneFreqHz,
                            onValueChange = { toneFreqHz = it },
                            valueRange = 80f..14000f,
                            colors = SliderDefaults.colors(
                                thumbColor = BrutalistColors.SignalAccent,
                                activeTrackColor = BrutalistColors.SignalAccent,
                                inactiveTrackColor = BrutalistColors.HairlineBorder
                            )
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            DeckChip(stringResource(R.string.hw_audio_left), tonePlaying && toneMode == 0, { startTone(0) }, modifier = Modifier.weight(1f))
                            DeckChip(stringResource(R.string.hw_audio_right), tonePlaying && toneMode == 1, { startTone(1) }, modifier = Modifier.weight(1f))
                            DeckChip(stringResource(R.string.hw_audio_stereo), tonePlaying && toneMode == 2, { startTone(2) }, modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            DeckChip(stringResource(R.string.hw_audio_phase), tonePlaying && toneMode == 3, { startTone(3) }, modifier = Modifier.weight(1f))
                            DeckChip(stringResource(R.string.hw_audio_sweep), tonePlaying && toneMode == 4, { startTone(4) }, modifier = Modifier.weight(1f))
                            DeckChip(stringResource(R.string.hw_audio_noise), tonePlaying && toneMode == 5, { startTone(5) }, modifier = Modifier.weight(1f))
                        }
                        if (tonePlaying) {
                            Spacer(modifier = Modifier.height(6.dp))
                            DeckButton(stringResource(R.string.hw_audio_stop), onClick = { stopTone() }, danger = true, modifier = Modifier.fillMaxWidth())
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.hw_mic_level, micDbFs),
                                style = DeckType.DataMono,
                                color = if (micRunning) BrutalistColors.SignalAccent else BrutalistColors.TungstenMute
                            )
                            DeckButton(
                                text = if (micRunning) stringResource(R.string.hw_mic_stop) else stringResource(R.string.hw_mic_start),
                                onClick = {
                                    if (micRunning) stopMic()
                                    else {
                                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        if (granted) startMic() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                primary = !micRunning
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        SegmentedBarMeter(fraction = ((micDbFs + 90f) / 90f).coerceIn(0f, 1f), segments = 24, height = 10.dp)
                        if (micRunning) {
                            Spacer(modifier = Modifier.height(6.dp))
                            ZeroAllocLineChart(primaryBuffer = micRing, tickVersion = micTick, fixedMax = 90f, height = 48.dp, isUltraLite = ultraLite)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DeckButton(stringResource(R.string.hw_vibrate_pulse), onClick = { triggerVibration(context, false) }, modifier = Modifier.weight(1f))
                            DeckButton(stringResource(R.string.hw_vibrate_pattern), onClick = { triggerVibration(context, true) }, modifier = Modifier.weight(1f))
                            DeckButton(
                                text = when {
                                    torchCameraId == null -> stringResource(R.string.hw_torch_na)
                                    torchOn -> stringResource(R.string.hw_torch_on)
                                    else -> stringResource(R.string.hw_torch_off)
                                },
                                onClick = {
                                    if (torchCameraId != null) {
                                        val next = !torchOn
                                        if (setTorch(context, torchCameraId, next)) torchOn = next
                                    }
                                },
                                primary = torchOn,
                                enabled = torchCameraId != null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                2 -> {
                    // KEYS & GAMEPAD TESTER
                    LaunchedEffect(Unit) {
                        try {
                            keyFocusRequester.requestFocus()
                        } catch (_: Throwable) {
                        }
                    }
                    DeckPanel(
                        title = stringResource(R.string.hw_tag_keys),
                        ledColor = BrutalistColors.SignalAccent,
                        modifier = Modifier
                            .focusRequester(keyFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { ev ->
                                val nativeEv = ev.nativeKeyEvent
                                if (nativeEv.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                                    false
                                } else {
                                    val act = if (ev.type == KeyEventType.KeyDown) "DOWN" else "UP"
                                    val name = android.view.KeyEvent.keyCodeToString(nativeEv.keyCode)
                                    keyLog.add(0, "[$act] $name (scan=${nativeEv.scanCode}, code=${ev.key.keyCode})")
                                    if (keyLog.size > 14) keyLog.removeAt(keyLog.lastIndex)
                                    true
                                }
                            }
                    ) {
                        Text(text = stringResource(R.string.hw_keys_hint), style = DeckType.Body)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (keyLog.isEmpty()) {
                            Text(text = stringResource(R.string.hw_keys_empty), style = DeckType.Caption)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (k in keyLog) {
                                    Text(
                                        text = k,
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

                else -> {
                    // CAMERAS & LIVE 60HZ SENSOR BUS
                    DeckPanel(
                        title = stringResource(R.string.hw_tag_cameras),
                        ledColor = BrutalistColors.TitaniumTrace
                    ) {
                        if (cameraList.isEmpty()) {
                            Text("No Camera2 modules reported.", style = DeckType.Body)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (cam in cameraList) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text("CAM ${cam.id} · ${cam.facing} · ${cam.megapixels}", style = DeckType.DataMono, color = BrutalistColors.SignalAccent)
                                        Text("SENSOR: ${cam.sensorSizeMm} · f/${cam.apertures} · ${cam.focalLengths}mm", style = DeckType.Caption, color = BrutalistColors.BoneInk)
                                        if (cam.capabilities.isNotBlank()) {
                                            Text("CAPS: ${cam.capabilities}", style = DeckType.Caption, color = BrutalistColors.TungstenMute)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DeckPanel(
                        title = stringResource(R.string.hw_tag_sensors),
                        ledColor = BrutalistColors.SignalAccent
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SensorGraphBlock(stringResource(R.string.hw_sensor_accel), hasAccel, String.format("X:%+.2f Y:%+.2f Z:%+.2f", accelVec[0], accelVec[1], accelVec[2]), accelRing, sensorTick, 25f, ultraLite)
                            SensorGraphBlock(stringResource(R.string.hw_sensor_gyro), hasGyro, String.format("X:%+.2f Y:%+.2f Z:%+.2f", gyroVec[0], gyroVec[1], gyroVec[2]), gyroRing, sensorTick, 10f, ultraLite)
                            SensorGraphBlock(stringResource(R.string.hw_sensor_mag), hasMag, String.format("X:%+.1f Y:%+.1f Z:%+.1f", magVec[0], magVec[1], magVec[2]), magRing, sensorTick, 120f, ultraLite)
                            SensorGraphBlock(stringResource(R.string.hw_sensor_light), hasLight, String.format("%.1f lx", lightLux), lightRing, sensorTick, null, ultraLite)
                            SensorGraphBlock(stringResource(R.string.hw_sensor_prox), hasProx, String.format("%.1f cm", proxCm), proxRing, sensorTick, 12f, ultraLite)
                            SensorGraphBlock(stringResource(R.string.hw_sensor_baro), hasBaro, String.format("%.1f hPa", baroHpa), baroRing, sensorTick, null, ultraLite)
                        }
                    }

                    DeckPanel(
                        title = "${stringResource(R.string.hw_tag_sensor_list)} (${allSensors.size})",
                        ledColor = BrutalistColors.GraphiteFaint
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (s in allSensors) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                        .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                        .padding(6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${s.name} (${s.vendor})",
                                        style = DeckType.Caption,
                                        color = BrutalistColors.BoneInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.2f mA", s.powerMa),
                                        style = DeckType.Caption,
                                        color = BrutalistColors.TungstenMute
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

@Composable
private fun SensorGraphBlock(
    label: String,
    available: Boolean,
    readout: String,
    buffer: FloatRingBuffer,
    tick: Long,
    fixedMax: Float?,
    ultraLite: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = DeckType.Caption, color = BrutalistColors.BoneInk)
            Text(
                text = if (available) readout else stringResource(R.string.hw_sensor_unavail),
                style = DeckType.Caption,
                color = if (available) BrutalistColors.SignalAccent else BrutalistColors.GraphiteFaint
            )
        }
        if (available) {
            ZeroAllocLineChart(primaryBuffer = buffer, tickVersion = tick, fixedMax = fixedMax, height = 46.dp, isUltraLite = ultraLite)
        }
    }
}

private fun enumerateCameras(context: Context): List<CameraHardwareInfo> {
    val out = mutableListOf<CameraHardwareInfo>()
    try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return out
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "REAR"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "EXT"
            }
            val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val mp = if (px != null) String.format(Locale.US, "%.1f MP", (px.width * px.height) / 1_000_000.0) else "—"
            val ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sz = if (ps != null) String.format(Locale.US, "%.2fx%.2fmm", ps.width, ps.height) else "—"
            val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString("/") { String.format(Locale.US, "%.1f", it) } ?: "—"
            val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { String.format(Locale.US, "%.1f", it) } ?: "—"
            val caps = mutableListOf<String>()
            val availCaps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (availCaps != null) {
                if (availCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) caps.add("RAW")
                if (availCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) caps.add("MANUAL")
            }
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (ois != null && ois.any { it != 0 }) caps.add("OIS")
            out.add(CameraHardwareInfo(id, facing, mp, sz, ap, fl, caps.joinToString(", ")))
        }
    } catch (_: Throwable) {
    }
    return out
}

@Suppress("DEPRECATION")
private fun triggerVibration(context: Context, pattern: Boolean) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    if (pattern) {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100, 80, 260), -1))
    } else {
        vibrator.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private fun findTorchCameraId(context: Context): String? {
    return try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            ) return id
        }
        cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Throwable) {
        null
    }
}

private fun setTorch(context: Context, cameraId: String, on: Boolean): Boolean {
    return try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        cm.setTorchMode(cameraId, on)
        true
    } catch (_: Throwable) {
        false
    }
}
