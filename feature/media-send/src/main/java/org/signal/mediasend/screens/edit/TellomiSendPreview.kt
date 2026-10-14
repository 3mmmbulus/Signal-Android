/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.signal.core.models.media.Media
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.glide.compose.GlideImage
import org.signal.mediasend.test.TestTags
import kotlin.math.roundToInt

/*
 * Tellomi（tellomi/tellomi#1519 M-5，照 Telegram）：有媒体时，长按发送不再只弹一个小菜单，而是整屏压暗：发送键左边是
 * 「发出去是什么样」的气泡（底边和发送键对齐），发送键下面是菜单（右边和气泡对齐）；在菜单里切「说明文字上移 / 下移」，
 * 气泡当场跟着变。点发送键就发，点气泡或空白处收起。
 *
 * 机制照 Telegram 安卓 `MessageSendPreview`（只读，代码是自己写的）：独立的全屏窗口；消息列表的右边贴着发送键左边、底边是发送键底边
 * （`chatListView.setY(pos[1] + sendButton.getHeight() - height)`），菜单从发送键底边往下（`optionsView.setY(pos[1] + sendButton.getHeight())`）；
 * 菜单放不下时发送键连同两样一起往上挪（`pos[1] = bottom - heightdown - height`），消息太高时限高可滚。iOS
 * `ChatSendMessageContextScreen` 同一种摆法（说明在上 / 在下整个气泡重排，菜单不收）。
 * 不同：Telegram 的背景是对下层的模糊截图，这里只压暗——Compose 的对话框模糊不了下层窗口，系统的窗口模糊又不是每台机器都有。
 */

private val BubbleMaxWidth = 260.dp
private val BubbleShape = RoundedCornerShape(18.dp)
private val MenuShape = RoundedCornerShape(18.dp)
private val TileGap = 2.dp
private val Gap = 8.dp
private val MenuGap = 8.dp

/** 发送键画出来的那个圆（`AddAMessageRow` 的 NextButtonSize），触摸区比它大一圈。 */
private val NextButtonVisualSize = 40.dp
private const val ANIMATION_MS = 200

/**
 * 气泡里画哪一句说明，和会话里一致（ADR-0075 §4.4，owner 2026-10-14 照 Telegram）：整条消息的说明加逐张说明，一共只有一句就画那一句；
 * 两句以上只画整条消息的说明（没有就不画），逐张的在查看器里看。
 */
internal fun previewCaption(message: String?, mediaCaptions: List<String?>): String? {
  val whole = message?.takeIf { it.isNotBlank() }
  val captions = listOfNotNull(whole) + mediaCaptions.filterNot { it.isNullOrBlank() }.filterNotNull()
  return when {
    captions.size == 1 -> captions[0]
    else -> whole
  }
}

/** 这块东西在屏幕上的位置；对话框是另一个窗口，两边都换算到屏幕上才对得齐。 */
internal fun LayoutCoordinates.boundsOnScreen(): Rect = Rect(localToScreen(Offset.Zero), size.toSize())

/**
 * @param sendButtonBounds 发送键（连同它左边的间距）在屏幕上的位置，预览里那一个画在同一处。
 * @param sendButton 画在原处的发送键；它被点了要调 `hide`，预览收起（发送本身调用方立刻做，不等动画）。
 */
