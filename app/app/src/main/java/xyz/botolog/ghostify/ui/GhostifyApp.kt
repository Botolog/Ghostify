package xyz.botolog.ghostify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.StateFlow
import xyz.botolog.ghostify.ui.add.AddPlaylistDialog
import xyz.botolog.ghostify.ui.contract.AddPlaylistContract
import xyz.botolog.ghostify.ui.contract.LibraryContract
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract
import xyz.botolog.ghostify.ui.contract.SettingsContract
import xyz.botolog.ghostify.ui.library.LibraryScreen
import xyz.botolog.ghostify.ui.player.FullPlayerOverlay
import xyz.botolog.ghostify.ui.player.MiniPlayer
import xyz.botolog.ghostify.ui.player.PlayerScreen
import xyz.botolog.ghostify.ui.playlist.PlaylistDetailScreen
import xyz.botolog.ghostify.ui.search.SearchScreen
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
    const val SEARCH = "search"

    /**
     * Builds the route string for the playlist detail screen.
     *
     * @param playlistId the unique identifier of the playlist.
     * @param highlightSongId optional song id to scroll to and highlight.
     * @return the full navigation route.
     */
    fun playlist(playlistId: String, highlightSongId: String? = null): String {
        return if (highlightSongId != null) {
            "playlist/$playlistId?highlightSongId=$highlightSongId"
        } else {
            "playlist/$playlistId"
        }
    }
}

/**
 * Composition root of the UI component.
 *
 * All screen state comes from the [GhostifyDependencies] contracts (implemented by the
 * `viewmodels` component in the real app; by fakes in Compose UI tests), so this tree is
 * fully unit-testable without an app backend.
 *
 * @param deps the bundle of ViewModel contracts that drive every screen.
 * @param openPlayerRequest open-player request counter fed by the launcher intent (media
 *   notification tap). A state flow is required rather than a bare flow so a request raised
 *   before this tree was collected is still delivered; every value it emits — including its
 *   current one on subscription — latches the full player overlay open. Passing null (the
 *   default) leaves the overlay to the mini player tap only.
 * @param modifier optional modifier applied to the root layout.
 */
@Composable
fun GhostifyApp(
    deps: GhostifyDependencies,
    openPlayerRequest: StateFlow<Long>? = null,
    modifier: Modifier = Modifier,
) {
    GhostifyTheme {
        val navController = rememberNavController()
        val startDestination = Routes.LIBRARY
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val playerState by deps.player.state.collectAsState()

        var fullPlayerOpen by rememberSaveable { mutableStateOf(false) }
        val settingsState by deps.settings.state.collectAsState()

        LaunchedEffect(openPlayerRequest) {
            openPlayerRequest?.collect { fullPlayerOpen = true }
        }

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
                        onOpenSearch = { navController.navigate(Routes.SEARCH) },
                        addDialog = { AddPlaylistDialog(contract = deps.addPlaylist) },
                    )
                }

                composable(
                    route = "playlist/{playlistId}?highlightSongId={highlightSongId}",
                    arguments = listOf(
                        navArgument("playlistId") { type = NavType.StringType },
                        navArgument("highlightSongId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    val playlistId = entry.arguments?.getString("playlistId").orEmpty()
                    val highlightSongId = entry.arguments?.getString("highlightSongId")
                    PlaylistDetailScreen(
                        contract = deps.detailFor(playlistId),
                        onBack = { navController.popBackStack() },
                        highlightSongId = highlightSongId,
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

                composable(Routes.SEARCH) {
                    SearchScreen(
                        contract = deps.search,
                        onBack = { navController.popBackStack() },
                        onPlaylistClick = { playlistId ->
                            navController.navigate(Routes.playlist(playlistId))
                        },
                        onSongClick = { playlistId, songId ->
                            navController.navigate(Routes.playlist(playlistId, songId))
                        },
                    )
                }
            }

            // Spotify-style now-playing bar. Persistent across every screen; hidden while
            // nothing is loaded. Tapping opens the full player overlay. Bottom padding keeps
            // it above the system navigation bar.
            if (!playerState.empty) {
                MiniPlayer(
                    contract = deps.player,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { fullPlayerOpen = true }
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        // Full player overlay — slides up over the current screen.
        FullPlayerOverlay(
            contract = deps.player,
            visible = fullPlayerOpen,
            onBack = { fullPlayerOpen = false },
            landscapeControlsSide = settingsState.landscapeControlsSide,
            layout = settingsState.fullPlayerLayout,
        )
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
    val search: xyz.botolog.ghostify.ui.contract.SearchContract,
)
