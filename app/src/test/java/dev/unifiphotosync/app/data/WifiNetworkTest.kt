package dev.unifiphotosync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkTest {
    @Test fun stripsAndroidQuotes() {
        assertEquals("Home Wi-Fi", WifiNetwork.normalize("\"Home Wi-Fi\""))
    }

    @Test fun hiddenNameIsNull() {
        assertNull(WifiNetwork.normalize("<unknown ssid>"))
        assertNull(WifiNetwork.normalize(null))
        assertNull(WifiNetwork.normalize("\"\""))
    }

    @Test fun matchesExactNamesOnly() {
        val allowed = setOf("Home", "Home 5G")
        assertTrue(WifiNetwork.matches("Home 5G", allowed))
        assertFalse(WifiNetwork.matches("home", allowed)) // Wi-Fi names are case-sensitive
        assertFalse(WifiNetwork.matches(null, allowed)) // unknown network never matches
    }
}
