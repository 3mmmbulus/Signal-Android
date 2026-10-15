/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.transfercontrols

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.clickableContainer
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.R
import org.signal.core.ui.R as CoreUiR

/**
 * A button that can be used to start, cancel, show progress, and show completion of a data transfer.
 */
@Composable
fun TransferProgressIndicator(
  state: TransferProgressState,
  modifier: Modifier = Modifier,
  size: Dp = 48.dp
) {
  // Internal paddings are tuned for a 48dp control; scale them with [size] so the icon/ring proportions are preserved at
  // other sizes. At 48dp this is a no-op, so existing callers are unaffected.
  val scale = size / 48.dp
  val sizedModifier = modifier.size(size)

  AnimatedContent(
    targetState = state,
    // Key on the state type, not the value, so that progress updates within InProgress recompose in place instead of
    // re-triggering the enter/exit transition on every tick (which would prevent the determinate fill from ever settling).
    contentKey = { it::class },
    // Tellomi（交互审计 A-13「下载键约 1 秒才退完」）：原来 200 ms 延迟再 400–800 ms 补间，换个状态要一秒左右才走完；
    // 改成标准里的交叉淡入（透明度 Fade、缩放 Snap，不另写时长、不延迟）：点下载马上换成进度环，下完马上换成完成图标。
    transitionSpec = {
      val enterTransition = fadeIn(TellomiMotion.spec(TellomiMotion.Fade)) + scaleIn(TellomiMotion.spec(TellomiMotion.Snap), initialScale = STATE_SWAP_SCALE)
      val exitTransition = fadeOut(TellomiMotion.spec(TellomiMotion.Fade)) + scaleOut(TellomiMotion.spec(TellomiMotion.Snap), targetScale = STATE_SWAP_SCALE)
      enterTransition
        .togetherWith(exitTransition)
        .using(SizeTransform(clip = false))
    }
  ) { targetState ->
    when (targetState) {
      is TransferProgressState.Ready -> StartTransferButton(targetState, sizedModifier, scale)
      is TransferProgressState.InProgress -> ProgressIndicator(targetState, sizedModifier, scale)
      is TransferProgressState.Complete -> CompleteIcon(targetState, sizedModifier, scale)
    }
  }
}

@Composable
private fun StartTransferButton(
  state: TransferProgressState.Ready,
  modifier: Modifier = Modifier,
  scale: Float = 1f
) {
  Box(
    modifier = modifier
      .then(
        if (state.onStartClick != null) {
          Modifier.clickableContainer(
            contentDescription = state.startButtonContentDesc,
            onClickLabel = state.startButtonOnClickLabel,
            onClick = state.onStartClick
          )
        } else {
          Modifier
        }
      )
  ) {
    Icon(
      imageVector = state.icon,
      tint = MaterialTheme.colorScheme.onSurface,
      contentDescription = null,
      modifier = Modifier
        .matchParentSize()
        .padding(12.dp * scale)
    )
  }
}

@Composable
private fun ProgressIndicator(
  state: TransferProgressState.InProgress,
  modifier: Modifier = Modifier,
  scale: Float = 1f
) {
  Box(
    modifier = modifier
      .then(
        if (state.cancelAction != null) {
          Modifier.clickableContainer(
            contentDescription = null,
            onClickLabel = state.cancelAction.onClickLabel,
            onClick = state.cancelAction.onClick
          )
        } else {
          Modifier
        }
      )
      .padding(10.dp * scale)
  ) {
    state.icon?.let { icon ->
      Icon(
        imageVector = icon,
        tint = MaterialTheme.colorScheme.onSurface,
        contentDescription = null,
        modifier = Modifier
          .matchParentSize()
          .padding(6.dp * scale)
      )
    }

    val indicatorModifier = Modifier
      .matchParentSize()
      .then(
        if (state.cancelAction != null) {
          Modifier.clearAndSetSemantics {
            contentDescription = state.cancelAction.contentDesc
          }
        } else {
          Modifier
        }
      )

    val progress = state.progress?.let { animatedProgress(it) }
    if (progress == null) {
      CircularProgressIndicator(
        strokeWidth = 2.dp,
        strokeCap = StrokeCap.Round,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = indicatorModifier
      )
    } else {
      CircularProgressIndicator(
        progress = { progress },
        strokeWidth = 2.dp,
        strokeCap = StrokeCap.Round,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = indicatorModifier
      )
    }

    if (state.cancelAction != null) {
      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .fillMaxSize(0.3f)
          .clip(RoundedCornerShape(percent = 15))
          .background(MaterialTheme.colorScheme.onSurface)
      )
    }
  }
}

