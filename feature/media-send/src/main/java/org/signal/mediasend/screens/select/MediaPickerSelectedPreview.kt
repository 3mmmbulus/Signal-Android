/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.signal.core.models.media.Media
import org.signal.core.ui.albumcarousel.AlbumCarouselGeometry
import org.signal.core.ui.compose.LocalChatWallpaper
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.ContentTypeUtil
import org.signal.glide.compose.GlideImage
import org.signal.mediasend.R
import org.signal.mediasend.test.TestTags
import org.signal.mediasend.util.formatAsClock
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tellomi（tellomi/tellomi#1261 P-3）：点「✓N」之后，选图网格换成「只看已选」。
 *
 * 机制照 Telegram（Android `ChatAttachAlertPhotoLayoutPreview`、iOS `MediaPickerSelectedListNode`）：会话的聊天背景上，
 * ≥ 2 张时顶部小字「拖动可调整顺序」，只有 1 张时顶部什么都不提示；下面一行卡片只当排序区（owner 2026-09-25 选 A：
 * 聊天里的横滑已撤回、改回宫格，这一行不再是「发出去的样子」；它在选图面板里面，不挡返回手势）：
 * [AlbumCarouselGeometry] 的行高、按原比例的宽、间距 8、圆角 18、放得下时靠右，松手吸附也照它；
 * 有说明时下面一个发出方向的气泡。
 * - 长按（系统的长按时长，同 Telegram 安卓 `ChatAttachAlertPhotoLayoutPreview` 用 `ViewConfiguration.getLongPressTimeout()`；
 *   无障碍里调过「触摸和按住延迟」的跟着变，tellomi/tellomi#1521 G-9）拖动排序，排序 = 发出去的顺序：拿起的那张跟着手指走，其它卡片实时让位，贴近两端时整行自动滚；
 *   松手时报一次 from → to。
 * - 每张右上角的编号勾：点了取消选择（撤销条由外面弹）；点卡片本身进编辑器、定位到这一张。
 *
 * Telegram 的实现只读机制、一行都没搬（GPLv2）。
 */
@Composable
internal fun MediaPickerSelectedPreview(
  selectedMedia: List<Media>,
  caption: CharSequence?,
  recipientId: Long?,
  recipientChatColor: Color?,
  onOpen: (Media) -> Unit,
  onDeselect: (Media) -> Unit,
  onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
  modifier: Modifier = Modifier
) {
  Box(modifier = modifier.testTag(TestTags.MEDIA_PICKER_SELECTED_PREVIEW)) {
    LocalChatWallpaper.current(recipientId, Modifier.matchParentSize())

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(vertical = 16.dp)
    ) {
      if (selectedMedia.size >= 2) {
        PreviewChip(text = stringResource(R.string.MediaSelectScreen__drag_to_reorder))
      }
      Spacer(modifier = Modifier.height(16.dp))

      if (selectedMedia.isNotEmpty()) {
        PreviewRow(
          selectedMedia = selectedMedia,
          color = recipientChatColor ?: MaterialTheme.colorScheme.primary,
          onOpen = onOpen,
          onDeselect = onDeselect,
          onReorder = onReorder
        )
      }

      if (!caption.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        CaptionBubble(
          caption = caption,
          recipientChatColor = recipientChatColor,
          modifier = Modifier
            .align(Alignment.End)
            .padding(end = AlbumCarouselGeometry.END_MARGIN_DP.dp)
        )
      }
    }
  }
}

/** 同会话里的日期标签：小字胶囊，盖在聊天背景上。 */
@Composable
private fun PreviewChip(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
      .testTag(TestTags.MEDIA_PICKER_PREVIEW_CHIP)
      .clip(RoundedCornerShape(percent = 50))
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
      .padding(horizontal = 10.dp, vertical = 4.dp)
  )
}

/** 说明：发出方向的气泡，颜色同会话；最宽 3/4 屏宽，靠右。 */
@Composable
private fun CaptionBubble(caption: CharSequence, recipientChatColor: Color?, modifier: Modifier = Modifier) {
  val bubbleColor = recipientChatColor ?: MaterialTheme.colorScheme.primary
  val textColor = if (recipientChatColor != null) SignalTheme.colors.colorOnCustom else MaterialTheme.colorScheme.onPrimary
  val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.75f
  Text(
    text = caption.toString(),
    style = MaterialTheme.typography.bodyLarge,
    color = textColor,
    modifier = modifier
      .widthIn(max = maxWidth)
      .clip(RoundedCornerShape(18.dp))
      .background(bubbleColor)
      .padding(horizontal = 12.dp, vertical = 8.dp)
      .testTag(TestTags.MEDIA_PICKER_PREVIEW_CAPTION)
  )
}

