package io.novadeck.control.core

/**
 * Fixed-capacity primitive ring buffer engineered for zero GC allocations during
 * 1 Hz – 60 Hz telemetry and sensor polling loops.
 */
class FloatRingBuffer(val capacity: Int = 60) {
    private val data = FloatArray(capacity)
    private var head = 0

    var size: Int = 0
        private set

    var latest: Float = 0f
        private set

    fun push(value: Float) {
        val clean = if (value.isFinite()) value else 0f
        data[head] = clean
        head = (head + 1) % capacity
        if (size < capacity) size++
        latest = clean
    }

    fun getOldestToNewest(index: Int): Float {
        if (index < 0 || index >= size) return 0f
        val start = if (size < capacity) 0 else head
        return data[(start + index) % capacity]
    }

    fun min(): Float {
        if (size == 0) return 0f
        var m = Float.MAX_VALUE
        for (i in 0 until size) {
            val v = getOldestToNewest(i)
            if (v < m) m = v
        }
        return m
    }

    fun max(): Float {
        if (size == 0) return 0f
        var m = -Float.MAX_VALUE
        for (i in 0 until size) {
            val v = getOldestToNewest(i)
            if (v > m) m = v
        }
        return m
    }

    fun clear() {
        head = 0
        size = 0
        latest = 0f
        data.fill(0f)
    }
}
