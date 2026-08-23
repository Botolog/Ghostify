package xyz.botolog.ghostify.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import xyz.botolog.ghostify.ui.add.AddPlaylistDialog
import xyz.botolog.ghostify.ui.contract.AddPlaylistContract
import xyz.botolog.ghostify.ui.contract.LibraryContract
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract
import xyz.botolog.ghostify.ui.contract.SettingsContract
import xyz.botolog.ghostify.ui.library.LibraryScreen
import xyz.botolog.ghostify.ui.player.MiniPlayer
import xyz.botolog.ghostify.ui.player.PlayerScreen
import xyz.botolog.ghostify.ui.playlist.PlaylistDetailScreen
import xyz.botolog.ghostify.ui.settings.SettingsScreen
import xyz.botolog.ghostify.ui.theme.GhostifyTheme

/**
 * Navigation route constants used by the [NavHost] inside [GhostifyApp].
 */
object Routes {
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val PLAYLIST = "playlist/{playlistId}"

    /**
     * Builds the route string for the playlist detail screen.
     *
     * @param playlistId the unique identifier of the playlist.
     * @return the full navigation route.
     */
    fun playlist(playlistId: String) = "playlist/$playlistId"
}

/**
 * Composition root of the UI component.
 *
 * All screen state comes from the [GhostifyDependencies] contracts (implemented by the
 * `viewmodels` component in the real app; by fakes in Compose UI tests), so this tree is
 * fully unit-testable without an app backend.
 *
 * @param deps the bundle of ViewModel contracts that drive every screen.
 * @param initialRoute route the NavHost should start on. The app root maps a "launched from
 *   notification" intent to [Routes.PLAYER] so the player renders on cold start (T-143).
 * @param modifier optional modifier applied to the root layout.
 */
@Composable
fun GhostifyApp(
    deps: GhostifyDependencies,
    initialRoute: String? = null,
    modifier: Modifier = Modifier,
) {
    GhostifyTheme {
        val navController = rememberNavController()
        val startDestination = initialRoute ?: Routes.LIBRARY
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val playerState by deps.player.state.collectAsState()

        Column(modifier = modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.weight(1f),
            ) {
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        contract = deps.library,
                        onOpenPlaylist = { id -> navController.navigate(Routes.playlist(id)) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        addDialog = { AddPlaylistDialog(contract = deps.addPlaylist) },
                    )
                }

                composable(
                    route = Routes.PLAYLIST,
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                ) { entry ->
                    val playlistId = entry.arguments?.getString("playlistId").orEmpty()
                    PlaylistDetailScreen(
                        contract = deps.detailFor(playlistId),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.PLAYER) {
                    PlayerScreen(
                        contract = deps.player,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        contract = deps.settings,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Spotify-style now-playing bar. Persistent across every screen except the full
            // player; hidden while nothing is loaded. Bottom padding keeps it above the
            // system navigation bar.
            if (currentRoute != Routes.PLAYER && !playerState.empty) {
                MiniPlayer(
                    contract = deps.player,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }
    }
}

/**
 * The bundle of ViewModel contracts the UI depends on. Supplied by the Android app's
 * composition root (Hilt) — or by fakes in tests.
 *
 * @property library contract driving the library screen playlist list.
 * @property addPlaylist contract driving the add-playlist dialog.
 * @property detailFor factory that creates the detail screen's contract for a given playlist id.
 * @property player contract driving both the mini player and the full player screen.
 * @property settings contract driving the settings screen.
 */
data class GhostifyDependencies(
    val library: LibraryContract,
    val addPlaylist: AddPlaylistContract,
    /** Creates the detail screen's contract for a given playlist id. */
    val detailFor: (playlistId: String) -> PlaylistDetailContract,
    val player: PlayerContract,
    val settings: SettingsContract,
)
