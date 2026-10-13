/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies

/**
 * Tellomi（tellomi/tellomi#1263，spec R-2）：实况照片的动态部分，叠在静态图上面放。
 *
 * 照 Telegram 的机制（iOS `ChatMessageInteractiveMediaNode` / `UniversalVideoGalleryItem`、安卓 `ChatMessageCell` 的实况，只读）：
 * 会话里静音放一遍，放完 0.3 秒淡回静态图；查看器里按「实况」或按住画面时带声循环放。第一帧出来之前不显示，
 * 静态图照常露着。播放器从共享的 ExoPlayer 池里借，停了就还。
 */
@OptIn(UnstableApi::class)
class TellomiLivePhotoPlayerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  companion object {
    private val TAG = Log.tag(TellomiLivePhotoPlayerView::class)

    /** 放完淡回静态图（Telegram iOS 会话里是 0.3 秒）。 */
    const val FADE_OUT_MS = 300L

    private const val POOL_TAG = "TellomiLivePhoto"
  }

  private val playerView: PlayerView
  private var player: ExoPlayer? = null
  private var looping = false

  /** 停下来（放完、被停、出错）以后调一次。 */
  var onStopped: (() -> Unit)? = null

  /** 正在放（包括还没出第一帧）。 */
  val isPlaying: Boolean
    get() = player != null

  init {
    inflate(context, R.layout.tellomi_live_photo_player, this)
    playerView = findViewById(R.id.live_photo_video)
    alpha = 0f
    visibility = GONE
  }

  /** 会话里铺满裁切（同静态图），查看器里完整显示。 */
  fun setCropToFill(crop: Boolean) {
    playerView.resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
  }

  /** 放 [uri]：会话里静音放一遍；[loopWithSound] 时带声循环（查看器的「实况」）。借不到播放器就不放。 */
  fun play(uri: Uri, loopWithSound: Boolean) {
    val exoPlayer = player ?: AppDependencies.exoPlayerPool.get(POOL_TAG) ?: run {
      Log.w(TAG, "No player available")
      return
    }
    if (player == null) {
      player = exoPlayer
      exoPlayer.addListener(listener)
      playerView.player = exoPlayer
    }
    looping = loopWithSound
    exoPlayer.volume = if (loopWithSound) 1f else 0f
    exoPlayer.repeatMode = if (loopWithSound) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    exoPlayer.setMediaItem(MediaItem.fromUri(uri))
    exoPlayer.prepare()
    exoPlayer.playWhenReady = true

    animate().cancel()
    alpha = 0f
    visibility = VISIBLE
  }

  /** 停下，[animated] 时淡回静态图；没在放就什么都不做。 */
  fun stop(animated: Boolean) {
    if (player == null) {
      return
    }
    animate().cancel()
    if (animated && alpha > 0f) {
      animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction { finishStop() }.start()
    } else {
      finishStop()
    }
  }

  private fun finishStop() {
    alpha = 0f
    visibility = GONE
    player?.let {
      it.removeListener(listener)
      it.stop()
      it.clearMediaItems()
      playerView.player = null
      AppDependencies.exoPlayerPool.pool(it)
    }
    player = null
    onStopped?.invoke()
  }

  private val listener = object : Player.Listener {
    override fun onRenderedFirstFrame() {
      animate().cancel()
      alpha = 1f
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == Player.STATE_ENDED && !looping) {
        stop(animated = true)
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.w(TAG, "Couldn't play the live photo video", error)
      stop(animated = false)
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    stop(animated = false)
  }
}
