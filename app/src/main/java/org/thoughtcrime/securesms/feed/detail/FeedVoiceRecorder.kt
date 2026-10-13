/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import java.io.File
import org.signal.core.ui.R as CoreUiR

/**
 * 语音评论的录音：引擎只收 MP4 封装、只有音轨的 AAC（`.m4a`），≤60 秒、≤5 MB。
 * Signal 自己的 [org.thoughtcrime.securesms.audio.MediaRecorderWrapper] 出的是 ADTS，不合格，所以这里单独配一个。
 * 单声道 44.1 kHz、32 kbps：60 秒约 240 KB，远低于 5 MB。
 */
class FeedVoiceRecorder(private val context: Context) {

  private var recorder: MediaRecorder? = null
  private var file: File? = null
  private var startedAt = 0L

  val isRecording: Boolean get() = recorder != null

  val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

  /** 开始录；麦克风被占用等原因开不了时返回 false。 */
  fun start(): Boolean {
    cancel()
    val dir = File(context.cacheDir, "feed-voice-out").apply { mkdirs() }
    val out = File(dir, "${System.currentTimeMillis()}.m4a")
    val next = newRecorder()
    return try {
      next.setAudioSource(MediaRecorder.AudioSource.MIC)
      next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      next.setAudioChannels(1)
      next.setAudioSamplingRate(SAMPLE_RATE)
      next.setAudioEncodingBitRate(BIT_RATE)
      next.setOutputFile(out.absolutePath)
      next.prepare()
      next.start()
      recorder = next
      file = out
      startedAt = SystemClock.elapsedRealtime()
      true
    } catch (e: Exception) {
      Log.w(TAG, "could not start voice recording", e)
      next.release()
      out.delete()
      false
    }
  }

  /** 停下并交出文件；不到 [minMs] 的当没录（删掉，返回 null）。 */
  fun finish(minMs: Long = MIN_MS): File? {
    val current = recorder ?: return null
    val out = file
    val length = elapsedMs
    recorder = null
    file = null
    val ok = try {
      current.stop()
      true
    } catch (e: RuntimeException) {
      // 录得太短时 stop() 会抛，文件不能用
      Log.w(TAG, "voice recording stop failed", e)
      false
    } finally {
      current.release()
    }
    if (!ok || out == null || length < minMs) {
      out?.delete()
      return null
    }
    return out
  }

  fun cancel() {
    val current = recorder ?: return
    recorder = null
    try {
      current.stop()
    } catch (e: RuntimeException) {
      Log.w(TAG, "voice recording stop on cancel failed", e)
    }
    current.release()
    file?.delete()
    file = null
  }

  @SuppressLint("NewApi")
  @Suppress("DEPRECATION")
  private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()

  companion object {
    private val TAG = Log.tag(FeedVoiceRecorder::class.java)
    private const val SAMPLE_RATE = 44_100
    private const val BIT_RATE = 32_000

    /** 太短的不发；和 Telegram 一样，点一下只提示按住说话。 */
    const val MIN_MS = 1_000L

    /** 按下多久才开始录（Telegram 是 150 ms），短于这个当成点了一下。 */
    const val HOLD_DELAY_MS = 150L
  }
}

/**
 * 录音时替换输入框的那一条：红点闪、计时、「左滑取消」。[slide] 是 0（没滑）到 1（到取消线）。
 */
@Composable
fun FeedVoiceRecordingBar(elapsedMs: Long, maxMs: Long, slide: Float, modifier: Modifier = Modifier) {
  val blink by rememberInfiniteTransition(label = "voice-dot").animateFloat(
    initialValue = 1f,
    targetValue = 0.2f,
    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
    label = "voice-dot-alpha"
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp).testTag(FeedPostDetailTestTags.VOICE_RECORDING)
  ) {
    Box(modifier = Modifier.size(10.dp).alpha(blink).background(MaterialTheme.colorScheme.error, CircleShape))
    Spacer(modifier = Modifier.width(8.dp))
    val left = (maxMs - elapsedMs).coerceAtLeast(0)
    Text(
      text = formatVoiceDuration(elapsedMs),
      style = MaterialTheme.typography.bodyLarge,
      color = if (left <= WARN_LEFT_MS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.weight(1f))
    Text(
      text = stringResource(R.string.TellomiFeed__voice_slide_to_cancel),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.graphicsLayer {
        alpha = 1f - slide.coerceIn(0f, 1f) * 0.7f
        translationX = -slide.coerceIn(0f, 1f) * 48.dp.toPx()
      }
    )
  }
}

