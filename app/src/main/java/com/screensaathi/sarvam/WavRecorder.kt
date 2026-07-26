package com.screensaathi.sarvam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Records 16 kHz mono 16-bit PCM and writes a valid WAV file — exactly what
 * Saaras v3 wants (16 kHz mono, WAV/PCM). Tap-to-start / tap-to-stop; no VAD in
 * M1 (endpointing tuning is a later milestone).
 */
class WavRecorder {

    private var record: AudioRecord? = null
    @Volatile private var recording = false
    private var thread: Thread? = null

    private val sampleRate = 16_000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted
    fun start(outFile: File): Boolean {
        if (recording) return false
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuf <= 0) return false
        val bufSize = minBuf * 2
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channel, encoding, bufSize)
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init failed: ${e.message}")
            return false
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); return false
        }
        record = ar
        recording = true
        ar.startRecording()
        thread = Thread { writeLoop(ar, outFile, bufSize) }.also { it.start() }
        return true
    }

    /** Blocks until the writer thread finishes flushing the WAV header. */
    fun stop() {
        if (!recording) return
        recording = false
        try { thread?.join(1500) } catch (_: InterruptedException) {}
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
    }

    private fun writeLoop(ar: AudioRecord, outFile: File, bufSize: Int) {
        val buf = ByteArray(bufSize)
        var total = 0L
        try {
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(0)
                writeWavHeader(raf, 0) // placeholder, patched on stop
                while (recording) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        raf.write(buf, 0, n)
                        total += n
                    }
                }
                // Patch sizes now that we know the payload length.
                raf.seek(0)
                writeWavHeader(raf, total.toInt())
            }
        } catch (e: Exception) {
            Log.w(TAG, "write loop failed: ${e.message}")
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val byteRate = sampleRate * 2 // mono * 16-bit
        val riffLen = 36 + dataLen
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) { for (i in s.indices) header[off + i] = s[i].code.toByte() }
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF"); putInt(4, riffLen); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sampleRate); putInt(28, byteRate); putShort(32, 2); putShort(34, 16)
        putStr(36, "data"); putInt(40, dataLen)
        raf.write(header)
    }

    companion object { private const val TAG = "WavRecorder" }
}
