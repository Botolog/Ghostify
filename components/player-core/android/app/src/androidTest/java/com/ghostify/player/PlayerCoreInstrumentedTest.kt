package com.ghostify.player

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ghostify.player.core.PlayerUiState
import com.ghostify.player.core.Song
import com.ghostify.player.core.SongStatus
import java.io.File
import org.junit.After
import org.junit.Before

/**
 * Shared scaffolding for the player-core instrumentation tests (T-079..T-092).
 *
 * All `ExoPlayer`/`PlayerController` access must happen on the main thread (the player's
 * application thread), so controller calls are wrapped in `runOnMain`. State assertions poll
 * the exposed `StateFlow`, which is safe from any thread.
 */
abstract class PlayerCoreInstrumentedTest {

    protected lateinit var context: Context
    protected var controller: PlayerController? = null

    @Before
    fun setUpBase() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDownBase() {
        runOnMain {
            controller?.release()
            controller = null
        }
    }

    protected fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    protected fun createController() {
        runOnMain {
            controller = PlayerController.create(context, sessionActivityClass = null)
        }
    }

    protected fun play(songs: List<Song>, startSongId: String? = null) {
        controller?.playPlaylist(songs, startSongId)
    }

    protected fun state(): PlayerUiState = controller?.state?.value ?: PlayerUiState()

    /** Polls [state] until [condition] holds or the timeout elapses (then fails). */
    protected fun awaitState(
        timeoutMs: Long = 15_000,
        condition: (PlayerUiState) -> Boolean,
    ): PlayerUiState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = state()
        while (SystemClock.uptimeMillis() < deadline) {
            last = state()
            if (condition(last)) return last
            SystemClock.sleep(50)
        }
        throw AssertionError("awaitState timed out after ${timeoutMs}ms; last=$last")
    }

    protected fun downloadedSong(
        id: String,
        file: File,
        title: String = "Title-$id",
        artists: String = "Artist $id",
        album: String = "Album $id",
    ) = Song(
        id = id,
        title = title,
        artists = artists,
        album = album,
        durationMs = null,
        filePath = file.absolutePath,
        status = SongStatus.DOWNLOADED,
    )

    protected fun nonDownloadedSong(id: String, file: File? = null, status: SongStatus = SongStatus.PENDING) = Song(
        id = id,
        title = "Title-$id",
        artists = "Artist $id",
        album = "Album $id",
        durationMs = null,
        filePath = file?.absolutePath,
        status = status,
    )
}
