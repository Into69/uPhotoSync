package dev.uphotosync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    private fun cmp(a: String, b: String) = Updater.compareVersions(a, b)

    @Test fun numbersCompareAsNumbersNotText() {
        assertTrue(cmp("0.1.10", "0.1.9") > 0)
        assertTrue(cmp("0.2.0", "0.1.11") > 0)
        assertTrue(cmp("1.0", "0.9.9") > 0)
    }

    @Test fun equalAndMissingParts() {
        assertEquals(0, cmp("0.1.11", "0.1.11"))
        assertEquals(0, cmp("1.0", "1.0.0"))
        assertTrue(cmp("0.1.9", "0.1.10") < 0)
    }

    @Test fun preReleaseSuffixComesBeforeTheFinalVersion() {
        assertTrue(cmp("0.1.13", "0.1.13-beta") > 0)
        assertTrue(cmp("0.1.13-beta", "0.1.12") > 0)
        assertTrue(cmp("0.1.13-rc10", "0.1.13-rc2") > 0)
        assertTrue(cmp("0.1.13-rc1", "0.1.13-beta2") > 0)
        assertEquals(0, cmp("0.1.13-RC1", "0.1.13-rc1"))
    }
}
