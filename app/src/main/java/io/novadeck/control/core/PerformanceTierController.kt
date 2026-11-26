package io.novadeck.control.core

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PerformanceModePreference {
    AUTO,
    DECK_FULL,
    ULTRA_LITE
}

enum class EffectiveRenderTier {
    DECK_FULL,
    ULTRA_LITE
}

/**
 * Evaluates device RAM capacity (<= 3 GB), ActivityManager.isLowRamDevice,
 * and PowerManager.isPowerSaveMode to adapt rendering passes cleanly.
 */
class PerformanceTierController(private val context: Context) {
    private val prefs = context.getSharedPreferences("novadeck_prefs", Context.MODE_PRIVATE)

    var preference by mutableStateOf(loadPref())
        private set

    var effectiveTier by mutableStateOf(EffectiveRenderTier.DECK_FULL)
        private set

    var totalRamBytes: Long = 0L
        private set

    var isLowRamHardware: Boolean = false
        private set

    var isPowerSaveActive: Boolean = false
        private set

    init {
        refresh()
    }

    private fun loadPref(): PerformanceModePreference {
        val raw = prefs.getString("perf_mode", PerformanceModePreference.AUTO.name)
        return PerformanceModePreference.entries.firstOrNull { it.name == raw }
            ?: PerformanceModePreference.AUTO
    }

    fun setModePreference(mode: PerformanceModePreference) {
        preference = mode
        prefs.edit().putString("perf_mode", mode.name).apply()
        refresh()
    }

    fun refresh() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        totalRamBytes = memInfo.totalMem
        val is3GbOrLess = totalRamBytes in 1L..3_400_000_000L
        isLowRamHardware = (am?.isLowRamDevice == true) || is3GbOrLess
        isPowerSaveActive = pm?.isPowerSaveMode == true

        effectiveTier = when (preference) {
            PerformanceModePreference.DECK_FULL -> EffectiveRenderTier.DECK_FULL
            PerformanceModePreference.ULTRA_LITE -> EffectiveRenderTier.ULTRA_LITE
            PerformanceModePreference.AUTO -> {
                if (isLowRamHardware || isPowerSaveActive) {
                    EffectiveRenderTier.ULTRA_LITE
                } else {
                    EffectiveRenderTier.DECK_FULL
                }
            }
        }
    }

    val isUltraLite: Boolean
        get() = effectiveTier == EffectiveRenderTier.ULTRA_LITE
}
