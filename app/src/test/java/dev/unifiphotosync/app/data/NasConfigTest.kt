package dev.unifiphotosync.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NasConfigTest {
    private fun cfg(host: String, share: String, destination: String = "") =
        NasConfig(host = host, share = share, username = "u", password = "p", destination = destination).normalized()

    @Test fun plainShareIsUnchanged() {
        val c = cfg("192.168.1.20", "Photos")
        assertEquals("192.168.1.20", c.host)
        assertEquals("Photos", c.share)
        assertEquals("", c.destination)
    }

    @Test fun uncPathInShareFieldIsSplit() {
        val c = cfg("", """\\192.168.1.20\Photos\Phone\Camera""")
        assertEquals("192.168.1.20", c.host)
        assertEquals("Photos", c.share)
        assertEquals("""Phone\Camera""", c.destination)
    }

    @Test fun smbUrlIsSplit() {
        val c = cfg("nas.local", "smb://nas.local/Photos/Phone")
        assertEquals("nas.local", c.host)
        assertEquals("Photos", c.share)
        assertEquals("Phone", c.destination)
    }

    @Test fun shareWithFolderKeepsExistingDestination() {
        val c = cfg("nas", "Photos/Other", destination = "Backups")
        assertEquals("Photos", c.share)
        assertEquals("Backups", c.destination)
    }

    @Test fun hostPrefixesAreStripped() {
        assertEquals("192.168.1.20", cfg("""\\192.168.1.20\""", "Photos").host)
        assertEquals("nas", cfg("smb://nas/", "Photos").host)
    }

    @Test fun hostRepeatedInShareFieldIsDropped() {
        val c = cfg("nas", """nas\Photos""")
        assertEquals("Photos", c.share)
        assertEquals("", c.destination)
    }
}
