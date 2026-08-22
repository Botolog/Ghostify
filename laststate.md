# Last State - Download Fix Investigation

## Problem

The Python command-line test `downloader._downloader.search(song)` returns the **correct** YouTube video for songs (e.g., "Without Me" by Halsey → ZAfAud_M_mg, official video, 1.18B views).

When the **same search** runs inside the Android app, it returns a **wrong version**.

All 4 test songs return correct results when searched via CLI. The app still picks wrong versions.

## Files Involved

### Python (Chaquopy)
- `app/app/src/main/python/ghostify_dl.py` — Main Python module containing `TrackDownloader`, `download()`, `_download_song()`, `_search()`, `make_downloader()`
- `app/app/python-spotdl/spotdl/download/downloader.py` — spotdl's `Downloader` class with `search()` and `search_and_download()`
- `app/app/python-spotdl/spotdl/providers/audio/base.py` — `AudioProvider.search()` (lines 160-329), `get_best_result()` (lines 331-386)
- `app/app/python-spotdl/spotdl/providers/audio/ytmusic.py` — `YouTubeMusic` provider (lines 19-133)
- `app/app/python-spotdl/spotdl/providers/audio/youtube.py` — `YouTube` provider (lines 15-67)
- `app/app/python-spotdl/spotdl/utils/matching.py` — `order_results()` (lines 753-979), scoring/matching algorithm
- `components/track-download/src/main/python/ghostify_dl.py` — Component copy of ghostify_dl.py

### Kotlin
- `app/app/src/main/java/xyz/botolog/ghostify/download/SpotdlTrackDownloader.kt` — `ChaquopySpotdlCall` class (lines 66-146), bridges Kotlin to Python
- `app/app/src/main/java/xyz/botolog/ghostify/trackdownload/TrackDownloadBridge.kt` — `downloadBlocking()` (lines 69-92), `requireDownloader()` (lines 47-53)
- `app/app/src/main/java/xyz/botolog/ghostify/trackdownload/TrackDownloadConfig.kt` — Config data class (lines 11-31)

## Functions Involved in Download Flow

### Kotlin → Python Bridge
1. `ChaquopySpotdlCall.invoke()` — constructs URL (`spotify:track:{id}` for Spotify, `youtube.com/watch?v={ytId}` for YouTube) and calls bridge
2. `TrackDownloadBridge.requireDownloader()` — calls `make_downloader(outputDir, bitrate, template, ffmpeg)` with only 4 positional args
3. `TrackDownloadBridge.downloadBlocking()` — calls `download(downloader, url, hook, hook, hook)`

### Python Download Pipeline
4. `TrackDownloader.download(url)` (ghostify_dl.py:1502) — entry point, calls `_search()` then `_download_song()`
5. `TrackDownloader._search(url)` (ghostify_dl.py:1220) — dispatches: Spotify → `parse_query()`, YouTube video → `_search_youtube_video()`, YouTube playlist → `_search_youtube_playlist()`
6. `TrackDownloader._download_song()` (ghostify_dl.py:1575) — calls `self._downloader.search_and_download(song)`
7. `Downloader.search_and_download(song)` (downloader.py:463) — checks if `song.download_url` is set; if None, calls `self.search(song)`
8. `Downloader.search(song)` (downloader.py:378) — iterates through `self.audio_providers`, returns first provider's URL

### Audio Provider Search
9. `AudioProvider.search(song, only_verified)` (base.py:160) — ISRC search first, then title search with `order_results()`
10. `YouTubeMusic.get_results()` (ytmusic.py:52) — ytmusicapi search with filter="songs" then filter="videos"
11. `YouTube.get_results()` (youtube.py:23) — yt-dlp `ytsearch10:{query}`

### Matching Algorithm
12. `order_results()` (matching.py:753) — scores each result using artist match, name match, album match, time match, channel-author bonus, forbidden words penalty, unrelated words penalty

## Key Observations (Facts, Not Speculations)

1. `make_downloader()` in Python accepts `audio_providers` parameter with default `["youtube-music", "youtube"]`
2. Kotlin bridge `requireDownloader()` calls `make_downloader` with only 4 positional args (outputDir, bitrate, template, ffmpeg) — does NOT pass `audio_providers`
3. Therefore the Python default `["youtube-music", "youtube"]` is always used in the app
4. `Downloader.search()` iterates providers in order; first provider to return a URL wins
5. `YouTubeMusic` provider has `SUPPORTS_ISRC = True`; `YouTube` provider has `SUPPORTS_ISRC = False`
6. `YouTubeMusic._create_client()` initializes with `YTMusic(language="de")` (German locale)
7. YouTube Music "songs" filter results have `verified=True`; YouTube yt-dlp results have `verified=False`
8. `AudioProvider.search()` returns early (line 302-310) if `best_score >= 80 and best_result.verified`
9. `reinit_song()` is called in `search_and_download()` before search, which re-fetches metadata from Spotify
10. The CLI test `downloader._downloader.search(song)` directly calls `Downloader.search()` which uses the same audio_providers list
