package com.luxwatch.gadget.avatar

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Brings Muse's own avatar clips onto the watch (the avatar.set_clips command):
 * [fetch] downloads each MP4, then [build] takes its frames at 15 fps (at most 10 s), crops them
 * square at 256 px, turns the background black ([BackgroundKey]) and stores
 * them as JPEG frames under files/avatar_muse/, played by [FrameClipArt].
 */
class ClipImporter(
    private val context: Context,
    /** Reads a file from Muse's VM over the watch's own connection (path, size cap). */
    private val readVmFile: (suspend (String, Long) -> ByteArray)? = null,
) {
    companion object {
        const val SIZE = 256 // the size the watch face draws an avatar at
        const val DIR = "avatar_muse"
        const val META = "avatar.properties"
        /** Clip states, in the order Muse is told about them. */
        val STATES = listOf("idle", "listening", "thinking", "speaking", "happy", "error")
        private const val MAX_BYTES = 30L * 1024 * 1024
        private const val BATCH = 12
        private const val TAG = "ClipImporter"
        private val lock = Mutex()

        /** An absolute path on Muse's VM (not a link). */
        fun isVmPath(s: String) = s.startsWith("/") && !s.startsWith("//") && ".." !in s.split('/')
    }

    class ImportError(message: String) : Exception(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads the clips ([urls]: state → https link, or the path of a file on
     * Muse's VM; idle required) to the cache. Quick, so bad links are reported
     * to Muse in its answer.
     */
    suspend fun fetch(urls: Map<String, String>): Map<String, File> = withContext(Dispatchers.IO) {
        if (urls["idle"] == null) throw ImportError("idle is required")
        for ((state, url) in urls) {
            if (!url.startsWith("https://") && !isVmPath(url)) {
                throw ImportError("$state: give an https:// link or the path of a file on your computer")
            }
        }
        val files = linkedMapOf<String, File>()
        try {
            for ((state, url) in urls) {
                val f = File.createTempFile("clip_$state", ".mp4", context.cacheDir)
                files[state] = f
                if (url.startsWith("https://")) download(state, url, f) else readFromVm(state, url, f)
            }
            files
        } catch (e: Exception) {
            files.values.forEach { it.delete() }
            throw e
        }
    }

    /** Turns downloaded clips into the avatar (slow: decodes and keys every frame). Returns frames per state. */
    suspend fun build(name: String, clips: Map<String, File>): Map<String, Int> = lock.withLock {
        withContext(Dispatchers.IO) {
            val work = File(context.filesDir, "$DIR.new").apply { deleteRecursively(); mkdirs() }
            try {
                val counts = linkedMapOf<String, Int>()
                for ((state, video) in clips) {
                    Log.i(TAG, "preparing $state")
                    counts[state] = importClip(state, video, File(work, state))
                }
                Properties().apply {
                    setProperty("name", name)
                    for ((state, n) in counts) setProperty(state, n.toString())
                    File(work, META).outputStream().use { store(it, "Muse avatar clips") }
                }
                val dir = File(context.filesDir, DIR)
                dir.deleteRecursively()
                if (!work.renameTo(dir)) throw ImportError("couldn't save the avatar")
                counts
            } finally {
                work.deleteRecursively()
                clips.values.forEach { it.delete() }
            }
        }
    }

    private fun importClip(state: String, video: File, out: File): Int {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(video.path)
            } catch (e: RuntimeException) {
                throw ImportError("$state: not a video the watch can read")
            }
            val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (frames == null || frames <= 0 || durationMs == null || durationMs <= 0) {
                throw ImportError("$state: not a video the watch can read")
            }
            val plan = BackgroundKey.plan(frames, frames * 1000.0 / durationMs)
            out.mkdirs()
            // Decode a few consecutive frames per call: one seek per batch, bounded memory.
            var i = 0
            while (i < plan.size) {
                val start = plan[i]
                var j = i
                while (j + 1 < plan.size && plan[j + 1] - start < BATCH) j++
                val batch = retriever.getFramesAtIndex(start, plan[j] - start + 1)
                for (k in i..j) writeFrame(batch[plan[k] - start], File(out, "%04d.jpg".format(k)))
                batch.forEach { it.recycle() }
                i = j + 1
            }
            return plan.size
        } finally {
            retriever.release()
        }
    }

    private suspend fun readFromVm(state: String, path: String, to: File) {
        val read = readVmFile ?: throw ImportError("$state: not connected to Muse")
        val bytes = try {
            read(path, MAX_BYTES)
        } catch (e: Exception) {
            throw ImportError("$state: couldn't read $path (${e.message})")
        }
        to.writeBytes(bytes)
    }

    private fun download(state: String, url: String, to: File) {
        val response = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: Exception) {
            throw ImportError("$state: couldn't download (${e.message})")
        }
        response.use {
            if (!it.isSuccessful) throw ImportError("$state: download failed, HTTP ${it.code}")
            val body = it.body ?: throw ImportError("$state: empty download")
            if (body.contentLength() > MAX_BYTES) throw ImportError("$state: over 30 MB")
            body.byteStream().use { input ->
                to.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw ImportError("$state: over 30 MB")
                        output.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun writeFrame(src: Bitmap, file: File) {
        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val keyed = Bitmap.createBitmap(BackgroundKey.key(px, SIZE, SIZE), SIZE, SIZE, Bitmap.Config.ARGB_8888)
        file.outputStream().use { keyed.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (square !== src) square.recycle()
        if (small !== square) small.recycle()
        keyed.recycle()
    }
}
