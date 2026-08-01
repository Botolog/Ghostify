package com.ghostify.python

import android.os.Looper

/**
 * Enforces the bridge contract that Python work is never triggered from the
 * main thread (TEST_PLAN T-027). The suspend API already dispatches off the
 * main thread; [checkNotMain] is the belt-and-braces guard for callers that
 * use the blocking API directly.
 */
internal object ThreadGuard {

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    fun checkNotMain() {
        if (isMainThread()) {
            throw PythonError.MainThreadCall()
        }
    }
}
