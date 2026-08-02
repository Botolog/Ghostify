class SpotifyFormatter:
    @staticmethod
    def formatPlaylistTrack(track):
        trackV3 = track["itemV3"]["data"]
        trackV2 = track["itemV2"]["data"]

        trackType = "track" if trackV2["mediaType"] == "AUDIO" else "None"
        songId = trackV3["uri"].removeprefix("spotify:track:")

        meta = {
            "track": {
                "name": trackV3["identityTrait"]["name"],
                "id": songId,
                "duration_ms": trackV2["trackDuration"]["totalMilliseconds"],
                "description": trackV3["identityTrait"]["description"],
                "artists": trackV3["identityTrait"]["contributors"]["items"],
                "album": {},
                "type": trackType,
                "external_urls": {
                    "spotify": "https://open.spotify.com/track/"
                    + trackV2["uri"].removeprefix("spotify:track:")
                },
                "is_local": False,
                "disc_number": trackV2["discNumber"],
                "track_number": trackV2["trackNumber"],
                "explicit": trackV2["contentRating"]["label"] == "EXPLICIT",
                "external_ids": {"isrc": ""},
            }
        }
        return meta

    @staticmethod
    def formatTrack(track, formattedArtists, songId=None, album=[]):
        if songId == None:
            songId = track["uri"].removeprefix("spotify:track:")

        if album == []:
            try:
                albumMeta = track["albumOfTrack"]
                tracks = albumMeta.get("tracks", {}).get("items", [])
                album = SpotifyFormatter.formatAlbum(
                    albumMeta,
                    formattedArtists,
                    tracks=tracks,
                )
            except:
                pass
        meta = {
            "name": track["name"],
            "track_id": songId,
            "id": track.get("id", songId),
            "disc_number": track.get("discNumber", 1),
            "track_number": track.get("trackNumber", 1),
            "duration_ms": track["duration"]["totalMilliseconds"],
            "artists": formattedArtists,
            "album": album,
            "explicit": track["contentRating"]["label"] == "EXPLICIT",
            "external_urls": {"spotify": "https://open.spotify.com/track/" + songId},
            "popularity": 10,  #< needs fixing
            "type": "track",
            "external_ids": {"isrc": ""},
        }
        return meta

    @staticmethod
    def formatTracks(tracks):
        return [
            SpotifyFormatter.formatTrack(
                track["track"],
                SpotifyFormatter.formatArtists(track["track"]["artists"]["items"]),
            )
            for track in tracks
        ]

    @staticmethod
    def formatArtist(artist):
        if type(artist) != dict:
            return {
                "name": "",
                "id": "",
                "uri": "",
                "external_urls": {"spotify": ""},
                "images": [],
                "href": "",
                "genres": [""],
            }

        name = ""
        name = artist.get("profile", {}).get("name", "")
        uri = artist.get("uri", "")
        try:
            images = artist.get("visuals", {}).get("avatarImage", {}).get("sources", [])
        except:
            images = []
        return {
            "name": name,
            "id": uri.removeprefix("spotify:artist:"),
            "uri": uri,
            "external_urls": {
                "spotify": uri.replace(
                    "spotify:artist:", "https://open.spotify.com/artist/"
                )
            },
            "href": uri.replace(
                "spotify:artist:", "https://api.spotify.com/v1/artists/"
            ),
            "images": images,
            "genres": artist.get("genres", [""]),
        }

    @staticmethod
    def formatArtists(artists):
        return [SpotifyFormatter.formatArtist(artist) for artist in artists]

    @staticmethod
    def formatAlbum(album, artists, tracks):
        altDate = {"isoString": "0000-00-00T00:00:00Z"}
        date = album.get("date", altDate)
        if date is None:
            date = altDate

        albumId = album["uri"].removeprefix("spotify:album:")
        album["id"] = albumId
        album["artists"] = artists
        album["tracks"] = {"items": tracks}
        album["total_tracks"] = len(album["tracks"]["items"])
        album["images"] = album["coverArt"]["sources"]
        album["release_date"] = date["isoString"].split("T")[0]
        album["album_type"] = "album"
        album["copyrights"] = [{"text": "", "type": ""}]
        album["external_urls"] = {
            "spotify": f"https://open.spotify.com/album/{albumId}"
        }
        album["genres"] = [""]
        return album

    @staticmethod
    def formatPlaylist(playlist):
        playlist["owner"] = playlist["ownerV2"]["data"]
        playlist.pop("ownerV2", None)
        playlist["owner"]["display_name"] = playlist["owner"]["name"]
        playlist["external_urls"] = {}
        playlist["external_urls"]["spotify"] = playlist["owner"]["uri"]
        try:
            playlist["images"] = playlist["images"]["items"][-1]["sources"]
        except:
            playlist["images"] = []
        if "id" not in playlist:
            playlist["id"] = playlist["uri"].removeprefix("spotify:playlist:")
        return playlist

    @staticmethod
    def formatMe(userInfo, userPlan=None):
        profile = userInfo.get("profile", {})
        userId = profile.get("email")
        return {
            "country": profile.get("country"),
            "display_name": profile.get(
                "username"
            ),  #< wrong this is the id, not username
            "email": userId,
            "explicit_content": {"filter_enabled": False, "filter_locked": False},
            "external_urls": {"spotify": f"https://open.spotify.com/user/{userId}"},
            "followers": {"href": None, "total": -1},
            "href": f"https://api.spotify.com/v1/users/{userId}",
            "id": userId,
            "images": [
                {"height": 300, "url": "https://i.scdn.co/image/null", "width": 300},
                {"height": 64, "url": "https://i.scdn.co/image/null", "width": 64},
            ],
            "product": "null",
            "type": "user",
            "uri": f"spotify:user:{userId}",
        }

    @staticmethod
    def formatContext(contextUri):
        contextId = contextUri.split(":")[-1]
        contextType = contextUri.split(":")[1]
        context = {
            "type": contextType,
            "href": f"https://api.spotify.com/v1/{contextType}s/{contextId}",
            "external_urls": {
                "spotify": f"https://open.spotify.com/{contextType}/{contextId}"
            },
            "uri": contextUri,
        }
        return context

    @staticmethod
    def addChunkInfo(items, total=-1, limit=-1, offset=0, mode="multi", *args):
        if total == -1:
            total = len(items)
        if limit == -1:
            limit = total
        if mode == "multi":
            return {
                "items": items,
                "total": total,
                "limit": limit,
                "offset": offset,
                "next": False,
                "previous": offset - limit if offset - limit >= 0 else None,
            }
        return {
            "item": items
        }