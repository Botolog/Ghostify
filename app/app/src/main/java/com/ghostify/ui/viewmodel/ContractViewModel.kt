package com.ghostify.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Minimal app-side base for the UI-contract ViewModels.
 *
 * Mirrors the shape of `components/viewmodels`' JVM `ViewModel` (a
 * `viewModelScope`-like [scope] plus a [clear] hook), but the app does manual
 * DI, so instances are created by the composition root and cleared by
 * [GhostifyViewModels.clear] when the activity is destroyed.
 */
abstract class ContractViewModel {

    /** Backs every state subscription so [clear] cancels the whole chain. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Starts a coroutine bound to this VM's lifetime. */
    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch { block() }

    /** Cancels every coroutine started in this VM (called at teardown). */
    fun clear() {
        scope.cancel()
    }
}
