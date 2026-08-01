package com.ghostify.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

const val VALID_SPOTIFY_ID = "37i9dQZF1DXcBWIGoYBM5M"

// ---------------------------------------------------------------------------
// Builders shared by every test class and the fakes.
// ---------------------------------------------------------------------------

fun playlist(
    id: String,
    spotifyId: String = VALID_SPOTIFY_ID,
    name: String = "Playlist $id",
    status: PlaylistStatus = PlaylistStatus.NEW,
    createdAt: Long = 1_000L,
    trackCount: Int = 0,
) = PlaylistSummary(
    id = id,
    spotifyId = spotifyId,
    name = name,
    owner = "owner",
    coverUrl = null,
    trackCount = trackCount,
    status = status,
    createdAt = createdAt,
)

fun track(
    id: String,
    playlistId: String,
    title: String = "Track $id",
    status: SongStatus = SongStatus.PENDING,
    filePath: String? = null,
    position: Int = 0,
) = Track(
    id = id,
    playlistId = playlistId,
    spotifyId = "spotify-$id",
    title = title,
    artists = "Artist",
    durationMs = 180_000,
    filePath = filePath,
    status = status,
    position = position,
)

fun sampleMetadata(name: String = "Sample") = FetchedPlaylist(
    name = name,
    owner = "owner",
    coverUrl = null,
    trackCount = 2,
    tracks = listOf(
        FetchedTrack(0, "s1", "Song One", "Artist A", "Album A", 100_000L, null),
        FetchedTrack(1, "s2", "Song Two", "Artist B", "Album B", 200_000L, null),
    ),
)

