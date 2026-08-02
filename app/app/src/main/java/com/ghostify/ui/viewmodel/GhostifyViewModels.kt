package com.ghostify.ui.viewmodel

import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SettingsRepository
import com.ghostify.data.repo.SongRepository
import com.ghostify.download.DownloadManager
import com.ghostify.file.MusicStore
import com.ghostify.player.PlayerController
import com.ghostify.python.PlaylistMetadataBridge
import com.ghostify.sync.SyncUseCase
import com.ghostify.ui.contract.AddPlaylistContract
import com.ghostify.ui.contract.LibraryContract
import com.ghostify.ui.contract.PlayerContract
import com.ghostify.ui.contract.PlaylistDetailContract
import com.ghostify.ui.contract.SettingsContract
import com.ghostify.ui.util.PlaylistUrlValidator
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

    fun library(): LibraryContract = library

    fun addPlaylist(): AddPlaylistContract = addPlaylist

    fun player(): PlayerContract = playerVm

    fun settings(): SettingsContract = settingsVm

    fun detailFor(playlistId: String): PlaylistDetailContract =
        detailCache.getOrPut(playlistId) {
            PlaylistDetailViewModel(
                playlistId = playlistId,
                repo = repo,
                songRepo = songRepo,
                downloads = downloads,
                syncer = syncer,
                player = player,
            )
        }

    /** Cancels every VM coroutine; call when the hosting activity is destroyed. */
    fun clear() {
        library.clear()
        addPlaylist.clear()
        playerVm.clear()
        settingsVm.clear()
        detailCache.values.forEach { it.clear() }
        detailCache.clear()
    }
}
