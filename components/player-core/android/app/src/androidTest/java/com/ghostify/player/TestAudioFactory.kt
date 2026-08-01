package com.ghostify.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds tiny, self-contained audio fixtures for instrumentation tests — no assets, no
 * encoders. Playback only needs *files*: the controller feeds their paths to ExoPlayer, which
 * plays the WAVs natively and errors on the deliberately corrupt MP3.
 */
object TestAudioFactory {

    /** A silent 8 kHz / mono / 16-bit PCM WAV, guaranteed playable and of known duration. */
    fun wav(context: Context, name: String, durationMs: Int): File {
        val file = File(context.cacheDir, name)
        val sampleRate = 8000
        val numChannels = 1
        val bitsPerSample = 16
        val numSamples = sampleRate.toLong() * durationMs / 1000L
        val dataSize = (numSamples * numChannels * bitsPerSample / 8L).toInt()

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(numChannels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * numChannels * bitsPerSample / 8)
        header.putShort((numChannels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        file.outputStream().use { out ->
            out.write(header.array())
            out.write(ByteArray(dataSize)) // silence
        }
        return file
    }

    /** A file with an .mp3 extension whose bytes are not a valid audio container. */
    fun corruptMp3(context: Context, name: String): File {
        val file = File(context.cacheDir, name)
        // Deliberately not a container: no ID3 magic, no MPEG frame sync, no WAV header.
        val bytes = ByteArray(8192)
        for (i in bytes.indices) {
            bytes[i] = ((i * 31 + 7) % 256).toByte()
        }
        file.writeBytes(bytes)
        return file
    }

    /**
     * A minimal MPEG-1 Layer III file that carries a real ID3v2.3 tag, including embedded front
     * cover artwork — enough for `MediaMetadataRetriever` to read title/artist/album and the
     * embedded picture.
     */
    fun mp3WithArtwork(
        context: Context,
        name: String,
        title: String,
        artist: String,
        album: String,
        artworkJpeg: ByteArray,
    ): File {
        val tagFrames = ByteArrayOutputStream().apply {
            write(textFrame("TIT2", title))
            write(textFrame("TPE1", artist))
            write(textFrame("TALB", album))
            write(apicFrame(artworkJpeg))
        }.toByteArray()

        val tag = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0x03, 0x00, 0x00)) // ID3v2.3, no flags
            write(syncSafeInt(tagFrames.size))
            write(tagFrames)
        }.toByteArray()

        // A handful of valid MPEG-1 Layer III frames (128 kbps / 44.1 kHz, 417-byte frames).
        val audioFrames = ByteArray(20 * 417).apply {
            val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
            for (frame in 0 until 20) {
                val offset = frame * 417
                header.copyInto(this, offset)
                // payload remains silence
            }
        }

        return File(context.cacheDir, name).apply {
            writeBytes(tag + audioFrames)
        }
    }

    /** Generates a real (tiny) JPEG so the artwork can be embedded in the fixture MP3. */
    fun jpeg(context: Context): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 90, 200))
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    private fun textFrame(id: String, text: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(0x00) // ISO-8859-1 text encoding
            write(text.toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        return frame(id, body)
    }

    private fun apicFrame(jpeg: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(0x00) // encoding
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            write(0x00)
            write(0x03) // picture type: front cover
            write(0x00) // empty description
            write(jpeg)
        }.toByteArray()
        return frame("APIC", body)
    }

    private fun frame(id: String, body: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            write(ByteBuffer.allocate(4).putInt(body.size).array()) // ID3v2.3: plain big-endian
            write(byteArrayOf(0, 0)) // frame flags
            write(body)
        }.toByteArray()

    /** ID3v2 size fields are synchsafe (7 bits of each byte used). */
    private fun syncSafeInt(value: Int): ByteArray {
        val out = ByteArray(4)
        out[0] = ((value shr 21) and 0x7F).toByte()
        out[1] = ((value shr 14) and 0x7F).toByte()
        out[2] = ((value shr 7) and 0x7F).toByte()
        out[3] = (value and 0x7F).toByte()
        return out
    }
}