/** 最后 10 秒计时变红，提醒快到上限。 */
private const val WARN_LEFT_MS = 10_000L

/** 评论框里录音的状态：录没录、录了多久、往左滑了多少。 */
@Stable
class FeedVoiceComposerState internal constructor(
  private val recorder: FeedVoiceRecorder,
  private val onSendVoice: () -> (File) -> Unit
) {
  var recording by mutableStateOf(false)
    private set
  var elapsedMs by mutableLongStateOf(0L)
    internal set
  var slide by mutableFloatStateOf(0f)
    internal set

  fun start(): Boolean {
    val ok = recorder.start()
    elapsedMs = 0L
    slide = 0f
    recording = ok
    return ok
  }

  /** 松手：够长就发，返回 true；太短返回 false。 */
  fun release(): Boolean {
    if (!recording) return true
    recording = false
    val file = recorder.finish() ?: return false
    onSendVoice()(file)
    return true
  }

  fun cancel() {
    if (!recording) return
    recording = false
    recorder.cancel()
  }

  internal fun tick(maxMs: Long) {
    elapsedMs = recorder.elapsedMs
    // 到上限自动发（引擎只收 ≤60 秒）
    if (elapsedMs >= maxMs) release()
  }
}

@Composable
fun rememberFeedVoiceComposer(maxMs: Long, onSendVoice: (File) -> Unit): FeedVoiceComposerState {
  val context = LocalContext.current.applicationContext
  val currentOnSend by rememberUpdatedState(onSendVoice)
  val state = remember { FeedVoiceComposerState(FeedVoiceRecorder(context)) { currentOnSend } }
  LaunchedEffect(state.recording, maxMs) {
    while (state.recording) {
      state.tick(maxMs)
      delay(TICK_MS)
    }
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    // 退到后台就放弃这段录音，不在用户看不到的时候发出去
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.cancel() }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      state.cancel()
    }
  }
  return state
}

/**
 * 按住说话的话筒（照 Telegram `ChatActivityEnterView`：按住 150 ms 才开始录，松手发送，左滑超过取消线就丢掉；
 * 只点一下提示按住）。第一次用先要麦克风权限，给了以后再按住。
 */
@Composable
fun FeedMicButton(voice: FeedVoiceComposerState, onTooShort: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val haptics = LocalHapticFeedback.current
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  val currentOnTooShort by rememberUpdatedState(onTooShort)
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cancelPx = with(LocalDensity.current) { minOf(screenWidth * 0.35f, 140.dp).toPx() }
  val description = stringResource(R.string.TellomiFeed__voice_hold)

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(48.dp)
      .clip(CircleShape)
      .background(if (voice.recording) MaterialTheme.colorScheme.primary else Color.Transparent)
      .semantics {
        contentDescription = description
        role = Role.Button
      }
      .testTag(FeedPostDetailTestTags.MIC)
      .pointerInput(voice, cancelPx) {
        awaitEachGesture {
          val down = awaitFirstDown()
          down.consume()
          if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            waitForUpOrCancellation()
            return@awaitEachGesture
          }
          val releasedEarly = withTimeoutOrNull(FeedVoiceRecorder.HOLD_DELAY_MS) {
            waitForUpOrCancellation()
            true
          } ?: false
          if (releasedEarly) {
            currentOnTooShort()
            return@awaitEachGesture
          }
          if (!voice.start()) return@awaitEachGesture
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)

          var cancelled = false
          while (voice.recording) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            voice.slide = ((down.position.x - change.position.x) / cancelPx).coerceIn(0f, 1f)
            if (voice.slide >= 1f) {
              cancelled = true
              voice.cancel()
              haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
          }
          if (!cancelled && voice.recording && !voice.release()) currentOnTooShort()
        }
      }
  ) {
    Icon(
      imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_mic_24),
      contentDescription = null,
      tint = if (voice.recording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

private const val TICK_MS = 100L
