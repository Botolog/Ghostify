package xyz.botolog.ghostify.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.botolog.ghostify.python.PythonLibraryInfo

class PythonLibrariesSheetFormatTest {

    @Test
    fun runtimeLabelCombinesImplementationAndVersion() {
        assertEquals("CPython · 3.11.14", pythonRuntimeLabel("3.11.14", "CPython"))
    }

    @Test
    fun runtimeLabelFallsBackToTheBareVersion() {
        assertEquals("3.11.14", pythonRuntimeLabel("3.11.14", "  "))
        assertEquals("3.11.14", pythonRuntimeLabel("3.11.14", null))
    }

    @Test
    fun runtimeLabelIsAbsentWithoutAVersion() {
        assertNull(pythonRuntimeLabel(null, "CPython"))
        assertNull(pythonRuntimeLabel("   ", "CPython"))
    }

    @Test
    fun versionLabelIsTheReportedVersion() {
        val library = PythonLibraryInfo(name = "spotdl", version = "4.5.2", source = "/s/spotdl.py")

        assertEquals("4.5.2", libraryVersionLabel(library))
    }

    @Test
    fun versionLabelNamesTheImportError() {
        val library = PythonLibraryInfo(
            name = "ytmusicapi",
            version = "not installed",
            source = "",
            error = "ModuleNotFoundError",
        )

        assertEquals("not installed · ModuleNotFoundError", libraryVersionLabel(library))
    }

    @Test
    fun sourceLabelTrimsAndFallsBack() {
        assertEquals(
            "/app/ghostify_dl.py",
            librarySourceLabel(PythonLibraryInfo("ghostify_dl", "my libs", " /app/ghostify_dl.py ")),
        )
        assertEquals(
            "No file reported.",
            librarySourceLabel(PythonLibraryInfo("ghostify_dl", "my libs", "   ")),
        )
    }
}
