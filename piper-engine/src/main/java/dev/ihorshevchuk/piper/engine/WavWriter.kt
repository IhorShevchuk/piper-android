package dev.ihorshevchuk.piper.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes float32 mono chunks to [file] as a WAV file: 44-byte header, IEEE
 * float (format tag 3), mono, 32-bit, with unspecified RIFF/data sizes - the
 * same layout the Swift side writes (streamed synthesis never knows the
 * total size up front).
 */
internal fun writeWavFloatMono(file: File, chunks: List<FloatArray>, sampleRate: Int) {
    FileOutputStream(file).use { fos ->
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(-1) // RIFF chunk size: unspecified (streamed), like Swift
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // fmt chunk size
        header.putShort(3) // IEEE float
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 4) // byte rate: sampleRate * channels * bytesPerSample
        header.putShort(4) // block align
        header.putShort(32) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(-1) // data chunk size: unspecified, like Swift
        fos.write(header.array())

        val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (chunk in chunks) {
            var i = 0
            while (i < chunk.size) {
                buf.clear()
                while (i < chunk.size && buf.remaining() >= 4) {
                    buf.putFloat(chunk[i++])
                }
                buf.flip()
                fos.write(buf.array(), 0, buf.limit())
            }
        }
    }
}
