package com.foodtale.signage

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class PlayerEngine(
    ctx: Context,
    private val onEnded: () -> Unit,
    private val onReady: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var preparing = false

    @Volatile var snapshotPositionMs: Long = 0
        private set
    @Volatile var snapshotPlaying: Boolean = false
        private set
    @Volatile var snapshotReady: Boolean = false
        private set

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                snapshotReady = state == Player.STATE_READY
                snapshotPositionMs = currentPosition
                when (state) {
                    Player.STATE_READY -> {
                        preparing = false
                        main.post { onReady() }
                    }
                    Player.STATE_ENDED -> {
                        if (preparing) return
                        snapshotPlaying = false
                        main.post { onEnded() }
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                snapshotPlaying = isPlaying
                snapshotPositionMs = currentPosition
            }
        })
    }

    val ready: Boolean
        get() = snapshotReady

    fun capture() {
        onMain {
            snapshotPositionMs = player.currentPosition
            snapshotPlaying = player.isPlaying
            snapshotReady = player.playbackState == Player.STATE_READY
        }
    }

    fun prepare(file: File) {
        onMain {
            preparing = true
            player.playWhenReady = false
            val uri = Uri.fromFile(file)
            val current = player.currentMediaItem?.localConfiguration?.uri
            if (current == uri && player.mediaItemCount > 0) {
                player.seekTo(0)
                if (player.playbackState != Player.STATE_READY) {
                    player.prepare()
                }
                snapshotPlaying = false
                snapshotReady = player.playbackState == Player.STATE_READY
                if (snapshotReady) {
                    preparing = false
                    main.post { onReady() }
                }
                return@onMain
            }
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.seekTo(0)
            snapshotReady = false
            snapshotPlaying = false
        }
    }

    fun play() {
        onMain {
            player.playWhenReady = true
            player.play()
        }
    }

    fun pause() {
        onMain {
            player.playWhenReady = false
            player.pause()
        }
    }

    fun mute(mute: Boolean) {
        onMain { player.volume = if (mute) 0f else 1f }
    }

    fun setSpeed(speed: Float) {
        onMain { player.playbackParameters = PlaybackParameters(speed) }
    }

    fun seekTo(ms: Long) {
        onMain { player.seekTo(ms) }
    }

    fun release() {
        onMain { player.release() }
    }

    private fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun onMain(block: () -> Unit) {
        if (onMain()) {
            block()
        } else {
            main.post(block)
        }
    }
}
