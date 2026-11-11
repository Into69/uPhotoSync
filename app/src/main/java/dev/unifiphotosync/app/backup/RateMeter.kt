package dev.unifiphotosync.app.backup

/**
 * Transfer rate in bytes per second, smoothed so the number shown doesn't jump around.
 * Feed it the running total of bytes moved; it samples at most every [sampleNanos].
 */
class RateMeter(
    private val clock: () -> Long = System::nanoTime,
    private val sampleNanos: Long = 500_000_000L,
) {
    private var lastTime = -1L
    private var lastTotal = 0L
    private var rate = 0.0

    /** Updates with the running byte total and returns the current rate (bytes/second). */
    fun update(totalBytes: Long): Long {
        val now = clock()
        if (lastTime < 0) {
            lastTime = now
            lastTotal = totalBytes
            return 0
        }
        val elapsed = now - lastTime
        if (elapsed >= sampleNanos) {
            val instant = (totalBytes - lastTotal).coerceAtLeast(0) * 1_000_000_000.0 / elapsed
            // Exponential smoothing: responsive, but one slow sample doesn't halve the number.
            rate = if (rate == 0.0) instant else rate * 0.6 + instant * 0.4
            lastTime = now
            lastTotal = totalBytes
        }
        return rate.toLong()
    }

    val bytesPerSecond: Long get() = rate.toLong()
}
