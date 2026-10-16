/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.theme.LocalSnackbarColors
import kotlin.math.ceil

/**
 * Tellomi（交互审计 A-30「撤销形态」，安卓半边）：可撤销的提示条左边一个倒计时环，中间是剩下的整秒数，走完提示条收起。
 *
 * 环和秒数的做法照 Telegram 安卓 `UndoView`（只读机制）：环从满走到空，秒数向上取整，换数字时旧的往下 10dp 淡出、新的从上面淡入（150 ms）；
 * 可撤销的停 5 s（`timeLeft = 5000`）。和 Tellomi iOS（Signal-iOS#251）同一个样子。
 * 时间按帧时钟走，不靠动画：「移除动画」档下环照样一格一格走，只是数字换的时候不滑。
 */
object TellomiCountdownSnackbar {

  /** 可撤销的提示条停多久（同 Telegram、Tellomi iOS）。 */
  const val UNDO_MILLIS = 5_000L

  const val RING_TEST_TAG = "tellomi_snackbar_countdown"

  /** 剩下的整秒数：向上取整，刚开始是 5，最后一秒是 1。 */
  fun displayedSeconds(remainingMillis: Long): Int {
    return if (remainingMillis <= 0) 0 else ceil(remainingMillis / 1000.0).toInt()
  }

  /** 倒计时提示条带的东西：一直显示（[SnackbarDuration.Indefinite]），到点由 [showCountdownSnackbar] 收起。 */
  class Visuals(
    override val message: String,
    override val actionLabel: String?,
    val countdownMillis: Long
  ) : SnackbarVisuals {
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
    override val withDismissAction: Boolean = false
  }

  /**
   * 画倒计时提示条：和 [Snackbars.Default] 同样的颜色、形状、阴影和外边距，一行排开：环、文字、「撤销」。
   * 不用 Material 的 `Snackbar(content)`：它按内容的首末基线判断一行还是两行，环里的数字也有基线，会被当成两行排高、「撤销」掉到下面（模拟器实测）。
   */
  @Composable
  fun Default(snackbarData: SnackbarData, visuals: Visuals) {
    val colors = LocalSnackbarColors.current
    Surface(
      modifier = Modifier.padding(12.dp),
      shape = SnackbarDefaults.shape,
      color = colors.color,
      contentColor = colors.contentColor,
      shadowElevation = 6.dp
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .padding(start = 16.dp, end = 8.dp)
      ) {
        Ring(totalMillis = visuals.countdownMillis, color = colors.contentColor)
        Spacer(Modifier.width(12.dp))
        Text(
          text = visuals.message,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier
            .weight(1f)
            .padding(vertical = 14.dp)
        )
        visuals.actionLabel?.let { label ->
          TextButton(
            colors = ButtonDefaults.textButtonColors(contentColor = colors.actionColor),
            onClick = { snackbarData.performAction() }
          ) {
            Text(label)
          }
        }
      }
    }
  }

  @Composable
  private fun Ring(totalMillis: Long, color: Color) {
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(totalMillis) {
      val start = withFrameMillis { it }
      while (elapsedMillis < totalMillis) {
        elapsedMillis = withFrameMillis { it } - start
      }
    }
    val remainingMillis = (totalMillis - elapsedMillis).coerceAtLeast(0)
    val fraction = if (totalMillis > 0) remainingMillis.toFloat() / totalMillis else 0f

    Box(
      contentAlignment = Alignment.Center,
      // 读屏不念环和数字（提示条自己的文字、「撤销」照常念）
      modifier = Modifier
        .size(22.dp)
        .testTag(RING_TEST_TAG)
        .semantics { hideFromAccessibility() }
    ) {
      Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(color = color.copy(alpha = 0.25f), radius = size.minDimension / 2 - stroke / 2, style = Stroke(width = stroke))
        drawArc(
          color = color,
          startAngle = -90f,
          sweepAngle = 360f * fraction,
          useCenter = false,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(size.width - stroke, size.height - stroke),
          style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
      }
      AnimatedContent(
        targetState = displayedSeconds(remainingMillis).coerceAtLeast(1),
        transitionSpec = {
          (slideInVertically(tween(DIGIT_MILLIS)) { -it / 2 } + fadeIn(tween(DIGIT_MILLIS))) togetherWith
            (slideOutVertically(tween(DIGIT_MILLIS)) { it / 2 } + fadeOut(tween(DIGIT_MILLIS)))
        },
        label = "tellomiCountdownDigits"
      ) { seconds ->
        Text(text = seconds.toString(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
      }
    }
  }

  private const val DIGIT_MILLIS = 150
}

/**
 * Tellomi（交互审计 A-30）：显示一条可撤销的倒计时提示条，[countdownMillis] 到了自己收起（和 [showSnackbar] 的自定义时长同一个做法）。
 */
suspend fun SnackbarHostState.showCountdownSnackbar(
  message: String,
  actionLabel: String?,
  countdownMillis: Long
): SnackbarResult {
  return coroutineScope {
    val dismissJob = launch {
      delay(countdownMillis)
      currentSnackbarData?.dismiss()
    }
    val result = showSnackbar(TellomiCountdownSnackbar.Visuals(message, actionLabel, countdownMillis))
    dismissJob.cancel()
    result
  }
}
