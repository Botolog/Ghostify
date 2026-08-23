package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PlayerQueueBuilderTest {

    private lateinit var tmpDir: File
    private lateinit var builder: PlayerQueueBuilder

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "queue-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        builder = PlayerQueueBuilder()
    }

    private fun newFile(name: String, content: ByteArray = byteArrayOf(1, 2, 3)): String {
        val file = File(tmpDir, name)
        file.writeBytes(content)
        return file.absolutePath
    }

    private fun song(
        id: String,
        filePath: String?,
        status: SongStatus = SongStatus.DOWNLOADED,
        title: String = "Song $id",
        artists: String = "Artist",
        album: String = "Album",
        durationMs: Long? = 60_000L,
    ) = Song(
        id = id,
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        filePath = filePath,
        status = status,
    )

    // ── Empty / nothing-to-play ──────────────────────────────────────────

    @Test
    fun emptySongListReturnsNothingToPlay() {
        val result = builder.build(emptyList())
        assertTrue(result is QueueBuildResult.NothingToPlay)
    }

    @Test
    fun allNonDownloadedSongsReturnsNothingToPlay() {
        val result = builder.build(
            listOf(
                song("p1", null, status = SongStatus.PENDING),
                song("p2", null, status = SongStatus.QUEUED),
                song("p3", null, status = SongStatus.DOWNLOADING),
                song("p4", null, status = SongStatus.FAILED),
                song("p5", null, status = SongStatus.REMOVED),
            ),
        )
        assertTrue(result is QueueBuildResult.NothingToPlay)
    }

    @Test
    fun downloadedSongsWithoutFilePathsReturnsNothingToPlay() {
        val result = builder.build(
            listOf(
                song("a", filePath = null, status = SongStatus.DOWNLOADED),
                song("b", filePath = "", status = SongStatus.DOWNLOADED),
                song("c", filePath = "   ", status = SongStatus.DOWNLOADED),
            ),
        )
        assertTrue(result is QueueBuildResult.NothingToPlay)
    }

    @Test
    fun allMissingFilesReturnsNothingToPlay() {
        val result = builder.build(
            listOf(
                song("a", tmpDir.resolve("nonexistent1.mp3").absolutePath),
                song("b", tmpDir.resolve("nonexistent2.mp3").absolutePath),
            ),
        )
        assertTrue(result is QueueBuildResult.NothingToPlay)
    }

    @Test
    fun allZeroLengthFilesReturnsNothingToPlay() {
        val empty = newFile("empty.mp3", byteArrayOf())
        val result = builder.build(listOf(song("e", empty)))
        assertTrue(result is QueueBuildResult.NothingToPlay)
    }

    // ── Ready queue ──────────────────────────────────────────────────────

    @Test
    fun singleDownloadedSongReturnsReadyWithStartIndexZero() {
        val path = newFile("a.mp3")
        val result = builder.build(listOf(song("a", path))) as QueueBuildResult.Ready
        assertEquals(1, result.items.size)
        assertEquals(0, result.startIndex)
        assertEquals("a", result.items[0].songId)
    }

    @Test
    fun multipleDownloadedSongsPreservePlaylistOrder() {
        val a = newFile("a.mp3")
        val b = newFile("b.mp3")
        val c = newFile("c.mp3")

        val result = builder.build(
            listOf(
                song("c", c, title = "C"),
                song("a", a, title = "A"),
                song("b", b, title = "B"),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("c", "a", "b"), result.items.map { it.songId })
    }

    @Test
    fun queueIndicesAreSequentialStartingFromZero() {
        val a = newFile("a.mp3")
        val b = newFile("b.mp3")
        val c = newFile("c.mp3")

        val result = builder.build(
            listOf(song("a", a), song("b", b), song("c", c)),
        ) as QueueBuildResult.Ready

        result.items.forEachIndexed { index, item ->
            assertEquals(index, item.indexInQueue)
        }
    }

    // ── Filtering ────────────────────────────────────────────────────────

    @Test
    fun nonDownloadedSongsAreExcluded() {
        val a = newFile("a.mp3")
        val b = newFile("b.mp3")

        val result = builder.build(
            listOf(
                song("downloaded", a, status = SongStatus.DOWNLOADED),
                song("pending", filePath = null, status = SongStatus.PENDING),
                song("queued", filePath = null, status = SongStatus.QUEUED),
                song("downloading", filePath = null, status = SongStatus.DOWNLOADING),
                song("failed", filePath = null, status = SongStatus.FAILED),
                song("removed", filePath = null, status = SongStatus.REMOVED),
                song("downloaded2", b, status = SongStatus.DOWNLOADED),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("downloaded", "downloaded2"), result.items.map { it.songId })
    }

    @Test
    fun downloadedRowWithBlankPathIsExcluded() {
        val a = newFile("a.mp3")
        val result = builder.build(
            listOf(
                song("blank", filePath = "  ", status = SongStatus.DOWNLOADED),
                song("ok", a),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("ok"), result.items.map { it.songId })
    }

    @Test
    fun missingFileOnDiskIsExcludedEvenIfStatusDownloaded() {
        val valid = newFile("ok.mp3")
        val result = builder.build(
            listOf(
                song("missing", tmpDir.resolve("gone.mp3").absolutePath),
                song("ok", valid),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("ok"), result.items.map { it.songId })
    }

    @Test
    fun mixOfDownloadedAndNonDownloadedOnlyIncludesDownloaded() {
        val a = newFile("a.mp3")
        val c = newFile("c.mp3")

        val result = builder.build(
            listOf(
                song("a", a),
                song("b", null, status = SongStatus.PENDING),
                song("c", c),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("a", "c"), result.items.map { it.songId })
    }

    // ── startSongId resolution ───────────────────────────────────────────

    @Test
    fun startSongIdResolvesCorrectIndex() {
        val a = newFile("a.mp3")
        val b = newFile("b.mp3")
        val c = newFile("c.mp3")

        val result = builder.build(
            listOf(song("a", a), song("b", b), song("c", c)),
            startSongId = "b",
        ) as QueueBuildResult.Ready

        assertEquals(1, result.startIndex)
    }

    @Test
    fun startSongIdResolvesFirstItem() {
        val a = newFile("a.mp3")
        val b = newFile("b.mp3")

        val result = builder.build(
            listOf(song("a", a), song("b", b)),
            startSongId = "a",
        ) as QueueBuildResult.Ready

        assertEquals(0, result.startIndex)
    }

    @Test
    fun startSongIdResolvesLastItem() {
        val a = newFile("a.mp3")
        val b = newFile("b.mp3")
        val c = newFile("c.mp3")

        val result = builder.build(
            listOf(song("a", a), song("b", b), song("c", c)),
            startSongId = "c",
        ) as QueueBuildResult.Ready

        assertEquals(2, result.startIndex)
    }

    @Test
    fun unknownStartSongIdFallsBackToZero() {
        val a = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", a)),
            startSongId = "nonexistent",
        ) as QueueBuildResult.Ready

        assertEquals(0, result.startIndex)
    }

    @Test
    fun nullStartSongIdDefaultsToZero() {
        val a = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", a)),
            startSongId = null,
        ) as QueueBuildResult.Ready

        assertEquals(0, result.startIndex)
    }

    @Test
    fun startSongIdDefaultsToZeroWhenNothingToPlay() {
        val result = builder.build(emptyList(), startSongId = "x")
        assertTrue(result is QueueBuildResult.NothingToPlay)
    }

    // ── QueueItem metadata ───────────────────────────────────────────────

    @Test
    fun queueItemMetadataCopiedFromSong() {
        val path = newFile("a.mp3")
        val result = builder.build(
            listOf(
                song(
                    "a", path,
                    title = "My Title",
                    artists = "My Artist",
                    album = "My Album",
                    durationMs = 180_000L,
                ),
            ),
        ) as QueueBuildResult.Ready

        val item = result.items[0]
        assertEquals("a", item.songId)
        assertEquals("My Title", item.title)
        assertEquals("My Artist", item.artist)
        assertEquals("My Album", item.album)
        assertEquals(180_000L, item.durationMs)
        assertEquals(path, item.filePath)
    }

    @Test
    fun blankTitleMapsToNull() {
        val path = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", path, title = "  ")),
        ) as QueueBuildResult.Ready

        assertNull(result.items[0].title)
    }

    @Test
    fun blankArtistMapsToNull() {
        val path = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", path, artists = "  ")),
        ) as QueueBuildResult.Ready

        assertNull(result.items[0].artist)
    }

    @Test
    fun blankAlbumMapsToNull() {
        val path = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", path, album = "  ")),
        ) as QueueBuildResult.Ready

        assertNull(result.items[0].album)
    }

    @Test
    fun negativeDurationMsMapsToNull() {
        val path = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", path, durationMs = -1L)),
        ) as QueueBuildResult.Ready

        assertNull(result.items[0].durationMs)
    }

    @Test
    fun zeroDurationMsMapsToNull() {
        val path = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", path, durationMs = 0L)),
        ) as QueueBuildResult.Ready

        assertNull(result.items[0].durationMs)
    }

    @Test
    fun nullDurationMsRemainsNull() {
        val path = newFile("a.mp3")
        val result = builder.build(
            listOf(song("a", path, durationMs = null)),
        ) as QueueBuildResult.Ready

        assertNull(result.items[0].durationMs)
    }

    @Test
    fun coverUrlIsCopiedFromSong() {
        val path = newFile("a.mp3")
        val s = song("a", path).copy(coverUrl = "https://example.com/cover.jpg")
        val result = builder.build(listOf(s)) as QueueBuildResult.Ready

        assertEquals("https://example.com/cover.jpg", result.items[0].coverUrl)
    }

    // ── Custom FileValidator ─────────────────────────────────────────────

    @Test
    fun customFileValidatorRejectsAll() {
        val alwaysFalse = FileValidator { false }
        val customBuilder = PlayerQueueBuilder(fileValidator = alwaysFalse)
        val path = newFile("a.mp3")

        val result = customBuilder.build(listOf(song("a", path)))
        assertTrue(result is QueueBuildResult.NothingToPlay)
    }

    @Test
    fun customFileValidatorAcceptsAll() {
        val alwaysTrue = FileValidator { true }
        val customBuilder = PlayerQueueBuilder(fileValidator = alwaysTrue)

        val result = customBuilder.build(
            listOf(song("a", tmpDir.resolve("does-not-exist.mp3").absolutePath)),
        ) as QueueBuildResult.Ready

        assertEquals(1, result.items.size)
    }

    @Test
    fun mediaIdAliasEqualsSongId() {
        val path = newFile("a.mp3")
        val result = builder.build(listOf(song("a", path))) as QueueBuildResult.Ready
        assertEquals(result.items[0].songId, result.items[0].mediaId)
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun largeNumberOfSongsPreservesOrder() {
        val songs = (1..100).map { i ->
            val path = newFile("song$i.mp3")
            song(i.toString(), path, title = "Song $i")
        }

        val result = builder.build(songs) as QueueBuildResult.Ready
        assertEquals(100, result.items.size)
        assertEquals((1..100).map { it.toString() }, result.items.map { it.songId })
        result.items.forEachIndexed { index, item -> assertEquals(index, item.indexInQueue) }
    }

    private fun assertNull(value: Any?) {
        assertEquals(null, value)
    }
}
