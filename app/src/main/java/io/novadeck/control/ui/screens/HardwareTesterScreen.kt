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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private data class TouchPoint(val x: Float, val y: Float, val pressure: Float)

@Composable
fun HardwareTesterScreen(perfController: PerformanceTierController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val ultraLite = perfController.isUltraLite

    // Fullscreen display tests
    var showDeadPixelTest by remember { mutableStateOf(false) }
    var deadPixelColorIdx by remember { mutableIntStateOf(0) }
    var showMultiTouchTest by remember { mutableStateOf(false) }

    // Tone generator
    var tonePlaying by remember { mutableStateOf(false) }
    var toneFreqHz by remember { mutableFloatStateOf(440f) }
    var toneChannel by remember { mutableIntStateOf(2) } // 0=L, 1=R, 2=Stereo
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

    // Sensors (zero-allocation buffers)
    val accelRing = remember { FloatRingBuffer(60) }
    val gyroRing = remember { FloatRingBuffer(60) }
    val magRing = remember { FloatRingBuffer(60) }
    val lightRing = remember { FloatRingBuffer(60) }
    val proxRing = remember { FloatRingBuffer(60) }

    var hasAccel by remember { mutableStateOf(false) }
    var hasGyro by remember { mutableStateOf(false) }
    var hasMag by remember { mutableStateOf(false) }
    var hasLight by remember { mutableStateOf(false) }
    var hasProx by remember { mutableStateOf(false) }

    val accelVec = remember { FloatArray(3) }
    val gyroVec = remember { FloatArray(3) }
    val magVec = remember { FloatArray(3) }
    var lightLux by remember { mutableFloatStateOf(0f) }
    var proxCm by remember { mutableFloatStateOf(0f) }
    var sensorTick by remember { mutableLongStateOf(0L) }

    fun stopTone() {
        toneJob?.cancel()
        toneJob = null
        tonePlaying = false
    }

    fun startTone(channel: Int) {
        stopTone()
        toneChannel = channel
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
            try {
                track.play()
                while (isActive) {
                    val step = 2.0 * PI * toneFreqHz.toDouble() / sr
                    for (i in 0 until frameCount) {
                        val s = (sin(phase) * 16000.0).toInt().toShort()
                        phase += step
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                        pcm[i * 2] = if (toneChannel == 0 || toneChannel == 2) s else 0
                        pcm[i * 2 + 1] = if (toneChannel == 1 || toneChannel == 2) s else 0
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

    // Sensor subscription with cleanup
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sAccel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sGyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val sMag = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val sLight = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
        val sProx = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        hasAccel = sAccel != null
        hasGyro = sGyro != null
        hasMag = sMag != null
        hasLight = sLight != null
        hasProx = sProx != null

        var lastUiNotifyNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelVec[0] = event.values[0]
                        accelVec[1] = event.values[1]
                        accelVec[2] = event.values[2]
                        val mag = sqrt(accelVec[0] * accelVec[0] + accelVec[1] * accelVec[1] + accelVec[2] * accelVec[2])
                        accelRing.push(mag)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroVec[0] = event.values[0]
                        gyroVec[1] = event.values[1]
                        gyroVec[2] = event.values[2]
                        val mag = sqrt(gyroVec[0] * gyroVec[0] + gyroVec[1] * gyroVec[1] + gyroVec[2] * gyroVec[2])
                        gyroRing.push(mag)
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magVec[0] = event.values[0]
                        magVec[1] = event.values[1]
                        magVec[2] = event.values[2]
                        val mag = sqrt(magVec[0] * magVec[0] + magVec[1] * magVec[1] + magVec[2] * magVec[2])
                        magRing.push(mag)
                    }
                    Sensor.TYPE_LIGHT -> {
                        lightLux = event.values[0]
                        lightRing.push(lightLux)
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        proxCm = event.values[0]
                        proxRing.push(proxCm)
                    }
                }
                val nowNs = event.timestamp
                if (nowNs - lastUiNotifyNs >= 16_000_000L) { // ~60 Hz cap
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
            listOf(
                Color.Red,
                Color.Green,
                Color.Blue,
                Color.White,
                Color.Black,
                Color.Cyan,
                Color.Magenta,
                Color.Yellow
            )
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

    // Fullscreen Overlay: Multi-Touch Visualizer
    if (showMultiTouchTest) {
        val activePointers = remember { mutableStateMapOf<Long, TouchPoint>() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BrutalistColors.ObsidianBg)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
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
                    drawLine(
                        color = BrutalistColors.HairlineBorder,
                        start = Offset(0f, pt.y),
                        end = Offset(size.width, pt.y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = BrutalistColors.HairlineBorder,
                        start = Offset(pt.x, 0f),
                        end = Offset(pt.x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawCircle(
                        color = BrutalistColors.SignalAccent,
                        radius = 34.dp.toPx(),
                        center = Offset(pt.x, pt.y),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = BrutalistColors.BoneInk,
                        radius = 4.dp.toPx(),
                        center = Offset(pt.x, pt.y)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TOUCHES: ${activePointers.size} · ${stringResource(R.string.hw_multitouch_hint)}",
                    style = DeckType.Caption,
                    color = BrutalistColors.BoneInk,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                DeckButton(
                    text = stringResource(R.string.hw_exit),
                    onClick = { showMultiTouchTest = false },
                    primary = true
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 01 // DISPLAY & TOUCH
        DeckPanel(
            title = stringResource(R.string.hw_tag_display),
            ledColor = BrutalistColors.TitaniumTrace
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
        }

        // 02 // AUDIO, MICROPHONE, VIBRATOR & FLASHLIGHT
        DeckPanel(
            title = stringResource(R.string.hw_tag_audio),
            ledColor = if (tonePlaying || micRunning || torchOn) BrutalistColors.SignalAccent else BrutalistColors.StatusNominal
        ) {
            Text(
                text = stringResource(R.string.hw_freq_fmt, toneFreqHz.toInt()),
                style = DeckType.DataMono
            )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckChip(
                    text = stringResource(R.string.hw_audio_left),
                    selected = tonePlaying && toneChannel == 0,
                    onClick = { startTone(0) },
                    modifier = Modifier.weight(1f)
                )
                DeckChip(
                    text = stringResource(R.string.hw_audio_right),
                    selected = tonePlaying && toneChannel == 1,
                    onClick = { startTone(1) },
                    modifier = Modifier.weight(1f)
                )
                DeckChip(
                    text = stringResource(R.string.hw_audio_stereo),
                    selected = tonePlaying && toneChannel == 2,
                    onClick = { startTone(2) },
                    modifier = Modifier.weight(1f)
                )
                if (tonePlaying) {
                    DeckButton(
                        text = stringResource(R.string.hw_audio_stop),
                        onClick = { stopTone() },
                        danger = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Microphone Level Visualizer
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
                        if (micRunning) {
                            stopMic()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) startMic() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    primary = !micRunning
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            SegmentedBarMeter(
                fraction = ((micDbFs + 90f) / 90f).coerceIn(0f, 1f),
                segments = 24,
                height = 10.dp
            )
            if (micRunning) {
                Spacer(modifier = Modifier.height(6.dp))
                ZeroAllocLineChart(
                    primaryBuffer = micRing,
                    tickVersion = micTick,
                    fixedMax = 90f,
                    height = 48.dp,
                    isUltraLite = ultraLite
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Vibrator + Flashlight
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckButton(
                    text = stringResource(R.string.hw_vibrate_pulse),
                    onClick = { triggerVibration(context, false) },
                    modifier = Modifier.weight(1f)
                )
                DeckButton(
                    text = stringResource(R.string.hw_vibrate_pattern),
                    onClick = { triggerVibration(context, true) },
                    modifier = Modifier.weight(1f)
                )
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

        // 03 // LIVE 60HZ SENSOR BUS
        DeckPanel(
            title = stringResource(R.string.hw_tag_sensors),
            ledColor = BrutalistColors.SignalAccent
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SensorGraphBlock(
                    label = stringResource(R.string.hw_sensor_accel),
                    available = hasAccel,
                    readout = String.format("X:%+.2f  Y:%+.2f  Z:%+.2f", accelVec[0], accelVec[1], accelVec[2]),
                    buffer = accelRing,
                    tick = sensorTick,
                    fixedMax = 25f,
                    ultraLite = ultraLite
                )
                SensorGraphBlock(
                    label = stringResource(R.string.hw_sensor_gyro),
                    available = hasGyro,
                    readout = String.format("X:%+.2f  Y:%+.2f  Z:%+.2f", gyroVec[0], gyroVec[1], gyroVec[2]),
                    buffer = gyroRing,
                    tick = sensorTick,
                    fixedMax = 10f,
                    ultraLite = ultraLite
                )
                SensorGraphBlock(
                    label = stringResource(R.string.hw_sensor_mag),
                    available = hasMag,
                    readout = String.format("X:%+.1f  Y:%+.1f  Z:%+.1f", magVec[0], magVec[1], magVec[2]),
                    buffer = magRing,
                    tick = sensorTick,
                    fixedMax = 120f,
                    ultraLite = ultraLite
                )
                SensorGraphBlock(
                    label = stringResource(R.string.hw_sensor_light),
                    available = hasLight,
                    readout = String.format("%.1f lx", lightLux),
                    buffer = lightRing,
                    tick = sensorTick,
                    fixedMax = null,
                    ultraLite = ultraLite
                )
                SensorGraphBlock(
                    label = stringResource(R.string.hw_sensor_prox),
                    available = hasProx,
                    readout = String.format("%.1f cm", proxCm),
                    buffer = proxRing,
                    tick = sensorTick,
                    fixedMax = 12f,
                    ultraLite = ultraLite
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = DeckType.Caption, color = BrutalistColors.BoneInk)
            Text(
                text = if (available) readout else stringResource(R.string.hw_sensor_unavail),
                style = DeckType.Caption,
                color = if (available) BrutalistColors.SignalAccent else BrutalistColors.GraphiteFaint
            )
        }
        if (available) {
            ZeroAllocLineChart(
                primaryBuffer = buffer,
                tickVersion = tick,
                fixedMax = fixedMax,
                height = 50.dp,
                isUltraLite = ultraLite
            )
        }
    }
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
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100, 80, 260), -1)
        )
    } else {
        vibrator.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private fun findTorchCameraId(context: Context): String? {
    return try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            if (hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
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
