package xyz.botolog.ghostify.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.botolog.ghostify.ui.model.FullPlayerLayout

class FullPlayerLayoutTest {

    @Test
    fun defaultIsNormal() {
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue(null))
    }

    @Test
    fun entriesCoverTheThreeOptions() {
        assertEquals(
            listOf(
                FullPlayerLayout.NORMAL,
                FullPlayerLayout.COMPACT,
                FullPlayerLayout.SUPER_COMPACT,
            ),
            FullPlayerLayout.entries.toList(),
        )
    }

    @Test
    fun storageValuesAreSafeStrings() {
        assertEquals("normal", FullPlayerLayout.NORMAL.storageValue)
        assertEquals("compact", FullPlayerLayout.COMPACT.storageValue)
        assertEquals("super_compact", FullPlayerLayout.SUPER_COMPACT.storageValue)
    }

    @Test
    fun labelsMatchUserFacingNames() {
        assertEquals("Normal", FullPlayerLayout.NORMAL.label)
        assertEquals("Compact", FullPlayerLayout.COMPACT.label)
        assertEquals("Super compact", FullPlayerLayout.SUPER_COMPACT.label)
    }

    @Test
    fun everyStorageValueRoundTrips() {
        FullPlayerLayout.entries.forEach { layout ->
            assertEquals(layout, FullPlayerLayout.fromStorageValue(layout.storageValue))
        }
    }

    @Test
    fun legacyTrueMapsToSuperCompact() {
        assertEquals(FullPlayerLayout.SUPER_COMPACT, FullPlayerLayout.fromStorageValue("true"))
        assertEquals(FullPlayerLayout.SUPER_COMPACT, FullPlayerLayout.fromStorageValue("TRUE"))
        assertEquals(FullPlayerLayout.SUPER_COMPACT, FullPlayerLayout.fromStorageValue("1"))
        assertEquals(FullPlayerLayout.SUPER_COMPACT, FullPlayerLayout.fromStorageValue("yes"))
    }

    @Test
    fun legacyFalseMapsToNormal() {
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue("false"))
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue("FALSE"))
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue("0"))
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue("no"))
    }

    @Test
    fun unknownValueFallsBackToNormal() {
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue("not-a-layout"))
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue(""))
        assertEquals(FullPlayerLayout.NORMAL, FullPlayerLayout.fromStorageValue("  "))
    }

    @Test
    fun storedValueIsCaseAndWhitespaceInsensitive() {
        assertEquals(FullPlayerLayout.SUPER_COMPACT, FullPlayerLayout.fromStorageValue("Super_Compact"))
        assertEquals(FullPlayerLayout.COMPACT, FullPlayerLayout.fromStorageValue("  compact "))
    }
}
