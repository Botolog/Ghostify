import json
import asyncio
import time
import requests
import spotapi  # type: ignore
from collections import deque

try:
    from .utils import getCookiesFile
    from .CookiesExtraction import interactiveMode as extractCookiesFromBrowser
    from .Formatter import SpotifyFormatter
    from .LastPlayed import LastPlayedManger
except ImportError:
    from utils import getCookiesFile
    from CookiesExtraction import interactiveMode as extractCookiesFromBrowser
    from Formatter import SpotifyFormatter
    from LastPlayed import LastPlayedManger

spotapi.client._FALLBACK_SECRET = (
    61,
    bytearray(
        [
            44,
            55,
            47,
            42,
            70,
            40,
            34,
            114,
            76,
            74,
            50,
            111,
            120,
            97,
            75,
            76,
            94,
            102,
            43,
            69,
            49,
            120,
            118,
            80,
            64,
            78,
        ]
    ),
)


class Spotify:
    """
    Wrapper that makes SpotAPI behave like Spotipy.
    Only implements commonly used methods but can be expanded.
    """

    def __init__(
        self,
        login=False,
        getIsrc=False,
        cookiesFile=None,
        email=None,
        cookies=None,
        *args,
        **kwargs,
    ):
        self.user_auth = False
        self._next = None
        self.lastPlayedManager = None
        self.recentlyPlayed = deque(maxlen=50)  # type: ignore
        self.playerStatus = None
        self.player = None
        if cookiesFile != None:
            self.login(cookiesFile)

        elif (
            email != None and cookies != None
        ):  #< allow direct login with cookies for interactive use
            tempFile = getCookiesFile("temp_cookies.json")
            with open(tempFile, "w") as f:
                json.dump(cookies, f)
            self.login(tempFile)

        elif login == True:
            self.login()

        self.getIsrc = False
        if getIsrc:
            try:
                import aiohttp

                self.getIsrc = True
            except:
                print(
                    "aiohttp and asyncio are required for fetching ISRCs. Please install them to use this feature."
                )

    def getTrackFromGroover(self, songId, session=None):
        # can also use GET "https://tools4music.com/api/spotify/isrc?id=SongID" or "https://www.chosic.com/api/tools/tracks/SongID"
        # returns {isrc: "GBAAA9800322", name: "Teardrop", artists: ["Massive Attack", "Elizabeth Fraser"],…}

        url = "https://groover.co/core/distantapi/spotify/getdata/"

        headers = {
            "accept": "application/json",
            "origin": "https://groover.co",
            "referer": "https://groover.co/en/lp/free-tools/isrc-finder/",
            "content-type": "application/json",
        }

        payload = {"url": f"https://open.spotify.com/track/{songId}"}

        try:
            if session:  #< if using async
                return session.post(url, headers=headers, json=payload)
            else:
                response = requests.post(url, headers=headers, json=payload)
                if response.status_code != 200:
                    return {}
                return response.json()
        except Exception as e:
            print("Could not fetch data:", e)
            return {}

    def _getIsrc(self, songId, session=None):
        data = self.getTrackFromGroover(songId, session)
        if data == {}:
            return data
        return data["external_ids"]["isrc"]

    async def _getIsrc_async(self, session, songId):
        try:
            async with self._getIsrc(songId, session) as resp:
                if resp.status != 200:
                    return songId, ""

                data = await resp.json()
                return songId, data.get("external_ids", {}).get("isrc", "")
        except Exception as e:
            print("Could not fetch ISRC:", e)
            return songId, ""

    def _loginIfNeeded(self):
        if self.isLoggedIn():
            return
        self.login()

    def _createPlayerIfNeeded(self):
        self._loginIfNeeded()
        if self.player == None:
            self.player = spotapi.player.Player(self.user_auth)

    def _createPlayerStatusIfNeeded(self):
        self._loginIfNeeded()
        if self.playerStatus == None:
            self.playerStatus = spotapi.player.PlayerStatus(self.user_auth)

    def _addToRecentlyPlayed(self, trackUri, playedAt, contextUri, timePlayed):
        track = self.track(trackUri.split(":")[-1])
        context = SpotifyFormatter.formatContext(contextUri)
        self.recentlyPlayed.append(
            {"track": track, "played_at": playedAt, "ms_played": timePlayed, "context": context}
        )

    def startRecentlyPlayedListener(self, refreshInterval=3):
        self._loginIfNeeded()
        if not self.lastPlayedManager:
            self.lastPlayedManager = LastPlayedManger(self.user_auth)
        self.lastPlayedManager.start(self._addToRecentlyPlayed, refreshInterval)

    def next(self, *args, **kwargs):
        return self._next(*args, **kwargs)

    def login(self, cookiesFile=None) -> bool:
        if cookiesFile == None:
            cookiesFile = getCookiesFile()
        try:
            cfg = spotapi.Config(logger=spotapi.Logger())
            saver = spotapi.saver.JSONSaver(cookiesFile)
            try:
                with open(cookiesFile, "r") as f:
                    sessions = json.load(f)
                identifier = sessions[0]["identifier"]
            except Exception as e:
                print("Error loading cookies file:", e)
                return False

            self.user_auth = spotapi.Login.from_saver(saver, cfg, identifier)
        except:
            extractCookiesFromBrowser(cookiesFile)
            return self.login(cookiesFile)
        return True

    def isLoggedIn(self):
        return type(self.user_auth) != bool

    def isUrl(self, test):
        return (
            test.startswith("spotify:")
            or test.startswith("https://open.spotify.com/")
            or test.startswith("http://open.spotify.com/")
            or test.startswith("open.spotify")
        )

    def urlToId(self, url):
        return url.split("/")[-1].split("?")[0]

    def album(self, albumId, *args, **kwargs):
        if self.isUrl(albumId):
            albumId = self.urlToId(albumId)

        album = spotapi.PublicAlbum(albumId).get_album_info()["data"]["albumUnion"]
        artists = SpotifyFormatter.formatArtists(album["artists"]["items"])
        tracks = SpotifyFormatter.formatTracks(album["tracksV2"]["items"])
        return SpotifyFormatter.formatAlbum(album, artists, tracks)

    def album_tracks(self, albumId, *args, **kwargs):
        if self.isUrl(albumId):
            albumId = self.urlToId(albumId)

        allTracks = []
        for tracks in spotapi.PublicAlbum(albumId).paginate_album():
            allTracks.extend(tracks)
        allTracks = SpotifyFormatter.formatTracks(allTracks)

        return SpotifyFormatter.addChunkInfo(allTracks)

    def artist(self, artistId, *args, **kwargs):
        if self.isUrl(artistId):
            artistId = self.urlToId(artistId)

        artist = spotapi.Artist().get_artist(artistId)["data"]["artistUnion"]
        return SpotifyFormatter.formatArtist(artist)

    def artist_albums(
        self, artistId, limit=-1, offset=0, include_groups="album", *args, **kwargs
    ):
        if self.isUrl(artistId):
            artistId = self.urlToId(artistId)

        allowed = set(include_groups.split(","))
        discog = spotapi.Artist().get_artist(artistId)["data"]["artistUnion"][
            "discography"
        ]

        merged = []
        for key, albumsList in discog.items():
            key = key.removesuffix("s")  #< ex: spotipy uses item while the backend api uses items 
            if key in allowed:
                for albums in albumsList.get("items", []):
                    for album in albums["releases"]["items"]:
                        try:
                            merged.append(self.album(album["id"]))
                        except:
                            pass

        return SpotifyFormatter.addChunkInfo(merged)

    def playlist(self, playlistId, limit=-1, offset=0, *args, **kwargs):
        if self.isUrl(playlistId):
            playlistId = self.urlToId(playlistId)
        playlist = spotapi.PublicPlaylist(playlistId).get_playlist_info()["data"][
            "playlistV2"
        ]
        return SpotifyFormatter.formatPlaylist(playlist)

    async def playlist_items_async(self, playlistId, *args, **kwargs):
        if self.isUrl(playlistId):
            playlistId = self.urlToId(playlistId)

        allTracks = []
        tasks = []
        session = None

        if self.getIsrc:
            session = aiohttp.ClientSession()  # type: ignore

        try:
            for chunk in spotapi.PublicPlaylist(playlistId).paginate_playlist():
                for track in chunk["items"]:
                    try:
                        meta = SpotifyFormatter.formatPlaylistTrack(track)
                        allTracks.append(meta)

                        if self.getIsrc:
                            tasks.append(
                                self._getIsrc_async(session, meta["track"]["id"])
                            )
                    except:
                        pass

            if self.getIsrc and tasks:
                results = await asyncio.gather(*tasks)  # type: ignore
                isrc_map = dict(results)

                for meta in allTracks:
                    sid = meta["track"]["id"]
                    meta["track"]["external_ids"]["isrc"] = isrc_map.get(sid, "")

        finally:
            if session:
                await session.close()

        return SpotifyFormatter.addChunkInfo(allTracks)

    def playlist_items(self, *args, **kwargs):
        try:
            loop = (
                asyncio.get_event_loop()
            )  #< bind to async thread if already exists # type: ignore
            return loop.run_until_complete(self.playlist_items_async(*args, **kwargs))
        except RuntimeError:
            return asyncio.run(self.playlist_items_async(*args, **kwargs))  # type: ignore

    def track(self, trackId, *args, **kwargs):
        if self.isUrl(trackId):
            trackId = self.urlToId(trackId)

        track = spotapi.Song().get_track_info(trackId)["data"]["trackUnion"]
        try:
            artists = track["firstArtist"]["items"]
            artists.extend(track["otherArtists"]["items"])
        except:
            artists = ["Not Found"]
        formattedArtists = SpotifyFormatter.formatArtists(artists)
        track = SpotifyFormatter.formatTrack(track, formattedArtists)
        if self.getIsrc:
            track["external_ids"] = {"isrc": self._getIsrc(track["track_id"])}
        return track

    def audio_features(self, trackIds, *args, **kwargs):
        if type(trackIds) == str:
            trackIds = [trackIds]

        headers = {
            "authority": "www.chosic.com",
            "method": "GET",
            "path": None,
            "scheme": "https",
            "accept": "application/json, text/javascript, */*; q=0.01",
            "accept-encoding": "gzip, deflate, br, zstd",
            "accept-language": "en-US,en;q=0.9",
            "app": "playlist_generator",
            "cache-control": "no-cache",
            "dnt": "1",
            "pragma": "no-cache",
            "priority": "u=1, i",
            "referer": "https://www.chosic.com/song-bpm-key-finder/",
            "sec-ch-ua": '"Chromium";v="148", "Microsoft Edge";v="148", "Not/A)Brand";v="99"',
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": '"Windows"',
            "sec-fetch-dest": "empty",
            "sec-fetch-mode": "cors",
            "sec-fetch-site": "same-origin",
            "x-requested-with": "XMLHttpRequest"
        }

        results = []
        for trackId in trackIds:
            if self.isUrl(trackId):
                trackId = self.urlToId(trackId)

            session = requests.Session()

            session.cookies.set('pll_language', 'en')
            session.headers.update({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            "AppleWebKit/537.36 (KHTML, like Gecko) "
                            "Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0"
            })
            headers["path"] = f"/api/tools/audio-features/{trackId}"

            url = f"https://www.chosic.com/api/tools/audio-features/{trackId}"

            try:
                response = session.get(url, headers=headers)
                if response.status_code != 200:
                    print(f"www.chosic.com returned bad status code: {response.status_code}, you might have been blocked")
                    results.append(None)
                    continue
                results.append(response.json())
                time.sleep(1)
            except Exception as e:
                print(f"Could not fetch data from www.chosic.com: {e}")
                results.append(None)

        return(results)

    def search(self, query, type="track", *args, **kwargs):
        pages = spotapi.Public().song_search(query)
        for results in pages:  #< save first page
            break

        tracks = []
        for res in results:
            res = res["item"]["data"]
            if res["__typename"] != "Track":  #< only accept tracks
                continue
            formattedArtists = SpotifyFormatter.formatArtists(res["artists"]["items"])
            meta = SpotifyFormatter.formatTrack(res, formattedArtists)
            if self.getIsrc:
                meta["external_ids"] = {"isrc": self._getIsrc(meta["track_id"])}
            tracks.append(meta)

        return {"tracks": SpotifyFormatter.addChunkInfo(tracks)}

    def current_user_saved_tracks(self, limit=-1, offset=0, *args, **kwargs):
        self._loginIfNeeded()

        pl = spotapi.playlist.PrivatePlaylist(self.user_auth).paginate_saved_tracks()
        tracks = []
        for raws in pl:
            for raw in raws["items"]:
                addedAt = raw["addedAt"]["isoString"]
                songId = raw["track"]["_uri"].removeprefix("spotify:track:")
                track = raw["track"]["data"]
                try:
                    artists = track["artists"]["items"]
                except:
                    artists = ["Not Found"]
                artists = SpotifyFormatter.formatArtists(artists)
                meta = SpotifyFormatter.formatTrack(track, artists, songId=songId)
                if self.getIsrc:
                    meta["external_ids"] = {"isrc": self._getIsrc(meta["track_id"])}
                tracks.append({"added_at": addedAt, "track": meta})

        result = SpotifyFormatter.addChunkInfo(tracks)
        result["href"] = (
            f"https://api.spotify.com/v1/me/tracks?offset={offset}&limit={limit}"
        )
        return result

    def current_user_saved_tracks_contains(
        self, tracks
    ):
        self._loginIfNeeded()
        pl = spotapi.playlist.PrivatePlaylist(self.user_auth).paginate_saved_tracks()
        results = []
        for trackId in tracks:
            for raws in pl:
                for raw in raws["items"]:
                    songId = raw["track"]["_uri"].removeprefix("spotify:track:")
                    if songId == trackId:
                        results.append(True)
            results.append(False)
        return results

    def current_user_recently_played(self, limit=50, after=None, before=None):
        return list(self.recentlyPlayed)

    def current_playback(self, *args, **kwargs):
        self._createPlayerStatusIfNeeded()
        state = self.playerStatus.state
        track = self.track(state.track.uri.removeprefix("spotify:track:"))
        context = SpotifyFormatter.formatContext(state.context_uri)
        metadata = SpotifyFormatter.addChunkInfo(track, mode="single")
        metadata["context"] = context
        metadata["is_playing"] = not state.is_paused
        metadata["progress_ms"] = -1
        return metadata

    def current_user_playlists(self, limit=-1, offset=0, *args, **kwargs):
        userPlaylists = []

        library = spotapi.playlist.PrivatePlaylist(self.user_auth).get_library()
        library = library["data"]["me"]["libraryV3"]["items"]
        for res in library:
            data = res["item"]["data"]
            if data["__typename"] != "Playlist":
                continue
            plId = res["item"]["_uri"].removeprefix("spotify:playlist:")
            try:
                pl = self.playlist(plId)
            except:  #< private playlist that can't be accessed by self.playlist
                pl = SpotifyFormatter.formatPlaylist(data)
            userPlaylists.append(pl)
        return SpotifyFormatter.addChunkInfo(userPlaylists)

    def seek_track(self, position_ms, device_id=None, *args, **kwargs):
        self._createPlayerIfNeeded()
        self.player.seek_to(position_ms)
        return True

    def next_track(self, device_id=None, *args, **kwargs):
        self._createPlayerIfNeeded()
        self.player.skip_next()
        return True

    def previous_track(self, device_id=None, *args, **kwargs):
        self._createPlayerIfNeeded()
        self.player.skip_prev()
        return True

    def pause_playback(self, device_id=None, *args, **kwargs):
        self._createPlayerIfNeeded()
        self.player.pause()
        return True

    def start_playback(self, device_id=None, *args, **kwargs):
        self._createPlayerIfNeeded()
        self.player.resume()
        return True

    def current_user_saved_tracks_add(self, *args, **kwargs):
        return

    def current_user_saved_tracks_delete(self, *args, **kwargs):
        return

    def playlist_remove_all_occurrences_of_items(self, *args, **kwargs):
        return

    def playlist_add_items(self, *args, **kwargs):
        return

    def current_user_followed_artists(self, limit=-1, offset=0, *args, **kwargs):
        return

    def me(self):
        """Get detailed profile information about the current user.
        An alias for the 'current_user' method.
        """
        self._loginIfNeeded()
        userInfo = spotapi.user.User(self.user_auth).get_user_info()
        return SpotifyFormatter.formatMe(userInfo)

    def current_user(self):
        return self.me()


