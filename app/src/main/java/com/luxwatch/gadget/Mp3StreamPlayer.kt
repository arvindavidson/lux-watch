package com.luxwatch.gadget

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.min

/**
 * Plays an MP3 while it's still arriving (Muse's spoken reply from
 * tts-stream): MediaPlayer reads from a buffer that [feed] grows, waiting for
 * more until [finish]. One reply per player.
 */
class Mp3StreamPlayer {
    private val lock = Object()
    private var buf = ByteArray(64 * 1024)
    private var size = 0
    private var ended = false
    private var cancelled = false
    private var started = false
    private val done = CompletableDeferred<Unit>()
    private val player = MediaPlayer()

    private val source = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            synchronized(lock) {
                val deadline = System.currentTimeMillis() + STALL_MS
                while (position >= size && !ended && !cancelled) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return -1 // the stream stalled: end it
                    lock.wait(left)
                }
                if (cancelled || position >= size) return -1
                val n = min(length.toLong(), size - position).toInt()
                System.arraycopy(buf, position.toInt(), buffer, offset, n)
                return n
            }
        }

        override fun getSize(): Long = synchronized(lock) { if (ended) size.toLong() else -1L }

        override fun close() = Unit
    }

    /** Adds the next piece of MP3; playback starts with the first. */
    fun feed(chunk: ByteArray) {
        synchronized(lock) {
            if (size + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + chunk.size))
            System.arraycopy(chunk, 0, buf, size, chunk.size)
            size += chunk.size
            lock.notifyAll()
        }
        if (!started) start()
    }

    /** No more MP3 is coming. */
    fun finish() {
        synchronized(lock) {
            ended = true
            lock.notifyAll()
        }
        if (!started) done.complete(Unit)
    }

    /** Suspends until the reply has played (or failed, or was stopped). */
    suspend fun await() = done.await()

    /** Stops now. */
    fun stop() {
        synchronized(lock) {
            cancelled = true
            lock.notifyAll()
        }
        runCatching { player.stop() }
        release()
    }

    private fun start() {
        started = true
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener { release() }
        player.setOnErrorListener { _, _, _ ->
            release()
            true
        }
        try {
            player.setDataSource(source)
            player.prepareAsync()
        } catch (e: Exception) {
            release()
        }
    }

    private fun release() {
        runCatching { player.release() }
        done.complete(Unit)
    }

    private companion object {
        /** Give up on a stream that sends nothing for this long. */
        const val STALL_MS = 15_000L
    }
}
