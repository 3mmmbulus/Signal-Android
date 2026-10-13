/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.feed.net.FeedMedia
import java.io.File
import java.security.MessageDigest
import org.signal.core.ui.R as CoreUiR

internal object FeedVoiceTestTags {
  const val ROW = "feed_voice_row"
  const val TOGGLE = "feed_voice_toggle"
  const val TRANSCRIPT = "feed_voice_transcript"
}

/** 评论区里同一时间只放一条语音：放着哪条、放没放、放到哪。 */
@Immutable
data class FeedVoicePlayback(
  val key: String? = null,
  val loading: Boolean = false,
  val playing: Boolean = false,
  val positionMs: Long = 0,
  val failedKey: String? = null
) {
  fun isActive(key: String): Boolean = this.key == key
}

/**
 * 语音评论的播放（第三节第 4 条）。音频地址和图片一样是 `media_base_url` + key；
 * 先用 App 的 OkHttp 下到缓存目录再交给 [MediaPlayer]，同一条再点就不用重下。
 * 点另一条会停掉当前这条；页面退到后台时暂停（和 Telegram 的语音消息一样）。
 */
class FeedVoicePlayer(private val context: Context, private val scope: CoroutineScope) {

  private val internalState = MutableStateFlow(FeedVoicePlayback())
  val playback: StateFlow<FeedVoicePlayback> = internalState.asStateFlow()

  private var player: MediaPlayer? = null
  private var job: Job? = null

  fun toggle(media: FeedMedia, url: String?) {
    val current = internalState.value
    if (current.isActive(media.key) && player != null) {
      if (current.playing) pause() else resume()
      return
    }
    if (current.isActive(media.key) && current.loading) return
    release()
    if (url == null) {
      internalState.value = FeedVoicePlayback(failedKey = media.key)
      return
    }
    internalState.value = FeedVoicePlayback(key = media.key, loading = true)
    job = scope.launch {
      try {
        val file = withContext(Dispatchers.IO) { download(url) }
        val next = MediaPlayer().apply {
          setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
          setDataSource(file.absolutePath)
          setOnCompletionListener { release() }
        }
        withContext(Dispatchers.IO) { next.prepare() }
        player = next
        next.start()
        internalState.value = FeedVoicePlayback(key = media.key, playing = true)
        while (isActive && player === next) {
          if (next.isPlaying) internalState.update { it.copy(positionMs = next.currentPosition.toLong()) }
          delay(TICK_MS)
        }
      } catch (e: Exception) {
        Log.w(TAG, "voice comment playback failed", e)
        release()
        internalState.value = FeedVoicePlayback(failedKey = media.key)
      }
    }
  }

  fun pause() {
    val current = player ?: return
    if (current.isPlaying) current.pause()
    internalState.update { it.copy(playing = false) }
  }

  private fun resume() {
    val current = player ?: return
    current.start()
    internalState.update { it.copy(playing = true) }
  }

  fun release() {
    job?.cancel()
    job = null
    player?.release()
    player = null
    internalState.value = FeedVoicePlayback()
  }

  private fun download(url: String): File {
    val dir = File(context.cacheDir, "feed-voice").apply { mkdirs() }
    val file = File(dir, sha256(url) + ".m4a")
    if (file.length() > 0) return file
    val request = Request.Builder().url(url).build()
    AppDependencies.okHttpClient.newCall(request).execute().use { response ->
      val body = response.body
      if (!response.isSuccessful || body == null) throw IllegalStateException("voice download ${response.code}")
      val partial = File(dir, file.name + ".part")
      partial.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
      if (!partial.renameTo(file)) throw IllegalStateException("voice rename failed")
    }
    return file
  }

  private fun sha256(value: String): String {
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
  }

  companion object {
    private val TAG = Log.tag(FeedVoicePlayer::class.java)
    private const val TICK_MS = 100L
  }
}

/** 评论列表用的播放器：离开页面释放，退到后台暂停。 */
@Composable
fun rememberFeedVoicePlayer(): FeedVoicePlayer {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val player = remember { FeedVoicePlayer(context, scope) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) player.pause()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      player.release()
    }
  }
  return player
}

/** 语音评论那一行：播放 / 暂停、进度、时长；有转写就在下面显示，空串不显示。 */
@Composable
fun FeedVoiceCommentRow(
  media: FeedMedia,
  transcript: String,
  playback: FeedVoicePlayback,
  onToggle: (FeedMedia) -> Unit,
  modifier: Modifier = Modifier
) {
  val active = playback.isActive(media.key)
  val playing = active && playback.playing
  Column(modifier = modifier.testTag(FeedVoiceTestTags.ROW)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (active && playback.loading) {
        CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
      } else {
        IconButton(onClick = { onToggle(media) }, modifier = Modifier.testTag(FeedVoiceTestTags.TOGGLE)) {
          Icon(
            imageVector = ImageVector.vectorResource(if (playing) CoreUiR.drawable.symbol_pause_24 else CoreUiR.drawable.symbol_play_24),
            contentDescription = stringResource(if (playing) R.string.TellomiFeed__voice_pause else R.string.TellomiFeed__voice_play),
            tint = MaterialTheme.colorScheme.primary
          )
        }
      }
      LinearProgressIndicator(
        progress = { if (active && media.durationMs > 0) (playback.positionMs.toFloat() / media.durationMs).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.widthIn(min = 80.dp, max = 160.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = formatVoiceDuration(if (active && playback.positionMs > 0) playback.positionMs else media.durationMs),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    if (playback.failedKey == media.key) {
      Text(
        text = stringResource(R.string.TellomiFeed__voice_play_failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
      )
    }
    if (transcript.isNotBlank()) {
      Text(
        text = transcript,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(FeedVoiceTestTags.TRANSCRIPT)
      )
    }
  }
}

/** 0:04、1:00；不足一秒按 0:00。 */
internal fun formatVoiceDuration(ms: Long): String {
  val seconds = (ms.coerceAtLeast(0) / 1000)
  return "%d:%02d".format(seconds / 60, seconds % 60)
}
