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

    /** PCM payload bytes captured in the last take, excluding the 44-byte header. */
    @Volatile
    var bytesRecorded: Long = 0L
        private set

    /**
     * Normalised 0..1 loudness of the most recent PCM buffer, for the voice
     * waveform.
     *
     * Computed here, inside the existing write loop, rather than from a second
     * AudioRecord: two recorders on one mic is either an outright failure or a
     * silent capture on most OEMs, and the buffer is already in hand. The
     * visualiser only ever reads this field, so the audio layer stays unaware
     * of how — or whether — anything is drawn.
     */
    @Volatile
    var level: Float = 0f
        private set

    /**
     * Captured audio length in ms. The caller uses this to refuse to spend an
     * STT round trip on a double-tapped mic that recorded nothing.
     */
    val recordedMs: Long
        get() = bytesRecorded * 1000 / (sampleRate * BYTES_PER_SAMPLE)

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted
    fun start(outFile: File): Boolean {
        if (recording) return false
        bytesRecorded = 0L
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
        level = 0f
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
                        bytesRecorded = total
                        level = rms(buf, n)
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

    /**
     * RMS of one 16-bit little-endian mono buffer, mapped to 0..1.
     *
     * Perceptual, not linear: speech sits far below full scale, so a linear
     * RMS/32768 reading leaves a waveform that barely twitches while someone
     * is plainly talking. The log curve below puts normal speech in the upper
     * half of the range, which is what makes the visualiser track the voice
     * instead of looking broken.
     */
    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        val samples = n / 2
        if (samples <= 0) return 0f
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            sum += (s.toDouble() * s.toDouble())
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / samples)
        if (rms < 1.0) return 0f
        // ~ -60dBFS floor -> 0, full scale -> 1.
        val db = 20.0 * kotlin.math.log10(rms / 32768.0)
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val byteRate = sampleRate * BYTES_PER_SAMPLE // mono * 16-bit
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

    companion object {
        private const val TAG = "WavRecorder"
        private const val BYTES_PER_SAMPLE = 2
    }
}
