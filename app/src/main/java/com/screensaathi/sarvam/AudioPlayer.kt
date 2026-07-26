package com.screensaathi.sarvam

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays WAV bytes from Bulbul via MediaPlayer, writing to a temp file first
 * (MediaPlayer needs a source, not a byte array). onDone fires when playback
 * finishes or fails, so the caller can flip the pill out of the speaking state.
 */
class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun play(wavBytes: ByteArray, onStart: () -> Unit, onDone: () -> Unit) {
        stop()
        try {
            val f = File(context.cacheDir, "saathi_tts.wav")
            f.writeBytes(wavBytes)
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener {
                onStart()
                it.start()
            }
            mp.setOnCompletionListener {
                stop()
                onDone()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra")
                stop()
                onDone()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
            onDone()
        }
    }

    fun stop() {
        try {
            player?.let { if (it.isPlaying) it.stop(); it.release() }
        } catch (_: Exception) {
        } finally {
            player = null
        }
    }

    companion object { private const val TAG = "AudioPlayer" }
}
