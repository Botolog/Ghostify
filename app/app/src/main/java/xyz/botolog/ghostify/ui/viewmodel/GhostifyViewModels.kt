package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.python.PlaylistMetadataBridge
import xyz.botolog.ghostify.sync.SyncUseCase
import xyz.botolog.ghostify.ui.contract.AddPlaylistContract
import xyz.botolog.ghostify.ui.contract.LibraryContract
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract
import xyz.botolog.ghostify.ui.contract.SettingsContract
import xyz.botolog.ghostify.ui.util.PlaylistUrlValidator
import timber.log.Timber
import java.util.UUID

/**
 * Builds every UI-contract ViewModel over the domain layer (manual DI).
 *
 * The Library and Add-playlist VMs are coupled by design: the Add dialog is
 * rendered from the Library's `isAddDialogOpen` flag, so the Add VM's close
 * callback flips that flag. Detail VMs are cached per playlist id so leaving and
 * re-entering a playlist keeps its state.
 *
 * @property repo playlist persistence layer.
 * @property songRepo song persistence layer.
 * @property settings app-wide settings repository.
 * @property bridge bridge to the Python playlist metadata fetcher.
 * @property downloads download orchestration layer.
 * @property syncer re-sync use case for playlists.
 * @property player media playback controller.
 * @property musicStore local file storage manager.
 * @property newId function that generates a new unique id.
 */
class GhostifyViewModels(
    private val context: android.content.Context,
    private val repo: PlaylistRepository,
    private val songRepo: SongRepository,
    private val settings: SettingsRepository,
    private val bridge: PlaylistMetadataBridge,
    private val downloads: DownloadManager,
    private val syncer: SyncUseCase,
    private val player: PlayerController,
    private val musicStore: MusicStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val library = LibraryViewModel(repo, downloads)

    private val addPlaylist = AddPlaylistViewModel(
        validator = { PlaylistUrlValidator.validate(it) },
        bridge = bridge,
        repo = repo,
        settings = settings,
        downloads = downloads,
        onClosed = { library.closeAddDialog() },
        newId = newId,
    )

    private val playerVm = PlayerViewModel(player, songRepo.songDao, downloads)

    private val settingsVm = SettingsViewModel(context, settings, repo, songRepo, musicStore)

    /** Cache of detail VMs keyed by playlist id. */
    private val detailCache = HashMap<String, PlaylistDetailViewModel>()

    /**
     * Returns the singleton [LibraryContract] instance.
     */
    fun library(): LibraryContract {
        Timber.i("GhostifyViewModels.library: START")
        return library
    }

    /**
     * Returns the singleton [AddPlaylistContract] instance.
     */
    fun addPlaylist(): AddPlaylistContract {
        Timber.i("GhostifyViewModels.addPlaylist: START")
        return addPlaylist
    }

    /**
     * Returns the singleton [PlayerContract] instance.
     */
    fun player(): PlayerContract {
        Timber.i("GhostifyViewModels.player: START")
        return playerVm
    }

    /**
     * Returns the singleton [SettingsContract] instance.
     */
    fun settings(): SettingsContract {
        Timber.i("GhostifyViewModels.settings: START")
        return settingsVm
    }

    /**
     * Returns a cached [PlaylistDetailContract] for the given playlist.
     *
     * A new instance is created on first access and reused for subsequent calls
     * with the same [playlistId].
     *
     * @param playlistId the id of the playlist to show.
     */
    fun detailFor(playlistId: String): PlaylistDetailContract {
        Timber.i("GhostifyViewModels.detailFor: START")
        return detailCache.getOrPut(playlistId) {
            PlaylistDetailViewModel(
                playlistId = playlistId,
                repo = repo,
                songRepo = songRepo,
                downloads = downloads,
                syncer = syncer,
                player = player,
            )
        }
    }

    /**
     * Cancels every VM coroutine; call when the hosting activity is destroyed.
     */
    fun clear() {
        Timber.i("GhostifyViewModels.clear: START")
        library.clear()
        addPlaylist.clear()
        playerVm.clear()
        settingsVm.clear()
        detailCache.values.forEach { it.clear() }
        detailCache.clear()
    }
}
