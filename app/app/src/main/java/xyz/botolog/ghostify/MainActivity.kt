package xyz.botolog.ghostify

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import xyz.botolog.ghostify.ui.GhostifyApp
import xyz.botolog.ghostify.ui.GhostifyDependencies

/**
 * Launcher activity: the single Compose entry point.
 *
 * It pulls the app's [GhostifyDependencies] from the application container
 * (manual DI, PROJECT.md §7.1) and renders the [GhostifyApp] navigation tree.
 * The ViewModels are cleared when the activity is destroyed so no coroutine
 * outlives the UI it feeds.
 *
 * A media-notification tap arrives as the private open-player action, on this
 * activity's launch intent for a cold start and through [onNewIntent] while the app
 * is already up. Both paths only register a request on the process-wide
 * [OpenPlayerRequests] bus; the composition turns it into the full player overlay.
 */
class MainActivity : ComponentActivity() {

    private val container: GhostifyContainer
        get() = (application as GhostifyApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenPlayerRequests.Process.handle(intent?.action)
        setContent {
            GhostifyApp(
                deps = buildDependencies(),
                openPlayerRequest = OpenPlayerRequests.Process.requestCount,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OpenPlayerRequests.Process.handle(intent.action)
    }

    private fun buildDependencies(): GhostifyDependencies {
        val vms = container.viewModels
        return GhostifyDependencies(
            library = vms.library(),
            addPlaylist = vms.addPlaylist(),
            detailFor = vms::detailFor,
            player = vms.player(),
            settings = vms.settings(),
            search = vms.search(),
        )
    }

    override fun onDestroy() {
        container.onDestroy()
        super.onDestroy()
    }
}
