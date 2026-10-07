package com.foodtale.signage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Two decoders. The visible one keeps playing through a cut.
 * The other one prerolls the next item at frame 0, then they swap.
 */
class PlayerEngine(
    ctx: Context,
    private val onEnded: () -> Unit,
    private val onReady: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val app = ctx.applicationContext
    private var visibleView: PlayerView? = null
    private var parkedView: PlayerView? = null
    private var stillView: ImageView? = null
    private var pendingStill: File? = null
    private var pendingBitmap: Bitmap? = null
    private var shownBitmap: Bitmap? = null
    private var front: ExoPlayer = buildPlayer()
    private var back: ExoPlayer = buildPlayer()
    private var muted = true

    @Volatile var showingStill: Boolean = false
        private set
    @Volatile var showingHold: Boolean = false
        private set
    @Volatile var showingGap: Boolean = false
        private set

    @Volatile var snapshotPositionMs: Long = 0
        private set
    @Volatile var snapshotPlaying: Boolean = false
        private set
    @Volatile var snapshotReady: Boolean = false
        private set

    val player: ExoPlayer get() = front

    val ready: Boolean
        get() = snapshotReady

    fun attach(visible: PlayerView, parked: PlayerView, still: ImageView) {
        visibleView = visible
        parkedView = parked
        stillView = still
        visible.player = front
        parked.player = back
        back.volume = 0f
    }

    fun capture() {
        onMain {
            if (showingGap) {
                snapshotPlaying = false
                snapshotReady = true
                return@onMain
            }
            if (showingStill || showingHold) {
                snapshotPlaying = true
                snapshotReady = true
                return@onMain
            }
            snapshotPositionMs = front.currentPosition
            snapshotPlaying = front.isPlaying
            snapshotReady = front.playbackState == Player.STATE_READY
        }
    }

    fun prepare(file: File, still: Boolean = false) {
        onMain {
            if (still) decodeStill(file) else load(front, file)
        }
    }

    fun preload(file: File, still: Boolean = false) {
        onMain {
            if (still) {
                decodeStill(file)
                return@onMain
            }
            if (readyFor(back, file)) return@onMain
            if (sameFile(back, file) && back.playbackState == Player.STATE_BUFFERING) return@onMain
            load(back, file)
        }
    }

    fun start(file: File, still: Boolean = false, loop: Boolean = false): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (still) return showStill(file)
        val mode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        front.repeatMode = mode
        back.repeatMode = mode
        if (front.isPlaying && readyFor(back, file)) {
            swap()
            return true
        }
        if (!front.isPlaying && readyFor(front, file)) {
            hideStill()
            clearHold()
            front.playWhenReady = true
            front.play()
            return true
        }
        if (!front.isPlaying && readyFor(back, file)) {
            swap()
            return true
        }
        return false
    }

    fun holdLastFrame(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (showingStill) {
            showingHold = true
            showingGap = false
            snapshotPlaying = true
            snapshotReady = true
            return true
        }
        if (front.playbackState == Player.STATE_IDLE || front.duration <= 0) return false
        front.repeatMode = Player.REPEAT_MODE_OFF
        front.seekTo((front.duration - 80).coerceAtLeast(0))
        front.playWhenReady = false
        front.pause()
        showingHold = true
        showingGap = false
        snapshotPlaying = true
        snapshotReady = true
        snapshotPositionMs = front.currentPosition
        return true
    }

    fun showGap(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        hideStill()
        front.playWhenReady = false
        front.pause()
        showingHold = false
        showingGap = true
        snapshotPlaying = false
        snapshotReady = true
        snapshotPositionMs = 0
        return true
    }

    fun canStart(file: File, still: Boolean = false): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (still) return pendingStill == file && pendingBitmap != null
        if (front.isPlaying && readyFor(back, file)) return true
        if (!front.isPlaying && readyFor(front, file)) return true
        return !front.isPlaying && readyFor(back, file)
    }

    fun play() {
        onMain {
            front.playWhenReady = true
            front.play()
        }
    }

    fun pause() {
        onMain {
            front.playWhenReady = false
            front.pause()
        }
    }

    fun mute(mute: Boolean) {
        onMain {
            muted = mute
            front.volume = if (mute) 0f else 1f
            back.volume = 0f
        }
    }

    fun currentSpeed(): Float = front.playbackParameters.speed

    fun setSpeed(speed: Float) {
        onMain {
            if (front.playbackParameters.speed == speed) return@onMain
            front.playbackParameters = PlaybackParameters(speed)
        }
    }

    fun seekTo(ms: Long) {
        onMain { front.seekTo(ms) }
    }

    fun release() {
        onMain {
            hideStill()
            pendingBitmap?.recycle()
            pendingBitmap = null
            shownBitmap = null
            front.release()
            back.release()
        }
    }

    private fun showStill(file: File): Boolean {
        val bmp = pendingBitmap ?: return false
        if (pendingStill != file) return false
        showingStill = true
        showingHold = false
        showingGap = false
        snapshotPlaying = true
        snapshotReady = true
        snapshotPositionMs = 0
        shownBitmap = bmp
        stillView?.setImageBitmap(bmp)
        stillView?.visibility = View.VISIBLE
        front.playWhenReady = false
        front.pause()
        return true
    }

    private fun hideStill() {
        showingStill = false
        stillView?.visibility = View.GONE
        stillView?.setImageDrawable(null)
    }

    private fun clearHold() {
        showingHold = false
        showingGap = false
    }

    private fun decodeStill(file: File) {
        if (pendingStill == file && pendingBitmap != null) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 1920) }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
        val old = pendingBitmap
        pendingBitmap = bmp
        pendingStill = file
        if (old != null && old != shownBitmap && old != bmp) old.recycle()
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var size = 1
        while (width / size > maxEdge || height / size > maxEdge) size *= 2
        return size
    }

    private fun swap() {
        hideStill()
        clearHold()
        val old = front
        front = back
        back = old
        back.playWhenReady = false
        back.pause()
        back.volume = 0f
        front.volume = if (muted) 0f else 1f
        visibleView?.player = front
        parkedView?.player = back
        front.playWhenReady = true
        front.play()
        snapshotPlaying = true
        snapshotReady = true
        snapshotPositionMs = front.currentPosition
    }

    private fun load(target: ExoPlayer, file: File) {
        target.playWhenReady = false
        val uri = Uri.fromFile(file)
        if (sameFile(target, file) && target.mediaItemCount > 0) {
            target.seekTo(0)
            if (target.playbackState == Player.STATE_IDLE) target.prepare()
            return
        }
        target.stop()
        target.clearMediaItems()
        target.setMediaItem(MediaItem.fromUri(uri))
        target.prepare()
        target.seekTo(0)
    }

    private fun readyFor(target: ExoPlayer, file: File): Boolean =
        sameFile(target, file) &&
            target.playbackState == Player.STATE_READY &&
            target.currentPosition <= 120 &&
            !target.isPlaying

    private fun sameFile(target: ExoPlayer, file: File): Boolean =
        target.currentMediaItem?.localConfiguration?.uri == Uri.fromFile(file)

    private fun buildPlayer(): ExoPlayer =
        ExoPlayer.Builder(app).build().apply {
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (showingStill || showingHold || showingGap) return
                    if (this@apply != front) {
                        if (state == Player.STATE_READY) main.post { onReady() }
                        return
                    }
                    snapshotPositionMs = currentPosition
                    when (state) {
                        Player.STATE_READY -> {
                            snapshotReady = true
                            main.post { onReady() }
                        }
                        Player.STATE_BUFFERING, Player.STATE_IDLE -> snapshotReady = false
                        Player.STATE_ENDED -> {
                            snapshotPlaying = false
                            snapshotReady = false
                            main.post { onEnded() }
                        }
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (showingStill || showingHold || showingGap || this@apply != front) return
                    snapshotPlaying = isPlaying
                    snapshotPositionMs = currentPosition
                }
            })
        }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
