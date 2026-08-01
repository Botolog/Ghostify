package com.ghostify.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Minimal, JVM-testable stand-in for `androidx.lifecycle.ViewModel`.
 *
 * It mirrors the androidx contract exactly — a `viewModelScope` backed by
 * `Dispatchers.Main.immediate` plus a [clear] hook that cancels that scope and
 * calls [onCleared]. That is the whole lifecycle story:
 *
 *  - Survives configuration changes: the instance (and its scope) live as long
 *    as the owning `ViewModelStore`; rotation only recreates the UI, which
 *    re-collects the exposed `StateFlow` and re-renders from the cached value
 *    (T-113).
 *  - No leaks: every `StateFlow` collection the VMs start is bound to
 *    `viewModelScope`, so [clear] cancels every upstream collector (T-111).
 *
 * In the app this class is trivially replaced by `androidx.lifecycle.ViewModel`
 * (same shape); the VMs in this component are otherwise 100% portable.
 */
abstract class ViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val viewModelScope: CoroutineScope
        get() = scope

    /** Called once when the ViewModel is cleared / no longer owner-scoped. */
    protected open fun onCleared() {}

    /**
     * Ends the ViewModel's lifetime: cancels all coroutines started in
     * [viewModelScope] (stopping every state subscription) and invokes
     * [onCleared]. Mirrors `androidx.lifecycle.ViewModel.clear()`.
     */
    fun clear() {
        viewModelScope.cancel()
        onCleared()
    }
}
