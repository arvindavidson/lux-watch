package com.luxwatch.gadget

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.luxwatch.gadget.noise.NoiseTransport
import com.luxwatch.gadget.noise.VoiceCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking

/**
 * A live call with Muse (Settings → Live calls): [VoiceCall] over a voice
 * Noise connection, with the watch's mic and speaker at 24 kHz. [run] returns
 * when the call ends; cancel it to hang up (the call is still ended with Muse).
 */
class LuxCall(
    private val connection: GadgetConnection,
    private val listener: VoiceCall.Listener,
) {
    private var call: VoiceCall? = null
    private val speaker = Speaker()

    /** Mic level 0..1 for the avatar. */
    @Volatile var level = 0f
        private set

    /** Runs the call; returns why it ended ("remote_stop", "idle_timeout"…). */
    suspend fun run(): String {
        val transport: NoiseTransport = connection.openVoiceTransport()
        val mic = Mic()
        try {
            if (!mic.start()) throw IllegalStateException("Microphone unavailable")
            speaker.start()
            val c = VoiceCall(transport, deviceId = connection.nodeId, listener = listener)
            call = c
            return c.run(mic, speaker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "call failed: ${e.javaClass.simpleName}")
            throw e
        } finally {
            call = null
            mic.stop()
            speaker.stop()
            transport.close()
        }
    }

    /** Tap while Muse speaks: stop its audio and cancel the reply. */
    fun interrupt() {
        call?.bargeIn(speaker)
    }

    /** 24 kHz PCM16 mono, 960-sample frames, from a background thread to the call. */
    private inner class Mic : VoiceCall.Mic {
        private val frames = Channel<ByteArray>(capacity = 25) // 1 s; a slow reader drops the oldest
        @Volatile private var running = false
        private var record: AudioRecord? = null
        private var thread: Thread? = null

        @SuppressLint("MissingPermission") // the talk screen checks RECORD_AUDIO first
        fun start(): Boolean {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, VoiceCall.FRAME_BYTES) * 4,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            record = rec
            running = true
            thread = Thread({
                rec.startRecording()
                while (running) {
                    val buf = ByteArray(VoiceCall.FRAME_BYTES)
                    var got = 0
                    while (got < buf.size && running) {
                        val n = rec.read(buf, got, buf.size - got)
                        if (n < 0) {
                            running = false
                            break
                        }
                        got += n
                    }
                    if (got == buf.size) {
                        level = ((VoiceCall.rms(buf) - 300) / 5000).toFloat().coerceIn(0f, 1f)
                        if (!frames.trySend(buf).isSuccess) {
                            frames.tryReceive()
                            frames.trySendBlocking(buf)
                        }
                    }
                }
            }, "LuxCallMic").also { it.start() }
            return true
        }

        override suspend fun read(): ByteArray? = frames.receiveCatching().getOrNull()

        fun stop() {
            running = false
            level = 0f
            runCatching { thread?.join(500) }
            runCatching { record?.stop() }
            record?.release()
            record = null
            frames.close()
        }
    }

    /** Plays the call's audio; writes never block (the call paces them). */
    private class Speaker : VoiceCall.Speaker {
        private var track: AudioTrack? = null

        fun start() {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, RATE * 2)) // 1 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }

        override fun write(pcm: ByteArray) {
            track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        }

        override fun flush() {
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                    it.play()
                }
            }
        }

        fun stop() {
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
            track = null
        }
    }

    private companion object {
        const val TAG = "LuxCall"
        const val RATE = VoiceCall.SAMPLE_RATE
    }
}
