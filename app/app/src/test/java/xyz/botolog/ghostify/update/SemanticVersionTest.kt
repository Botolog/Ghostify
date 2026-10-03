package xyz.botolog.ghostify.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun parse_trailingLetterExtraSegment() {
        assertEquals(listOf(0L, 4L, 10L, 1L), SemanticVersion.parse("0.4.10a")!!.numbers)
        assertEquals(listOf(0L, 4L, 10L, 2L), SemanticVersion.parse("0.4.10b")!!.numbers)
        assertEquals(listOf(0L, 4L, 10L, 26L), SemanticVersion.parse("0.4.10z")!!.numbers)
        assertEquals(emptyList<String>(), SemanticVersion.parse("0.4.10a")!!.prerelease)
    }

    @Test
    fun compare_trailingLetterOrdering() {
        assertTrue(SemanticVersion.parse("0.4.10a")!! > SemanticVersion.parse("0.4.10")!!)
        assertTrue(SemanticVersion.parse("0.4.10b")!! > SemanticVersion.parse("0.4.10a")!!)
        assertTrue(SemanticVersion.parse("0.4.11")!! > SemanticVersion.parse("0.4.10b")!!)
        assertTrue(SemanticVersion.parse("0.4.10")!! < SemanticVersion.parse("0.4.10a")!!)
        assertTrue(SemanticVersion.parse("0.4.10a")!! < SemanticVersion.parse("0.4.10b")!!)
        assertTrue(SemanticVersion.parse("0.4.10b")!! < SemanticVersion.parse("0.4.11")!!)
    }

    @Test
    fun compare_trailingLetterUppercaseAndVariants() {
        assertEquals(0, SemanticVersion.parse("0.4.10a")!!.compareTo(SemanticVersion.parse("0.4.10A")!!))
        assertEquals(0, SemanticVersion.parse("0.4.10B")!!.compareTo(SemanticVersion.parse("0.4.10b")!!))
        assertTrue(SemanticVersion.parse("0.4.10A")!! < SemanticVersion.parse("0.4.10B")!!)
        assertTrue(SemanticVersion.parse("0.4.10a")!! < SemanticVersion.parse("0.4.10z")!!)
        assertTrue(SemanticVersion.parse("0.4.10Z")!! > SemanticVersion.parse("0.4.10a")!!)
        assertTrue(SemanticVersion.parse("v0.4.10a")!! > SemanticVersion.parse("0.4.10")!!)
    }

    @Test
    fun compare_equalAndNumericEdge() {
        assertEquals(0, SemanticVersion.parse("0.4.8")!!.compareTo(SemanticVersion.parse("0.4.8")!!))
        assertTrue(SemanticVersion.parse("0.4.9")!! < SemanticVersion.parse("0.4.10")!!)
        assertTrue(SemanticVersion.parse("0.4.10")!! > SemanticVersion.parse("0.4.9")!!)
        assertFalse(SemanticVersion.parse("0.4.8")!! > SemanticVersion.parse("0.4.8")!!)
    }

    @Test
    fun parse_trailingLetterMalformed() {
        assertNull(SemanticVersion.parse("0.4.10ab"))
        assertNull(SemanticVersion.parse("0.4.10aa"))
        assertNull(SemanticVersion.parse("1.2.3.4.5.x"))
        assertNull(SemanticVersion.parse("nightly"))
        assertNull(SemanticVersion.parse("unknown"))
        assertNull(SemanticVersion.parse("1.2.x"))
    }

    @Test
    fun compare_trailingLetterWithPrereleaseAndBuild() {
        val withPrerelease = SemanticVersion.parse("0.4.10a-beta")!!
        assertEquals(listOf(0L, 4L, 10L, 1L), withPrerelease.numbers)
        assertEquals(listOf("beta"), withPrerelease.prerelease)
        val withBuild = SemanticVersion.parse("0.4.10a+build.1")!!
        assertEquals(listOf(0L, 4L, 10L, 1L), withBuild.numbers)
        assertEquals(emptyList<String>(), withBuild.prerelease)
        assertEquals(0, withBuild.compareTo(SemanticVersion.parse("0.4.10a")!!))
        assertTrue(SemanticVersion.parse("0.4.10a-beta")!! < SemanticVersion.parse("0.4.10a")!!)
        assertTrue(SemanticVersion.parse("0.4.10a-beta")!! > SemanticVersion.parse("0.4.10")!!)
        assertTrue(SemanticVersion.parse("0.4.10b-rc.1")!! > SemanticVersion.parse("0.4.10a")!!)
    }
}
