package xyz.botolog.ghostify

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
 */
class MainActivity : ComponentActivity() {

    private val container: GhostifyContainer
        get() = (application as GhostifyApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GhostifyApp(deps = buildDependencies())
        }
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