/** 拖动排序的进行状态：拿着哪一张、它（卡片左边）在内容坐标里的位置、手指在视口里的位置。 */
@Stable
private class PreviewDragState {
  var uri by mutableStateOf<Uri?>(null)
  var left by mutableFloatStateOf(0f)
  var fingerInViewport by mutableFloatStateOf(0f)
  var touchOffset = 0f
  var originalIndex = -1
}

@Composable
private fun PreviewRow(
  selectedMedia: List<Media>,
  color: Color,
  onOpen: (Media) -> Unit,
  onDeselect: (Media) -> Unit,
  onReorder: (fromIndex: Int, toIndex: Int) -> Unit
) {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptics = LocalHapticFeedback.current
    val viewportWidth = constraints.maxWidth

    // 行高照 AlbumCarouselGeometry（聊天里撤回横滑之前用的同一套）：按宽算，横屏与平板再按屏高封顶。
    val capByScreenHeight = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.smallestScreenWidthDp >= 600
    val rowHeightDp = AlbumCarouselGeometry.rowHeightDp(maxWidth.value, configuration.screenHeightDp.toFloat(), capByScreenHeight)
    val rowHeight = with(density) { rowHeightDp.dp.roundToPx() }

    val drag = remember { PreviewDragState() }
    // 拖动中（和松手后、等外面按新顺序更新之前）用自己的顺序，免得松手那一下闪回旧顺序。
    var localOrder by remember { mutableStateOf<List<Media>?>(null) }
    LaunchedEffect(selectedMedia) {
      val pending = localOrder
      if (drag.uri == null && (pending == null || pending.map { it.uri } == selectedMedia.map { it.uri } || pending.size != selectedMedia.size)) {
        localOrder = null
      }
    }
    val order = localOrder ?: selectedMedia

    val startInset = with(density) { PREVIEW_START_INSET_DP.dp.roundToPx() }
    val endMargin = with(density) { AlbumCarouselGeometry.END_MARGIN_DP.dp.roundToPx() }
    val spacing = with(density) { AlbumCarouselGeometry.ITEM_SPACING_DP.dp.roundToPx() }
    val minNextPeek = with(density) { AlbumCarouselGeometry.NEXT_ITEM_MIN_PEEK_DP.dp.roundToPx() }
    val layoutFor: (List<Media>) -> AlbumCarouselGeometry.Layout = { media ->
      AlbumCarouselGeometry.layout(
        viewportWidth = viewportWidth,
        startInset = startInset,
        endMargin = endMargin,
        spacing = spacing,
        minNextPeek = minNextPeek,
        rowHeight = rowHeight,
        aspectRatios = media.map { AlbumCarouselGeometry.aspectRatio(it.width, it.height) },
        alignEndWhenFits = true
      )
    }
    val layout = layoutFor(order)

    // 手势协程只在第一次组合时起一次，里面读到的组合期的值会过时：顺序直接读 localOrder（状态对象，随时是新的），
    // 外面给的选中顺序与布局函数经 rememberUpdatedState（每次重组更新；一次拖动当中不变）。
    val currentSelected by rememberUpdatedState(selectedMedia)
    val currentLayoutFor by rememberUpdatedState(layoutFor)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val liveOrder: () -> List<Media> = { localOrder ?: currentSelected }
    val liveLayout: () -> AlbumCarouselGeometry.Layout = { currentLayoutFor(liveOrder()) }

    val scrollState = rememberScrollState()
    val decay = rememberSplineBasedDecay<Float>()
    val flingBehavior = remember(scrollState, decay) { CarouselSnapFlingBehavior(liveLayout, scrollState, decay) }

    fun moveDraggedToFinger() {
      val uri = drag.uri ?: return
      drag.left = drag.fingerInViewport + scrollState.value - drag.touchOffset
      val order = liveOrder()
      val layoutNow = currentLayoutFor(order)
      val from = order.indexOfFirst { it.uri == uri }
      if (from < 0) return
      val target = PreviewReorder.targetIndex(layoutNow, from, drag.left + layoutNow.itemWidths[from] / 2f)
      if (target != from) {
        localOrder = order.toMutableList().apply { add(target, removeAt(from)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
      }
    }

    // 贴近两端：每帧滚一步，拿着的那张跟着手指（手指没动，底下的内容动了）重新换位。
    val autoScrollZone = with(density) { AUTO_SCROLL_ZONE_DP.dp.toPx() }
    val autoScrollMaxSpeed = with(density) { AUTO_SCROLL_MAX_SPEED_DP.dp.toPx() }
    LaunchedEffect(drag.uri) {
      while (drag.uri != null) {
        withFrameNanos { }
        val step = PreviewReorder.autoScrollStep(drag.fingerInViewport, viewportWidth.toFloat(), autoScrollZone, autoScrollMaxSpeed)
        if (step != 0f && liveLayout().isScrollable) {
          scrollState.scrollBy(step)
          moveDraggedToFinger()
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(rowHeightDp.dp)
        .horizontalScroll(scrollState, flingBehavior = flingBehavior)
        .testTag(TestTags.MEDIA_PICKER_PREVIEW_ROW)
    ) {
      Box(
        modifier = Modifier
          .width(with(density) { layout.contentWidth.toDp() })
          .height(rowHeightDp.dp)
          .pointerInput(Unit) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              val startOrder = liveOrder()
              val startLayout = currentLayoutFor(startOrder)
              val index = PreviewReorder.indexAt(startLayout, down.position.x)
              if (startOrder.size < 2 || index == null) return@awaitEachGesture

              // 长按时长内抬起或挪开：是点按或横滑，交给卡片和这一行。
              val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitLiftOrMove(down, viewConfiguration.touchSlop) }
              if (released != null) return@awaitEachGesture

              val media = startOrder[index]
              drag.uri = media.uri
              drag.originalIndex = index
              drag.touchOffset = down.position.x - startLayout.itemLefts[index]
              drag.fingerInViewport = down.position.x - scrollState.value
              drag.left = startLayout.itemLefts[index].toFloat()
              localOrder = startOrder
              haptics.performHapticFeedback(HapticFeedbackType.LongPress)

              while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                drag.fingerInViewport = change.position.x - scrollState.value
                moveDraggedToFinger()
              }

              val finalIndex = liveOrder().indexOfFirst { it.uri == media.uri }
              val originalIndex = drag.originalIndex
              drag.uri = null
              if (finalIndex >= 0 && finalIndex != originalIndex) {
                currentOnReorder(originalIndex, finalIndex)
              }
            }
          }
      ) {
        order.forEachIndexed { index, media ->
          key(media.uri) {
            val isDragged = drag.uri == media.uri
            val animatedLeft by animateIntAsState(targetValue = layout.itemLefts[index], label = "previewCardLeft")
            val width = with(density) { layout.itemWidths[index].toDp() }
            PreviewCard(
              media = media,
              number = index + 1,
              color = color,
              size = DpSize(width, rowHeightDp.dp),
              onOpen = { onOpen(media) },
              onDeselect = { onDeselect(media) },
              modifier = Modifier
                .offset { IntOffset(if (isDragged) drag.left.roundToInt() else animatedLeft, 0) }
                .zIndex(if (isDragged) 1f else 0f)
                .graphicsLayer {
                  if (isDragged) {
                    scaleX = LIFT_SCALE
                    scaleY = LIFT_SCALE
                    shadowElevation = 12.dp.toPx()
                    shape = RoundedCornerShape(AlbumCarouselGeometry.ITEM_CORNER_RADIUS_DP.dp)
                  }
                }
            )
          }
        }
      }
    }
  }
}

