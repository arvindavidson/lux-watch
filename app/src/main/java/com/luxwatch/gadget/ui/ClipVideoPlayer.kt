package com.luxwatch.gadget.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * Plays Lux's clips (H.264 MP4 in res/raw) on a TextureView with the watch's
 * hardware video decoder, much cheaper than decoding animated WebP on the CPU.
 * One MediaPlayer is reused; switching clips keeps the last frame on screen
 * until the next one starts, so there's no black flash.
 */
class ClipVideoPlayer(private val context: Context) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var wanted: Pair<Int, Boolean>? = null
    private var playing: Pair<Int, Boolean>? = null

    fun attach(view: TextureView) {
        view.surfaceTextureListener = this
        view.surfaceTexture?.let { onSurfaceTextureAvailable(it, view.width, view.height) }
    }

    /** Plays raw resource [res] (looping or once); a no-op if it's already playing. */
    fun play(res: Int, loop: Boolean) {
        wanted = res to loop
        start()
    }

    private fun start() {
        val s = surface ?: return
        val clip = wanted ?: return
        if (clip == playing) return
        val mp = player ?: MediaPlayer().also { player = it }
        try {
            mp.reset()
            context.resources.openRawResourceFd(clip.first).use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.setSurface(s)
            mp.isLooping = clip.second
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            playing = clip
        } catch (e: Exception) {
            Log.w(TAG, "can't play clip", e)
            playing = null
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface?.release()
        surface = Surface(st)
        playing = null
        start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
        playing = null
    }

    private companion object {
        const val TAG = "ClipVideoPlayer"
    }
}
