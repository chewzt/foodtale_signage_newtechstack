package com.foodtale.signage

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
    private var front: ExoPlayer = buildPlayer()
    private var back: ExoPlayer = buildPlayer()
    private var muted = true

    @Volatile var snapshotPositionMs: Long = 0
        private set
    @Volatile var snapshotPlaying: Boolean = false
        private set
    @Volatile var snapshotReady: Boolean = false
        private set

    val player: ExoPlayer get() = front

    val ready: Boolean
        get() = snapshotReady

    fun attach(visible: PlayerView, parked: PlayerView) {
        visibleView = visible
        parkedView = parked
        visible.player = front
        parked.player = back
        back.volume = 0f
    }

    fun capture() {
        onMain {
            snapshotPositionMs = front.currentPosition
            snapshotPlaying = front.isPlaying
            snapshotReady = front.playbackState == Player.STATE_READY
        }
    }

    fun prepare(file: File) {
        onMain { load(front, file) }
    }

    fun preload(file: File) {
        onMain {
            if (readyFor(back, file)) return@onMain
            if (sameFile(back, file) && back.playbackState == Player.STATE_BUFFERING) return@onMain
            load(back, file)
        }
    }

    fun start(file: File): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (front.isPlaying && readyFor(back, file)) {
            swap()
            return true
        }
        if (!front.isPlaying && readyFor(front, file)) {
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

    fun canStart(file: File): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
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
            front.release()
            back.release()
        }
    }

    private fun swap() {
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
                    if (this@apply != front) return
                    snapshotPlaying = isPlaying
                    snapshotPositionMs = currentPosition
                }
            })
        }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