/** 在长按时长到之前：抬起或挪出触摸容差就返回 true（不是长按）。 */
private suspend fun AwaitPointerEventScope.awaitLiftOrMove(down: PointerInputChange, touchSlop: Float): Boolean {
  while (true) {
    val event = awaitPointerEvent()
    val change = event.changes.firstOrNull { it.id == down.id } ?: return true
    if (!change.pressed) return true
    if ((change.position - down.position).getDistance() > touchSlop) return true
  }
}

@Composable
private fun PreviewCard(
  media: Media,
  number: Int,
  color: Color,
  size: DpSize,
  onOpen: () -> Unit,
  onDeselect: () -> Unit,
  modifier: Modifier = Modifier
) {
  val openLabel = stringResource(R.string.MediaSelectScreen__open_media)
  Box(
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(AlbumCarouselGeometry.ITEM_CORNER_RADIUS_DP.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onOpen, onClickLabel = openLabel, role = Role.Button)
      .testTag(TestTags.mediaPickerPreviewCard(media.uri.toString()))
  ) {
    if (LocalInspectionMode.current) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(color = Previews.rememberRandomColor())
      )
    } else {
      GlideImage(
        model = media.uri,
        imageSize = size,
        modifier = Modifier.fillMaxSize()
      )
    }

    if (ContentTypeUtil.isVideo(media.contentType) && !media.isVideoGif) {
      Text(
        text = remember(media.duration) { media.duration.milliseconds.formatAsClock() },
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 4f)),
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 10.dp, bottom = 8.dp)
      )
    }

    SelectionCheck(
      selectionIndex = number - 1,
      color = color,
      onClick = onDeselect,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = 6.dp, end = 6.dp)
        .testTag(TestTags.mediaPickerPreviewCheck(media.uri.toString()))
    )
  }
}

