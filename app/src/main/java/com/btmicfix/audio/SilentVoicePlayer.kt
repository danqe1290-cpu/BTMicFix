package com.btmicfix.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.btmicfix.util.Logger

/**
 * Plays an endless loop of digital silence as voice-communication audio.
 *
 * Why: since Android 11, AudioService runs a watchdog on apps that set
 * MODE_IN_COMMUNICATION. If such an app is not itself playing or capturing
 * voice-communication audio, it loses the mode a few seconds later (and on any
 * playback/recording change in the system — e.g. the moment Voice Access starts
 * listening). Losing the mode also drops our setCommunicationDevice() route, so
 * the earbuds fall back from HFP to A2DP and their microphone disappears.
 *
 * An active USAGE_VOICE_COMMUNICATION player marks us as "in a call", which keeps
 * the mode and the route. The samples are all zeros, so nothing is audible.
 * Capturing audio would also count, but it would compete with Voice Access for
 * the microphone, so we only play.
 */
class SilentVoicePlayer {

    private var track: AudioTrack? = null

    val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun start() {
        if (isPlaying) return
        stop()
        try {
            val frames = SAMPLE_RATE // one second of silence, looped forever
            val bytes = frames * 2   // 16-bit mono
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()

            newTrack.write(ByteArray(bytes), 0, bytes)
            newTrack.setLoopPoints(0, frames, -1)
            newTrack.play()
            track = newTrack
            Logger.i("Silent voice-communication player started")
        } catch (e: Exception) {
            Logger.e("Could not start silent player", e)
            stop()
        }
    }

    fun stop() {
        val current = track ?: return
        track = null
        try {
            current.stop()
        } catch (e: Exception) {
            // Already stopped or never started.
        }
        current.release()
        Logger.i("Silent voice-communication player stopped")
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
