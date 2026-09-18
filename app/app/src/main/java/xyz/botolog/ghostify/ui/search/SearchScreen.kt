package xyz.botolog.ghostify.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.botolog.ghostify.ui.contract.SearchContract
import xyz.botolog.ghostify.ui.contract.SearchContract.SearchUiState

/**
 * Test tag constants for the search screen.
 */
object SearchTestTags {
    const val SEARCH_BAR = "search_bar"
    const val RESULTS_LIST = "search_results"
    const val EMPTY_STATE = "search_empty"
}

private val RESULT_ICON_SIZE = 20.dp

/**
 * Full search screen with a search bar at the top and results below.
 *
 * @param contract ViewModel contract driving the search state and actions.
 * @param onBack callback invoked when the back button is tapped.
 * @param onPlaylistClick callback invoked when a playlist result is tapped with the playlist id.
 * @param onSongClick callback invoked when a song result is tapped with the playlist id and song id.
 * @param modifier optional modifier applied to the screen root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    contract: SearchContract,
    onBack: () -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onSongClick: (playlistId: String, songId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchBar(
                query = state.query,
                onQueryChange = contract::onQueryChange,
                onClear = contract::clearResults,
                focusRequester = focusRequester,
            )

            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(32.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }
                state.query.isNotBlank() && state.playlistResults.isEmpty() && state.songResults.isEmpty() -> {
                    EmptySearchState(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(SearchTestTags.EMPTY_STATE),
                    )
                }
                else -> {
                    SearchResultList(
                        state = state,
                        onPlaylistClick = onPlaylistClick,
                        onSongClick = onSongClick,
                    )
                }
            }
        }
    }
}

/**
 * Search input field with a leading search icon and a trailing clear button.
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester)
            .testTag(SearchTestTags.SEARCH_BAR),
        placeholder = { Text("Search songs or playlists...") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
    )
}

/**
 * Scrollable list of search results, grouped by Playlists and Songs sections.
 */
@Composable
private fun SearchResultList(
    state: SearchUiState,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (playlistId: String, songId: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SearchTestTags.RESULTS_LIST),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (state.playlistResults.isNotEmpty()) {
            item(key = "section_playlists") {
                SectionHeader("Playlists")
            }
            items(state.playlistResults, key = { "playlist_${it.id}" }) { result ->
                PlaylistResultRow(
                    result = result,
                    onClick = { onPlaylistClick(result.id) },
                )
            }
        }

        if (state.songResults.isNotEmpty()) {
            item(key = "section_songs") {
                SectionHeader("Songs")
            }
            items(state.songResults, key = { "song_${it.songId}" }) { result ->
                SongResultRow(
                    result = result,
                    onClick = { onSongClick(result.playlistId, result.songId) },
                )
            }
        }
    }
}

/**
 * Section header label with primary color styling.
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * A single playlist search result row.
 */
@Composable
private fun PlaylistResultRow(
    result: SearchContract.PlaylistSearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = result.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${result.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A single song search result row showing playlist path and song info.
 */
@Composable
private fun SongResultRow(
    result: SearchContract.SongSearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = result.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${result.playlistName} / ${result.artists}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Empty state shown when a search query returns no results.
 */
@Composable
private fun EmptySearchState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No results found",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Try a different search term.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