/**
 * Shared JVM test harness. `Dispatchers.setMain` gives the VMs' viewModelScope
 * (which uses `Dispatchers.Main.immediate`) a TestDispatcher, so their
 * coroutines are driven deterministically by [runVmTest]'s scheduler.
 *
 * JUnit5 default lifecycle is per-method, so the dispatcher here is fresh for
 * every test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUpMain() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    open class FakePlaylistRepository : PlaylistRepository {
        val playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
        val trackStore = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
        val saved = mutableListOf<Pair<PlaylistSummary, List<Track>>>()
        var failObserve = false

        override fun observePlaylists(): Flow<List<PlaylistSummary>> {
            if (failObserve) return flow { throw IllegalStateException("boom") }
            return playlists
        }

        override fun observePlaylist(playlistId: String): Flow<PlaylistSummary?> {
            if (failObserve) return flow { throw IllegalStateException("boom") }
            return playlists.map { list -> list.firstOrNull { it.id == playlistId } }
        }

        override fun observeTracks(playlistId: String): Flow<List<Track>> {
            if (failObserve) return flow { throw IllegalStateException("boom") }
            return trackStore.map { it[playlistId].orEmpty() }
        }

        override suspend fun playlist(playlistId: String): PlaylistSummary? =
            playlists.value.firstOrNull { it.id == playlistId }

        override suspend fun tracksFor(playlistId: String): List<Track> =
            trackStore.value[playlistId].orEmpty()

        override suspend fun save(playlist: PlaylistSummary, trackList: List<Track>): String {
            saved += playlist to trackList
            playlists.value = playlists.value.filter { it.id != playlist.id } + playlist
            trackStore.value = trackStore.value + (playlist.id to trackList)
            return playlist.id
        }

        override suspend fun delete(playlistId: String) {
            playlists.value = playlists.value.filter { it.id != playlistId }
            trackStore.value = trackStore.value - playlistId
        }
    }

    class FakeDownloadController : DownloadController {
        val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        val downloadAllCalls = mutableListOf<String>()
        val retryCalls = mutableListOf<String>()
        val cancelCalls = mutableListOf<String>()
        val running = mutableSetOf<String>()
        var downloadAllResult: Boolean = true

        override fun downloadAll(playlistId: String): Boolean {
            downloadAllCalls += playlistId
            // Single-flight: Set.add returns false when the run is already active.
            return if (downloadAllResult) running.add(playlistId) else false
        }

        override fun retry(playlistId: String): Boolean {
            retryCalls += playlistId
            return downloadAll(playlistId)
        }

        override fun cancel(playlistId: String) {
            cancelCalls += playlistId
            running.remove(playlistId)
        }

        override fun isRunning(playlistId: String): Boolean = playlistId in running

        override fun observeProgress(playlistId: String): Flow<DownloadProgress?> =
            progress.map { it[playlistId] }
    }

    class FakeSyncer : PlaylistSyncer {
        val outcomes = ArrayDeque<SyncOutcome>()
        var defaultOutcome: SyncOutcome = SyncOutcome.Success(0, 0, 0)
        val calls = mutableListOf<String>()

        override suspend fun sync(playlistId: String): SyncOutcome {
            calls += playlistId
            return outcomes.removeFirstOrNull() ?: defaultOutcome
        }
    }

    class FakeUrlParser : PlaylistUrlParser {
        var result: UrlParseResult = UrlParseResult.Success(VALID_SPOTIFY_ID)
        override fun parse(input: String): UrlParseResult = result
    }

    class FakeFetcher : PlaylistFetcher {
        var result: PlaylistFetchResult = PlaylistFetchResult.Success(sampleMetadata())
        val calls = mutableListOf<String>()

        /** When set, [fetch] suspends until it is completed — exposes transient Fetching state. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(spotifyId: String): PlaylistFetchResult {
            calls += spotifyId
            gate?.await()
            return result
        }
    }

    class FakePlayerController : PlayerController {
        /** A minimal "queue loaded, paused" baseline so command-forwarding tests can observe Content. */
        val state = MutableStateFlow(
            PlayerPlaybackState(
                currentTrackId = "idle",
                currentTrackTitle = "Idle track",
                currentTrackArtist = "Someone",
                queueSize = 1,
            )
        )
        var loadResult: PlayerLoadResult = PlayerLoadResult.Ok
        var observers = 0
        val loaded = mutableListOf<Pair<String, Int>>()
        val toggles = mutableListOf<Unit>()

        override fun observeState(): Flow<PlayerPlaybackState> {
            observers++
            return state.map { it }.onCompletion { observers-- }
        }

        override suspend fun playPlaylist(playlistId: String, tracks: List<Track>): PlayerLoadResult {
            loaded += playlistId to tracks.size
            if (loadResult is PlayerLoadResult.Ok) {
                state.value = state.value.copy(
                    queueSize = tracks.size,
                    nothingToPlay = tracks.isEmpty(),
                    currentTrackId = tracks.firstOrNull()?.id,
                    currentTrackTitle = tracks.firstOrNull()?.title,
                    currentTrackArtist = tracks.firstOrNull()?.artists,
                )
            }
            return loadResult
        }

        override fun togglePlayPause() {
            toggles += Unit
            state.value = state.value.copy(isPlaying = !state.value.isPlaying)
        }

        override fun next() {
            state.value = state.value.copy(positionMs = state.value.positionMs + 1)
        }

        override fun previous() = Unit

        override fun seekTo(positionMs: Long) {
            state.value = state.value.copy(positionMs = positionMs)
        }

        override fun setShuffle(enabled: Boolean) {
            state.value = state.value.copy(shuffle = enabled)
        }

        override fun setRepeat(mode: RepeatMode) {
            state.value = state.value.copy(repeat = mode)
        }

        override fun setVolume(volume: Float) = Unit
    }

    class FakeFileStore : LocalFileStore {
        val files = mutableMapOf<String, String>() // path -> playlistId
        val deleted = mutableListOf<String>()

        override fun exists(path: String?): Boolean = path != null && files.containsKey(path)

        override suspend fun delete(path: String) {
            deleted += path
            files.remove(path)
        }

        override suspend fun deletePlaylistFiles(playlistId: String) {
            files.filterValues { it == playlistId }.keys.toList().forEach { p ->
                deleted += p
                files.remove(p)
            }
        }

        override suspend fun listFiles(): List<String> = files.keys.toList()
    }
}