if __name__ == "__main__":
    import time
    import json
    import os

    try:
        import pysole  # type: ignore
    except:
        pysole = None
        print("To get an interactive console, do pip install liveConsole")

    def makeJsonSafe(obj):
        """
        Recursively convert an object into something JSON-serializable.
        If an object isn't serializable, fall back to obj.__dict__.
        """

        if isinstance(obj, (str, int, float, bool)) or obj is None:
            return obj

        if isinstance(obj, (list, tuple, set)):
            return [makeJsonSafe(x) for x in obj]

        if isinstance(obj, dict):
            return {str(k): makeJsonSafe(v) for k, v in obj.items()}

        try:
            json.dumps(obj)
            return obj
        except Exception:
            pass

        if hasattr(obj, "__dict__"):            #< spotapi uses weird objects, convert them to dicts
            return makeJsonSafe(obj.__dict__)

        # Last resort: string representation
        return str(obj)

    def save(jsonData, name="saved.json"):
        with open(
            os.path.join(
                os.path.dirname(os.path.realpath(__file__)),
                "../../",
                "Data",
                "Output",
                name,
            ),
            "w",
        ) as f:
            json.dump(makeJsonSafe(jsonData), f, indent=4)

    def testPlayer(sp):
        sp._createPlayerIfNeeded()
        sp._createPlayerStatusIfNeeded()
        player = sp.player
        status = sp.playerStatus

        save(status.state.__dict__, "status.json")
        sp.startRecentlyPlayedListener()

        sp.seek_track(5000)
        sp.next_track()
        time.sleep(3)
        sp.previous_track()

        current_playback = sp.current_playback()
        save(current_playback, "current_playback.json")
        current_user_playlists = sp.current_user_playlists()
        save(current_user_playlists, "current_user_playlists.json")
        current_user_saved_tracks_contains = sp.current_user_saved_tracks_contains(
            "67Hna13dNDkZvBpTXRIaOJ"
        )
        save(
            [current_user_saved_tracks_contains], "current_user_saved_tracks_contains.json"
        )
        return player, status

    def testWebsocketReconnect(sp):
        """
        Force the PlayerStatus websocket to disconnect and verify that the
        automatic reconnect logic works.
        """
        sp._createPlayerStatusIfNeeded()

        print("Current connection ID:")
        print(sp.playerStatus.connection_id)

        print("\nClosing websocket...")
        sp.playerStatus.ws.close()

        # Give the reconnect logic time to run
        time.sleep(5)

        print("\nNew connection ID:")
        print(sp.playerStatus.connection_id)

        try:
            state = sp.playerStatus.state
            print("\nReconnect successful!")
            print("Current track:", sp.current_playback())
        except Exception as e:
            print("\nReconnect failed:", e)

    sp = Spotify()
    # res = sp.audio_features(["https://open.spotify.com/track/4tyjNEHKos3lZPYAfTiMKH?si=b6fd0ef9ebca4a0c", "https://open.spotify.com/track/67Hna13dNDkZvBpTXRIaOJ?si=e8268aa17dc44271"])
    sp.login()


    if pysole:
        pysole.probe(runRemainingCode=True, printStartupCode=True)
    testWebsocketReconnect(sp)
    try:
        player, status = testPlayer(sp)
    except:
        print("Using outdate spotAPI, run pip uninstall spotAPI, and then pip install git+https://github.com/TzurSoffer/SpotAPI/")

    artist = sp.artist("3Bd1cgCjtCI32PYvDC3ynO")
    save(artist, "artist.json")
    artistAlbums = sp.artist_albums("3Bd1cgCjtCI32PYvDC3ynO", include_groups="single")#include_groups="album,single,compilation")
    save(artistAlbums, "artist_albums.json")
    playlist = sp.playlist_items("6lnfkAgnVtNzvj8KScLSkj")
    save(playlist, "playlist.json")
    track = sp.track("67Hna13dNDkZvBpTXRIaOJ")
    save(track, "track.json")
    album = sp.album("4m2880jivSbbyEGAKfITCa")
    save(album, "album.json")
    albumTracks = sp.album_tracks("4m2880jivSbbyEGAKfITCa")
    save(albumTracks, "album_tracks.json")
    search = sp.search("Blinding Light - Weekend")
    save(search, "search.json")

    self = sp
