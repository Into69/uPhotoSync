package dev.unifiphotosync.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NasPathTest {
    @Test fun cleanNormalizesSeparators() {
        assertEquals("""PhotoSync\Pixel 9""", NasPath.clean("""/PhotoSync//Pixel 9\"""))
        assertEquals("", NasPath.clean(" / "))
    }

    @Test fun joinSkipsBlankParts() {
        assertEquals("""a\b\c""", NasPath.join("", "a/", "b", "", """\c"""))
    }

    @Test fun parentAndName() {
        assertEquals("""a\b""", NasPath.parent("""a\b\c.jpg"""))
        assertEquals("", NasPath.parent("a"))
        assertEquals("c.jpg", NasPath.name("""a\b\c.jpg"""))
    }

    @Test fun safeNameReplacesIllegalCharacters() {
        assertEquals("a_b_c.jpg", NasPath.safeName("a:b?c.jpg"))
        assertEquals("file", NasPath.safeName("..."))
    }
}
