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
 */
class GhostifyViewModels(
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

    private val playerVm = PlayerViewModel(player)

    private val settingsVm = SettingsViewModel(settings, repo, songRepo, musicStore)

    private val detailCache = HashMap<String, PlaylistDetailViewModel>()

    fun library(): LibraryContract {
        Timber.i("GhostifyViewModels.library: START")
        return library
    }

    fun addPlaylist(): AddPlaylistContract {
        Timber.i("GhostifyViewModels.addPlaylist: START")
        return addPlaylist
    }

    fun player(): PlayerContract {
        Timber.i("GhostifyViewModels.player: START")
        return playerVm
    }

    fun settings(): SettingsContract {
        Timber.i("GhostifyViewModels.settings: START")
        return settingsVm
    }

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

    /** Cancels every VM coroutine; call when the hosting activity is destroyed. */
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
