package dev.unifiphotosync.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class RateMeterTest {
    private var now = 0L
    private val second = 1_000_000_000L
    private fun meter() = RateMeter(clock = { now })

    @Test fun firstSampleIsZero() {
        assertEquals(0, meter().update(1_000))
    }

    @Test fun steadyRate() {
        val m = meter()
        m.update(0)
        now += second
        assertEquals(10_000_000, m.update(10_000_000)) // 10 MB in 1 s
        now += second
        assertEquals(10_000_000, m.update(20_000_000)) // still 10 MB/s
    }

    @Test fun ignoresSamplesCloserThanTheInterval() {
        val m = meter()
        m.update(0)
        now += second
        m.update(1_000)
        now += 100_000_000 // 0.1 s later: too soon to resample
        assertEquals(1_000, m.update(999_999))
    }

    @Test fun smoothsASlowSample() {
        val m = meter()
        m.update(0)
        now += second
        m.update(10_000) // 10 KB/s
        now += second
        // A stalled second only pulls the rate down partway: 10_000 * 0.6.
        assertEquals(6_000, m.update(10_000))
    }
}
