package com.ghostify.player.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.*

/**
 * JVM mirror of T-079 / T-086 / T-087 (the queue-building contract).
 * The instrumentation variants additionally push the resulting items through a real
 * ExoPlayer; here we verify the pure queue-building logic itself.
 */
class PlayerQueueBuilderTest {

    private lateinit var tmpDir: Path
    private lateinit var builder: PlayerQueueBuilder

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("player-queue-test")
        builder = PlayerQueueBuilder()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    private fun newFile(name: String, content: ByteArray = byteArrayOf(1, 2, 3)): String {
        val path = tmpDir.resolve(name)
        path.writeBytes(content)
        return path.toAbsolutePath().toString()
    }

    private fun song(
        id: String,
        filePath: String?,
        status: SongStatus = SongStatus.DOWNLOADED,
        title: String = "Song $id",
    ) = Song(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        durationMs = 60_000L,
        filePath = filePath,
        status = status,
    )

    @Test
    fun `T-079 one item per downloaded song in playlist order`() {
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

        assertEquals(listOf("c", "a", "b"), result.items.map { it.songId }, "playlist order preserved")
        assertEquals(0, result.startIndex)
        result.items.forEachIndexed { index, item -> assertEquals(index, item.indexInQueue) }
    }

    @Test
    fun `T-086 non-downloaded songs are excluded from the queue`() {
        val a = newFile("a.mp3")
        val b = newFile("b.mp3")

        val result = builder.build(
            listOf(
                song("downloaded-1", a),
                song("pending", newFile("pending.mp3"), status = SongStatus.PENDING),
                song("failed", newFile("failed.mp3"), status = SongStatus.FAILED),
                song("queued", null, status = SongStatus.QUEUED),
                song("downloaded-2", b),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("downloaded-1", "downloaded-2"), result.items.map { it.songId })
    }

    @Test
    fun `T-086 downloaded row without a file path is excluded`() {
        val a = newFile("a.mp3")
        val result = builder.build(
            listOf(
                song("no-path", filePath = null),
                song("blank-path", filePath = "   "),
                song("with-path", a),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("with-path"), result.items.map { it.songId })
    }

    @Test
    fun `missing file on disk is excluded even when the DB row says downloaded`() {
        val valid = newFile("ok.mp3")
        val result = builder.build(
            listOf(
                song("missing", filePath = tmpDir.resolve("gone.mp3").toAbsolutePath().toString()),
                song("ok", valid),
            ),
        ) as QueueBuildResult.Ready

        assertEquals(listOf("ok"), result.items.map { it.songId })
    }

    @Test
    fun `zero-length file is treated as unplayable`() {
        val empty = newFile("empty.mp3", byteArrayOf())
        val result = builder.build(listOf(song("empty", empty)))
        assertIs<QueueBuildResult.NothingToPlay>(result)
    }

    @Test
    fun `T-087 no downloaded songs yields NothingToPlay instead of crashing`() {
        val result = builder.build(
            listOf(
                song("p1", filePath = null, status = SongStatus.PENDING),
                song("p2", filePath = null, status = SongStatus.PENDING),
            ),
        )
        assertIs<QueueBuildResult.NothingToPlay>(result)
    }

    @Test
    fun `startSongId picks the start index`() {
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
    fun `unknown startSongId falls back to the first item`() {
        val a = newFile("a.mp3")
        val result = builder.build(listOf(song("a", a)), startSongId = "nope") as QueueBuildResult.Ready
        assertEquals(0, result.startIndex)
    }
}