@Composable
internal fun SendPreviewOverlay(
  media: List<Media>,
  message: String?,
  bubbleColor: Color,
  onBubbleColor: Color,
  sendButtonBounds: Rect,
  captionAboveMedia: Boolean?,
  showScheduleOptions: Boolean,
  onToggleCaptionPosition: () -> Unit,
  onScheduleOption: (ScheduleSendOption) -> Unit,
  onDismiss: () -> Unit,
  sendButton: @Composable (hide: () -> Unit) -> Unit
) {
  val currentOnDismiss by rememberUpdatedState(onDismiss)
  val visible = remember { MutableTransitionState(false).apply { targetState = true } }
  val hide = { visible.targetState = false }

  // 收起的动画放完才真的关掉窗口
  LaunchedEffect(visible.isIdle, visible.currentState) {
    if (visible.isIdle && !visible.currentState && !visible.targetState) {
      currentOnDismiss()
    }
  }

  Dialog(
    onDismissRequest = hide,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
  ) {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
      dialogWindow?.setDimAmount(0f)
      dialogWindow?.setBackgroundDrawable(ColorDrawable(0))
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val insets = WindowInsets.safeDrawing

    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(ANIMATION_MS)), exit = fadeOut(tween(ANIMATION_MS))) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .onGloballyPositioned { origin = it.localToScreen(Offset.Zero) }
          .testTag(TestTags.SEND_PREVIEW)
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = hide)
            .testTag(TestTags.SEND_PREVIEW_SCRIM)
        )

        Layout(
          content = {
            // 气泡太高时限高，从底部（挨着发送键的那头）开始看
            Box(
              modifier = Modifier
                .verticalScroll(rememberScrollState(), reverseScrolling = true)
                .animateEnterExit(
                  enter = scaleIn(tween(ANIMATION_MS), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 1f)),
                  exit = scaleOut(tween(ANIMATION_MS), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 1f))
                )
            ) {
              PreviewBubble(
                media = media,
                caption = previewCaption(message, media.map { it.caption }),
                captionAboveMedia = captionAboveMedia == true,
                bubbleColor = bubbleColor,
                textColor = onBubbleColor,
                onClick = hide
              )
            }
            Box(
              modifier = Modifier.animateEnterExit(
                enter = scaleIn(tween(ANIMATION_MS), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
                exit = scaleOut(tween(ANIMATION_MS), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
              )
            ) {
              SendMenuCard(
                captionAboveMedia = captionAboveMedia,
                showScheduleOptions = showScheduleOptions,
                onToggleCaptionPosition = onToggleCaptionPosition,
                onScheduleOption = { option ->
                  hide()
                  onScheduleOption(option)
                }
              )
            }
            Box { sendButton(hide) }
          }
        ) { measurables, constraints ->
          val anchor = sendButtonBounds.translate(-origin)
          val margin = Gap.roundToPx()
          val ltr = layoutDirection == LayoutDirection.Ltr
          val buttonWidth = anchor.width.roundToInt().coerceAtLeast(0)
          val buttonHeight = anchor.height.roundToInt().coerceAtLeast(0)
          val button = measurables[2].measure(Constraints.fixed(buttonWidth, buttonHeight))
          // 圆按钮画得比触摸区小一圈（触摸区前面还有一段间距）：气泡、菜单都对着画出来的那个圆——右边离圆左边 Gap，
          // 气泡底边是圆的底边，菜单从它下面开始
          val buttonInset = ((buttonHeight - NextButtonVisualSize.roundToPx()) / 2).coerceAtLeast(0)
          val circleStart = if (ltr) anchor.right.roundToInt() - buttonInset - NextButtonVisualSize.roundToPx() else anchor.left.roundToInt() + buttonInset + NextButtonVisualSize.roundToPx()
          val edge = if (ltr) circleStart - margin else circleStart + margin
          val room = (if (ltr) edge else constraints.maxWidth - edge) - margin
          val menu = measurables[1].measure(Constraints(maxWidth = room.coerceIn(0, constraints.maxWidth)))

          val top = insets.getTop(this) + margin
          val bottom = constraints.maxHeight - insets.getBottom(this) - margin
          val menuGap = MenuGap.roundToPx()
          var buttonTop = anchor.top.roundToInt()
          val overflow = buttonTop + buttonHeight - buttonInset + menuGap + menu.height - bottom
          if (overflow > 0) {
            buttonTop -= overflow
          }
          val bubbleBottom = buttonTop + buttonHeight - buttonInset
          val bubble = measurables[0].measure(
            Constraints(maxWidth = room.coerceIn(0, constraints.maxWidth), maxHeight = (bubbleBottom - top).coerceAtLeast(0))
          )

          layout(constraints.maxWidth, constraints.maxHeight) {
            bubble.place(if (ltr) edge - bubble.width else edge, bubbleBottom - bubble.height)
            menu.place(if (ltr) edge - menu.width else edge, bubbleBottom + menuGap)
            button.place(anchor.left.roundToInt(), buttonTop)
          }
        }
      }
    }
  }
}