/** Tellomi（交互审计 A-13）：换状态时旧的缩到、新的从这个比例开始，配合交叉淡入。 */
private const val STATE_SWAP_SCALE = 0.8f

/**
 * Tellomi（交互审计 A-13「进度环约 10 帧跳格」）：进度从视图那边每 100 ms 才来一次，环按帧插值过去，不再一格一格跳。
 * 临界阻尼（不回弹、不越过真实进度），刚度取 [TellomiMotion.Fade]，跟得上 100 ms 一次的更新。
 */
@Composable
private fun animatedProgress(target: Float): Float {
  val animated by animateFloatAsState(
    targetValue = target,
    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = TellomiMotion.Fade.stiffness),
    label = "transfer-progress"
  )
  return animated
}

@Composable
private fun CompleteIcon(
  state: TransferProgressState.Complete,
  modifier: Modifier = Modifier,
  scale: Float = 1f
) {
  Icon(
    imageVector = state.icon,
    tint = state.tint,
    contentDescription = state.iconContentDesc,
    modifier = modifier.padding(12.dp * scale)
  )
}

sealed interface TransferProgressState {
  data class Ready(
    val icon: ImageVector,
    val startButtonContentDesc: String,
    val startButtonOnClickLabel: String,
    val onStartClick: (() -> Unit)?
  ) : TransferProgressState

  data class InProgress(
    val icon: ImageVector? = null,
    val progress: Float? = null,
    val cancelAction: CancelAction? = null
  ) : TransferProgressState {

    data class CancelAction(
      val contentDesc: String,
      val onClickLabel: String,
      val onClick: () -> Unit
    )
  }

  data class Complete(
    val icon: ImageVector,
    val iconContentDesc: String,
    val tint: Color
  ) : TransferProgressState
}

@DayNightPreviews
@Composable
private fun TransferProgressIndicatorReadyPreview() {
  Previews.Preview {
    PreviewBackdrop {
      TransferProgressIndicator(
        state = TransferProgressState.Ready(
          icon = ImageVector.vectorResource(R.drawable.symbol_arrow_down_24),
          startButtonContentDesc = "",
          startButtonOnClickLabel = "",
          onStartClick = {}
        )
      )
    }
  }
}

@DayNightPreviews
@Composable
private fun TransferProgressIndicatorIndeterminatePreview() {
  Previews.Preview {
    PreviewBackdrop {
      TransferProgressIndicator(
        state = TransferProgressState.InProgress(
          icon = ImageVector.vectorResource(R.drawable.symbol_arrow_down_24),
          progress = null,
          cancelAction = null
        )
      )
    }
  }
}

@DayNightPreviews
@Composable
private fun TransferProgressIndicatorDeterminatePreview() {
  Previews.Preview {
    PreviewBackdrop {
      TransferProgressIndicator(
        state = TransferProgressState.InProgress(
          icon = ImageVector.vectorResource(R.drawable.symbol_arrow_down_24),
          progress = 0.4f,
          cancelAction = null
        )
      )
    }
  }
}

@DayNightPreviews
@Composable
private fun TransferProgressIndicatorCancelablePreview() {
  Previews.Preview {
    PreviewBackdrop {
      TransferProgressIndicator(
        state = TransferProgressState.InProgress(
          progress = 0.4f,
          cancelAction = TransferProgressState.InProgress.CancelAction(
            contentDesc = "",
            onClickLabel = "",
            onClick = {}
          )
        )
      )
    }
  }
}

@DayNightPreviews
@Composable
private fun TransferProgressIndicatorCompletePreview() {
  Previews.Preview {
    PreviewBackdrop {
      TransferProgressIndicator(
        state = TransferProgressState.Complete(
          icon = ImageVector.vectorResource(R.drawable.symbol_check_white_24),
          iconContentDesc = "",
          tint = MaterialTheme.colorScheme.onSurface
        )
      )
    }
  }
}

@Composable
private fun PreviewBackdrop(content: @Composable () -> Unit) {
  Box(
    modifier = Modifier
      .size(96.dp)
      .background(colorResource(CoreUiR.color.signal_colorTransparent2))
  ) {
    content()
  }
}
