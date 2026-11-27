package io.novadeck.control.core

import androidx.annotation.Keep

@Keep
object NativeBridge {
    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    init {
        isNativeLoaded = try {
            System.loadLibrary("novadeck_native")
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun nativeGetCpuCoreCount(): Int
    external fun nativeReadCpuMetrics(outMetrics: FloatArray): Int
    external fun nativeReadMemAndThermal(outValues: LongArray): Int
    external fun nativeReadSystemStats(outStats: DoubleArray): Int
    external fun nativeReadActiveSockets(): String
    external fun nativeMeasureTcpPing(host: String, port: Int, count: Int): DoubleArray
    external fun nativeScanStorage(rootPath: String, threadCount: Int): String
    external fun nativeCleanTempFiles(paths: Array<String>): Long
    external fun nativeRunCpuBenchmark(threads: Int, durationMs: Int): DoubleArray
    external fun nativeRunThrottleSlice(threads: Int, sliceMs: Int): Double
    external fun nativeRunStorageBench(dirPath: String, sizeMb: Int): DoubleArray
    external fun nativeCheckRootIndicators(): Int

    fun safeCoreCount(): Int {
        if (!isNativeLoaded) return Runtime.getRuntime().availableProcessors().coerceIn(1, 32)
        return try {
            nativeGetCpuCoreCount().coerceIn(1, 32)
        } catch (_: Throwable) {
            Runtime.getRuntime().availableProcessors().coerceIn(1, 32)
        }
    }

    fun safeReadCpuMetrics(outMetrics: FloatArray): Int {
        if (!isNativeLoaded) return 0
        return try {
            nativeReadCpuMetrics(outMetrics)
        } catch (_: Throwable) {
            0
        }
    }

    fun safeReadMemAndThermal(outValues: LongArray): Int {
        if (!isNativeLoaded) return 0
        return try {
            nativeReadMemAndThermal(outValues)
        } catch (_: Throwable) {
            0
        }
    }

    fun safeReadSystemStats(outStats: DoubleArray): Int {
        if (!isNativeLoaded) return 0
        return try {
            nativeReadSystemStats(outStats)
        } catch (_: Throwable) {
            0
        }
    }

    fun safeReadActiveSockets(): String {
        if (!isNativeLoaded) return "RESTRICTED"
        return try {
            nativeReadActiveSockets()
        } catch (_: Throwable) {
            "RESTRICTED"
        }
    }

    fun safeMeasureTcpPing(host: String, port: Int, count: Int = 4): DoubleArray {
        if (!isNativeLoaded) return doubleArrayOf(-1.0, -1.0, -1.0)
        return try {
            nativeMeasureTcpPing(host, port, count)
        } catch (_: Throwable) {
            doubleArrayOf(-1.0, -1.0, -1.0)
        }
    }

    fun safeScanStorage(rootPath: String, threadCount: Int): String {
        if (!isNativeLoaded) return ""
        return try {
            nativeScanStorage(rootPath, threadCount)
        } catch (_: Throwable) {
            ""
        }
    }

    fun safeCleanTempFiles(paths: Array<String>): Long {
        if (!isNativeLoaded || paths.isEmpty()) return 0L
        return try {
            nativeCleanTempFiles(paths)
        } catch (_: Throwable) {
            0L
        }
    }

    fun safeRunCpuBenchmark(threads: Int, durationMs: Int): DoubleArray {
        if (!isNativeLoaded) return DoubleArray(13)
        return try {
            nativeRunCpuBenchmark(threads, durationMs)
        } catch (_: Throwable) {
            DoubleArray(13)
        }
    }

    fun safeRunThrottleSlice(threads: Int, sliceMs: Int): Double {
        if (!isNativeLoaded) return 0.0
        return try {
            nativeRunThrottleSlice(threads, sliceMs)
        } catch (_: Throwable) {
            0.0
        }
    }

    fun safeRunStorageBench(dirPath: String, sizeMb: Int): DoubleArray {
        if (!isNativeLoaded) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return try {
            nativeRunStorageBench(dirPath, sizeMb)
        } catch (_: Throwable) {
            doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
    }

    fun safeCheckRootIndicators(): Int {
        if (!isNativeLoaded) return 0
        return try {
            nativeCheckRootIndicators()
        } catch (_: Throwable) {
            0
        }
    }
}
