package xyz.botolog.ghostify.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun parse_semver() {
        val version = SemanticVersion.parse("1.2.3")!!
        assertEquals(listOf(1L, 2L, 3L), version.numbers)
        assertEquals(emptyList<String>(), version.prerelease)
    }

    @Test
    fun parse_vPrefixAndWhitespace() {
        val version = SemanticVersion.parse("  v0.4.8  ")!!
        assertEquals(listOf(0L, 4L, 8L), version.numbers)
    }

    @Test
    fun parse_singleAndDoubleSegment() {
        assertEquals(listOf(2L), SemanticVersion.parse("2")!!.numbers)
        assertEquals(listOf(0L, 4L), SemanticVersion.parse("0.4")!!.numbers)
    }

    @Test
    fun parse_prereleaseIdentifiers() {
        val version = SemanticVersion.parse("0.5.0-rc.1")!!
        assertEquals(listOf(0L, 5L, 0L), version.numbers)
        assertEquals(listOf("rc", "1"), version.prerelease)
    }

    @Test
    fun parse_buildMetadataIsDropped() {
        val version = SemanticVersion.parse("1.2.3-beta.2+build.99")!!
        assertEquals(listOf("beta", "2"), version.prerelease)
    }

    @Test
    fun parse_malformedOrUnknown_returnsNull() {
        assertNull(SemanticVersion.parse(null))
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("   "))
        assertNull(SemanticVersion.parse("nightly"))
        assertNull(SemanticVersion.parse("v"))
        assertNull(SemanticVersion.parse("1.2.x"))
        assertNull(SemanticVersion.parse("-1.2.3"))
        assertNull(SemanticVersion.parse("1.2.3-"))
        assertNull(SemanticVersion.parse("1.2.3-a..b"))
    }

    @Test
    fun compare_missingComponentsCountAsZero() {
        assertEquals(0, SemanticVersion.parse("1.0")!!.compareTo(SemanticVersion.parse("1.0.0")!!))
        assertEquals(0, SemanticVersion.parse("1")!!.compareTo(SemanticVersion.parse("1.0.0.0")!!))
    }

    @Test
    fun compare_numericNotLexicographic() {
        assertTrue(SemanticVersion.parse("0.4.10")!! > SemanticVersion.parse("0.4.9")!!)
        assertTrue(SemanticVersion.parse("0.10.0")!! > SemanticVersion.parse("0.9.0")!!)
    }

    @Test
    fun compare_releaseOutranksPrerelease() {
        assertTrue(SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("1.0.0-rc.1")!!)
        assertTrue(SemanticVersion.parse("1.0.0-rc.1")!! < SemanticVersion.parse("1.0.0")!!)
        assertEquals(
            0,
            SemanticVersion.parse("1.0.0+build.1")!!.compareTo(SemanticVersion.parse("1.0.0+build.2")!!),
        )
    }

    @Test
    fun compare_prereleaseOrdering() {
        assertTrue(SemanticVersion.parse("1.0.0-alpha")!! < SemanticVersion.parse("1.0.0-beta")!!)
        assertTrue(SemanticVersion.parse("1.0.0-beta")!! < SemanticVersion.parse("1.0.0-beta.2")!!)
        assertTrue(SemanticVersion.parse("1.0.0-rc.9")!! < SemanticVersion.parse("1.0.0-rc.10")!!)
        assertTrue(SemanticVersion.parse("1.0.0-1")!! < SemanticVersion.parse("1.0.0-alpha")!!)
    }

    @Test
    fun compare_prereleaseNumberBeatsMoreFields() {
        assertTrue(SemanticVersion.parse("1.0.0-beta")!! < SemanticVersion.parse("1.0.0-beta.1")!!)
    }
}
