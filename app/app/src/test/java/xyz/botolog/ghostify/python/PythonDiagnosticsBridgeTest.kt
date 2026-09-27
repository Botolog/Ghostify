package xyz.botolog.ghostify.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonDiagnosticsBridgeTest {

    @Test
    fun reportIsParsedIntoLibraries() {
        val report = parseReport(
            mapOf(
                "python_version" to "3.11.14",
                "implementation" to "CPython",
                "libraries" to listOf(
                    mapOf(
                        "name" to "spotdl",
                        "version" to "4.5.2",
                        "source" to "/data/spotdl/__init__.py",
                        "error" to null,
                    ),
                    mapOf(
                        "name" to "ghostify_dl",
                        "version" to "my libs",
                        "source" to "/app/ghostify_dl.py",
                        "error" to null,
                    ),
                ),
                "error" to null,
            )
        )

        assertEquals("3.11.14", report.pythonVersion)
        assertEquals("CPython", report.implementation)
        assertNull(report.error)
        assertFalse(report.isFailure)
        assertEquals(2, report.libraries.size)
        assertEquals("spotdl", report.libraries[0].name)
        assertEquals("4.5.2", report.libraries[0].version)
        assertEquals("/data/spotdl/__init__.py", report.libraries[0].source)
        assertTrue(report.libraries[0].isAvailable)
        assertEquals("my libs", report.libraries[1].version)
    }

    @Test
    fun missingModuleKeepsItsLabelAndError() {
        val libraries = parseLibraries(
            listOf(
                mapOf(
                    "name" to "rapidfuzz",
                    "version" to "not installed",
                    "source" to "",
                    "error" to "ModuleNotFoundError",
                ),
            )
        )

        assertEquals(1, libraries.size)
        assertEquals("not installed", libraries[0].version)
        assertEquals("", libraries[0].source)
        assertEquals("ModuleNotFoundError", libraries[0].error)
        assertFalse(libraries[0].isAvailable)
    }

    @Test
    fun missingFieldsFallBackToReadableDefaults() {
        val libraries = parseLibraries(
            listOf(
                mapOf("name" to "requests"),
                mapOf("version" to "2.32.3"),
                mapOf("name" to "  spotapi  ", "version" to "  1.2.8  "),
            )
        )

        assertEquals(2, libraries.size)
        assertEquals("requests", libraries[0].name)
        assertEquals(PythonDiagnosticsBridge.UNKNOWN_VERSION, libraries[0].version)
        assertEquals("", libraries[0].source)
        assertNull(libraries[0].error)
        assertEquals("spotapi", libraries[1].name)
        assertEquals("1.2.8", libraries[1].version)
    }

    @Test
    fun unexpectedPayloadsDegradeGracefully() {
        assertTrue(parseLibraries(null).isEmpty())
        assertTrue(parseLibraries("spotdl").isEmpty())
        assertTrue(parseLibraries(listOf("spotdl", 7)).isEmpty())

        val report = parseReport("not a dict")
        assertTrue(report.isFailure)
        assertEquals(PythonDiagnosticsBridge.UNEXPECTED_RESULT, report.error)
        assertTrue(report.libraries.isEmpty())
    }

    @Test
    fun emptyReportIsNotAFailure() {
        val report = parseReport(
            mapOf("python_version" to "  ", "libraries" to emptyList<Any>())
        )

        assertFalse(report.isFailure)
        assertNull(report.pythonVersion)
        assertTrue(report.libraries.isEmpty())
    }
}
