package com.ghostify.python

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before

/**
 * Base for the device tests (TEST_PLAN §3, T-022..T-029). The instrumentation
 * runner executes these in the app's main process; the interpreter itself runs
 * in the `:pythond` process, exactly as in production.
 */
abstract class PythonRuntimeInstrumentedTest {

    protected fun context(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUpRuntime() {
        PythonRuntime.init(context())
    }
}
