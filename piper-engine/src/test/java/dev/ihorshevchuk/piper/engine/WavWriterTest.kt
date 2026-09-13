package dev.ihorshevchuk.piper.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies the WAV layout written by [writeWavFloatMono] (port of the Swift
 * to-file path): 44-byte header, IEEE float, mono, 32-bit, unspecified
 * RIFF/data sizes, samples round-tripping exactly.
 */
class WavWriterTest {

    @Test
    fun `wav header matches Swift layout and data round-trips`() {
        val file = File.createTempFile("piper-wav", ".wav")
        try {
            writeWavFloatMono(
                file,
                listOf(floatArrayOf(0.5f, -0.25f), floatArrayOf(0.0f, 1.0f)),
                sampleRate = 22050
            )

            val bytes = file.readBytes()
            assertEquals(44 + 4 * 4, bytes.size)

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", ascii(buf, 0, 4))
            assertEquals(-1, buf.getInt(4)) // unspecified RIFF size, like Swift
            assertEquals("WAVE", ascii(buf, 8, 4))
            assertEquals("fmt ", ascii(buf, 12, 4))
            assertEquals(16, buf.getInt(16))
            assertEquals(3.toShort(), buf.getShort(20)) // IEEE float
            assertEquals(1.toShort(), buf.getShort(22)) // mono
            assertEquals(22050, buf.getInt(24))
            assertEquals(22050 * 4, buf.getInt(28)) // byte rate
            assertEquals(4.toShort(), buf.getShort(32)) // block align
            assertEquals(32.toShort(), buf.getShort(34)) // bits per sample
            assertEquals("data", ascii(buf, 36, 4))
            assertEquals(-1, buf.getInt(40)) // unspecified data size, like Swift

            buf.position(44)
            val samples = FloatArray(4) { buf.getFloat() }
            assertArrayEquals(floatArrayOf(0.5f, -0.25f, 0.0f, 1.0f), samples, 0.0f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `empty chunk list still writes a valid header`() {
        val file = File.createTempFile("piper-wav-empty", ".wav")
        try {
            writeWavFloatMono(file, emptyList(), sampleRate = 16000)

            val bytes = file.readBytes()
            assertEquals(44, bytes.size)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", ascii(buf, 0, 4))
            assertEquals("data", ascii(buf, 36, 4))
            assertEquals(16000, buf.getInt(24))
        } finally {
            file.delete()
        }
    }

    private fun ascii(buf: ByteBuffer, offset: Int, length: Int): String {
        val b = ByteArray(length)
        val pos = buf.position()
        buf.position(offset)
        buf.get(b)
        buf.position(pos)
        return b.toString(Charsets.US_ASCII)
    }
}
