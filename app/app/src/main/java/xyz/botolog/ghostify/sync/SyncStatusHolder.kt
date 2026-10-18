package xyz.botolog.ghostify.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The library's **global** sync state, as the home-screen "Library" widget reads it.
 *
 * Until now "syncing" existed only as a screen-local `MutableStateFlow` inside
 * `PlaylistDetailViewModel` and "last synced" only per playlist
 * (`PlaylistEntity.lastSyncedAt`), so nothing process-wide could tell a widget whether a
 * sync was running or when the library was last refreshed. This holder is that minimal
 * global: [SyncUseCase] flips it at the start/end of every `syncPlaylist` call and stamps
 * the finish time.
 *
 * **Live only.** Like the sleep timer, [state] is in-memory: a cold widget process reads
 * the *persisted* timestamp instead
 * ([xyz.botolog.ghostify.data.repo.SettingsRepository.getLastSyncedAt]), and
 * [WidgetUpdateCoordinator] seeds this holder from it when the UI process starts, so the
 * two agree for as long as the process lives.
 */
data class SyncStatus(
    /** `true` while at least one playlist sync is in flight. */
    val isSyncing: Boolean = false,
    /** Epoch millis of the last successful sync in this process, or `null` if unknown. */
    val lastSyncedAtMs: Long? = null,
)

/**
 * Process-wide holder for [SyncStatus]. Guarded because [SyncUseCase] runs its syncs off
 * the main thread while the widget coordinator observes from the main dispatcher.
 */
object SyncStatusHolder {

    private val lock = Any()
    private var activeSyncs = 0

    private val _state = MutableStateFlow(SyncStatus())

    /** Observable sync status; the widget coordinator collects it. */
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    /** The current value without collecting. */
    val current: SyncStatus get() = _state.value

    /**
     * Marks a sync as started.
     *
     * Counted rather than boolean so two concurrent per-playlist syncs do not clear each
     * other's status when the first of them finishes.
     */
    fun beginSync() {
        synchronized(lock) {
            activeSyncs++
            _state.update { it.copy(isSyncing = true) }
        }
    }

    /** Marks one in-flight sync as finished, clearing [SyncStatus.isSyncing] at zero. */
    fun endSync() {
        synchronized(lock) {
            if (activeSyncs > 0) activeSyncs--
            if (activeSyncs == 0) _state.update { it.copy(isSyncing = false) }
        }
    }

    /** Records a successful sync finished at [atMs]; also clears [SyncStatus.isSyncing]. */
    fun markSynced(atMs: Long) {
        synchronized(lock) {
            _state.value = SyncStatus(isSyncing = false, lastSyncedAtMs = atMs)
        }
    }

    /**
     * Seeds the persisted last-synced timestamp at process start, leaving the live
     * [SyncStatus.isSyncing] flag untouched.
     */
    fun restore(lastSyncedAtMs: Long?) {
        _state.update { it.copy(lastSyncedAtMs = lastSyncedAtMs ?: it.lastSyncedAtMs) }
    }

    /** Drops all state (tests / lab). */
    fun reset() {
        synchronized(lock) {
            activeSyncs = 0
            _state.value = SyncStatus()
        }
    }
}
