package xyz.botolog.ghostify.car

import android.content.ComponentName
import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import timber.log.Timber

class GhostifyCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        Timber.i("GhostifyCarAppService.onCreateSession")
        return GhostifyCarSession()
    }
}

class GhostifyCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        Timber.i("GhostifyCarSession.onCreateScreen")
        return GhostifyLyricsScreen(getCarContext())
    }
}

class GhostifyLyricsScreen(
    carContext: androidx.car.app.CarContext,
) : Screen(carContext) {

    private var mediaController: MediaController? = null
    private var currentLyrics: String = ""
    private var currentTitle: String = "No track"
    private var lyricsLines: List<String> = emptyList()
    private var currentLineIndex: Int = 0

    private val metadataListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            Timber.i("GhostifyLyricsScreen: onMediaMetadataChanged")
            readMetadataFromCurrentItem()
            invalidate()
        }
    }

    init {
        connectToMediaSession()
    }

    private fun connectToMediaSession() {
        val sessionToken = SessionToken(
            carContext,
            ComponentName(
                carContext,
                xyz.botolog.ghostify.background.PlaybackService::class.java,
            ),
        )
        val future: ListenableFuture<MediaController> = MediaController.Builder(carContext, sessionToken)
            .buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                mediaController = controller
                controller.addListener(metadataListener)
                Timber.i("GhostifyLyricsScreen: MediaController connected, listener registered")
                readMetadataFromCurrentItem()
                invalidate()
            } catch (t: Throwable) {
                Timber.e(t, "GhostifyLyricsScreen: MediaController connection failed")
            }
        }, { it.run() })
    }

    private fun readMetadataFromCurrentItem() {
        val controller = mediaController ?: return
        val metadata = controller.mediaMetadata
        currentTitle = metadata?.title?.toString() ?: "No track"
        val extras = metadata?.extras
        currentLyrics = extras?.getString(EXTRA_LYRICS, "") ?: ""
        lyricsLines = currentLyrics.lines().filter { it.isNotBlank() }
        currentLineIndex = 0
        Timber.i("GhostifyLyricsScreen: loaded title=$currentTitle, lyrics lines=${lyricsLines.size}")
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        if (lyricsLines.isNotEmpty()) {
            val startIndex = maxOf(0, currentLineIndex - 2)
            val endIndex = minOf(lyricsLines.size, currentLineIndex + 3)

            for (i in startIndex until endIndex) {
                val line = lyricsLines[i]
                val isCurrentLine = i == currentLineIndex
                val displayLine = if (isCurrentLine) "$LYRICS_POINTER $line" else line

                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle(displayLine)
                        .setBrowsable(false)
                        .build(),
                )
            }
        } else {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(currentLyrics.ifBlank { "No lyrics available for this track." })
                    .setBrowsable(false)
                    .build(),
            )
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeader(
                Header.Builder()
                    .setTitle(currentTitle)
                    .build(),
            )
            .build()
    }

    companion object {
        private const val EXTRA_LYRICS = "xyz.botolog.ghostify.LYRICS"
        private const val LYRICS_POINTER = "\u25B6"
    }
}
