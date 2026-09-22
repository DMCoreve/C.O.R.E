package com.dmcore.core.wakeword

import java.io.File
import java.io.RandomAccessFile

/** Empaqueta PCM de 16 bits mono crudo en un archivo .wav estándar, sin dependencias. */
object WavUtil {

    fun writeWav(dir: File, samples: ShortArray, sampleRate: Int): File {
        val file = File(dir, "wake_capture_${System.currentTimeMillis()}.wav")
        val dataSize = samples.size * 2

        RandomAccessFile(file, "rw").use { raf ->
            raf.writeAscii("RIFF")
            raf.writeIntLE(36 + dataSize)
            raf.writeAscii("WAVE")
            raf.writeAscii("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1) // PCM
            raf.writeShortLE(1) // mono
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(sampleRate * 2) // byte rate
            raf.writeShortLE(2) // block align
            raf.writeShortLE(16) // bits per sample
            raf.writeAscii("data")
            raf.writeIntLE(dataSize)

            val bytes = ByteArray(dataSize)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            raf.write(bytes)
        }
        return file
    }

    private fun RandomAccessFile.writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))

    private fun RandomAccessFile.writeIntLE(v: Int) = write(
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        ),
    )

    private fun RandomAccessFile.writeShortLE(v: Int) = write(
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()),
    )
}