@Composable
private fun PreviewBubble(
  media: List<Media>,
  caption: String?,
  captionAboveMedia: Boolean,
  bubbleColor: Color,
  textColor: Color,
  onClick: () -> Unit
) {
  AnimatedContent(
    targetState = captionAboveMedia,
    transitionSpec = { fadeIn(tween(ANIMATION_MS)) togetherWith fadeOut(tween(ANIMATION_MS)) using SizeTransform(clip = false) },
    label = "captionPosition"
  ) { above ->
    Column(
      modifier = Modifier
        .widthIn(max = BubbleMaxWidth)
        .clip(BubbleShape)
        .background(bubbleColor)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .testTag(TestTags.SEND_PREVIEW_BUBBLE)
    ) {
      if (caption != null && above) {
        CaptionText(caption, textColor, Modifier.testTag(TestTags.SEND_PREVIEW_CAPTION_ABOVE))
      }
      MediaMosaic(media = media, width = BubbleMaxWidth)
      if (caption != null && !above) {
        CaptionText(caption, textColor, Modifier.testTag(TestTags.SEND_PREVIEW_CAPTION_BELOW))
      }
    }
  }
}

@Composable
private fun CaptionText(caption: String, color: Color, modifier: Modifier) {
  Text(
    text = caption,
    color = color,
    style = MaterialTheme.typography.bodyLarge,
    modifier = modifier
      .width(BubbleMaxWidth)
      .padding(horizontal = 12.dp, vertical = 8.dp)
  )
}

/**
 * 和会话里的相册同一种排法（1 张按比例、左右 2 张、左大右二、2 × 2、上 2 下 3，多出来的在最后一格写「+N」），这样预览就是
 * 对方会看到的样子。
 */
@Composable
private fun MediaMosaic(media: List<Media>, width: Dp) {
  val half = (width - TileGap) / 2
  when (media.size) {
    0 -> Unit
    1 -> {
      val item = media[0]
      val aspect = if (item.width > 0 && item.height > 0) (item.width.toFloat() / item.height).coerceIn(0.6f, 1.8f) else 1f
      Tile(item, DpSize(width, width / aspect))
    }
    2 -> Row(horizontalArrangement = spacedBy(TileGap)) {
      media.forEach { Tile(it, DpSize(half, half)) }
    }
    3 -> Row(horizontalArrangement = spacedBy(TileGap)) {
      Tile(media[0], DpSize(half, half * 2 + TileGap))
      Column(verticalArrangement = spacedBy(TileGap)) {
        Tile(media[1], DpSize(half, half))
        Tile(media[2], DpSize(half, half))
      }
    }
    4 -> Column(verticalArrangement = spacedBy(TileGap)) {
      media.chunked(2).forEach { row ->
        Row(horizontalArrangement = spacedBy(TileGap)) { row.forEach { Tile(it, DpSize(half, half)) } }
      }
    }
    else -> Column(verticalArrangement = spacedBy(TileGap)) {
      val third = (width - TileGap * 2) / 3
      Row(horizontalArrangement = spacedBy(TileGap)) { media.take(2).forEach { Tile(it, DpSize(half, half)) } }
      Row(horizontalArrangement = spacedBy(TileGap)) {
        media.drop(2).take(3).forEachIndexed { index, item ->
          Tile(item, DpSize(third, third), more = if (index == 2) media.size - 5 else 0)
        }
      }
    }
  }
}

@Composable
private fun Tile(media: Media, size: DpSize, more: Int = 0) {
  Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
    if (LocalInspectionMode.current) {
      Box(modifier = Modifier.fillMaxSize().background(Color.Gray))
    } else {
      GlideImage(model = media.uri, imageSize = size, modifier = Modifier.fillMaxSize())
    }
    if (more > 0) {
      Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
        Text(text = "+$more", color = Color.White, style = MaterialTheme.typography.titleLarge)
      }
    }
  }
}

@Composable
private fun SendMenuCard(
  captionAboveMedia: Boolean?,
  showScheduleOptions: Boolean,
  onToggleCaptionPosition: () -> Unit,
  onScheduleOption: (ScheduleSendOption) -> Unit
) {
  Surface(
    shape = MenuShape,
    color = SignalTheme.colors.colorSurface2,
    shadowElevation = 3.dp,
    modifier = Modifier
      .widthIn(min = 220.dp)
      .testTag(TestTags.SEND_PREVIEW_MENU)
  ) {
    // 和下拉菜单一样按最宽的那一项定宽，不撑满
    Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
      if (captionAboveMedia != null) {
        CaptionPositionMenuItem(captionAboveMedia = captionAboveMedia, onClick = onToggleCaptionPosition)
        if (showScheduleOptions) {
          HorizontalDivider()
        }
      }
      if (showScheduleOptions) {
        ScheduleSendOptions(
          options = TellomiScheduleSendOptions,
          onOptionClick = onScheduleOption
        )
      }
    }
  }
}