/** P-3：「已取消选择 N 张 · 撤销」，深色圆角条（同 Telegram 的撤销提示），在说明栏上面。 */
@Composable
internal fun DeselectionUndoBar(count: Int, onUndo: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .height(48.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(Color(0xF21F1F1F))
      .padding(start = 16.dp, end = 4.dp)
      .testTag(TestTags.MEDIA_PICKER_UNDO_BAR)
  ) {
    Text(
      text = pluralStringResource(R.plurals.MediaSelectScreen__n_deselected, count, count),
      color = Color.White,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f)
    )
    TextButton(onClick = onUndo) {
      Text(
        text = stringResource(R.string.MediaSelectScreen__undo),
        color = Color(0xFF8AB4FF),
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

/**
 * 横滑那一行的松手吸附，照聊天里撤回横滑之前用的同一套（[AlbumCarouselGeometry.Layout.targetSnapOffset]）：
 * 慢慢松手停在最近的一张；甩的时候按甩出去的落点找，但至少朝甩的方向走一格。
 */
private class CarouselSnapFlingBehavior(
  private val layout: () -> AlbumCarouselGeometry.Layout,
  private val scrollState: ScrollState,
  private val decay: DecayAnimationSpec<Float>
) : FlingBehavior {
  override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
    val current = scrollState.value
    val projected = decay.calculateTargetValue(current.toFloat(), initialVelocity).roundToInt()
    val velocitySign = when {
      initialVelocity > FLING_SIGN_THRESHOLD -> 1
      initialVelocity < -FLING_SIGN_THRESHOLD -> -1
      else -> 0
    }
    val target = layout().targetSnapOffset(current, projected, velocitySign)
    var last = current.toFloat()
    animate(initialValue = last, targetValue = target.toFloat(), initialVelocity = initialVelocity) { value, _ ->
      scrollBy(value - last)
      last = value
    }
    return 0f
  }
}

/** 排序与自动滚的计算（不依赖 Compose，单测直接喂数）。 */
internal object PreviewReorder {

  /** 内容坐标 [x] 落在第几张上（间隙里算没落上）。 */
  fun indexAt(layout: AlbumCarouselGeometry.Layout, x: Float): Int? {
    for (i in 0 until layout.itemCount) {
      if (x >= layout.itemLefts[i] && x < layout.itemLefts[i] + layout.itemWidths[i]) return i
    }
    return null
  }

  /** 拿着的那张（中心在 [draggedCenterX]）该排到第几：其它卡片里中心在它左边的有几张。 */
  fun targetIndex(layout: AlbumCarouselGeometry.Layout, draggedIndex: Int, draggedCenterX: Float): Int {
    var target = 0
    for (i in 0 until layout.itemCount) {
      if (i != draggedIndex && layout.itemLefts[i] + layout.itemWidths[i] / 2f < draggedCenterX) {
        target++
      }
    }
    return target
  }

  /** 手指离视口两端 [zone] 以内：每帧滚多少（负数往回），越靠边越快，最多 [maxSpeed]。 */
  fun autoScrollStep(fingerInViewport: Float, viewportWidth: Float, zone: Float, maxSpeed: Float): Float {
    return when {
      fingerInViewport < zone -> -maxSpeed * ((zone - fingerInViewport) / zone).coerceAtMost(1f)
      fingerInViewport > viewportWidth - zone -> maxSpeed * ((fingerInViewport - (viewportWidth - zone)) / zone).coerceAtMost(1f)
      else -> 0f
    }
  }
}

private const val PREVIEW_START_INSET_DP = 16f
private const val AUTO_SCROLL_ZONE_DP = 56f
private const val AUTO_SCROLL_MAX_SPEED_DP = 12f
private const val LIFT_SCALE = 1.05f
private const val FLING_SIGN_THRESHOLD = 300f
