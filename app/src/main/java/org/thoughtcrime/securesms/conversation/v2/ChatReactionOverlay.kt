/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.navigationBarsCompat
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationItem
import org.thoughtcrime.securesms.conversation.ReactionOverlayPlacement
import org.thoughtcrime.securesms.conversation.ReactionScrubber
import org.thoughtcrime.securesms.reactions.any.TellomiReactionPanelView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.signal.core.ui.R as CoreUiR

/** Tellomi (interaction audit A-06): seven reactions and the expand slot, as Telegram has ([TellomiReactionStrip]). */
const val REACTION_EMOJI_COUNT = TellomiReactionStrip.SLOT_COUNT

private const val GROW_DURATION_MS = 200

/** Tellomi (A-07 fly-in): also used by [ChatReactionOverlayController] to know how big the emoji under the thumb is drawn. */
internal const val GROW_SCALE = 1.5f

/** Tellomi (A-06): 28 dp in a 36 dp slot leaves a little air between neighbours, as Telegram's strip has (iOS draws 28 pt too). */
internal val EMOJI_WIDTH = 28.dp
private val EMOJI_HEIGHT = 48.dp
private val SLOT_WIDTH = TellomiReactionStrip.ITEM_SIZE_DP.dp
private val STRIP_WIDTH = TellomiReactionStrip.WIDTH_DP.dp
private val STRIP_HEIGHT = TellomiReactionStrip.HEIGHT_DP.dp

/** Tellomi (A-06): the reaction already on the message sits in a slot-sized circle and shrinks to 0.8 (Telegram). */
private val SELECTION_INDICATOR_SIZE = TellomiReactionStrip.ITEM_SIZE_DP.dp
private const val APPLIED_EMOJI_SCALE = 0.8f

/** android.view.animation.OvershootInterpolator, which Telegram's strip and its items enter with. */
private fun overshoot(tension: Float) = Easing { fraction ->
  val t = fraction - 1f
  t * t * ((tension + 1f) * t + tension) + 1f
}

/** DecelerateInterpolator, which an interpolator on an AnimatorSet applied to the whole reveal and hide. */
private val Decelerate = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }

/** ViewPropertyAnimator's default, which carried the snapshot to its place. */
private val AccelerateDecelerate = Easing { fraction -> (cos((fraction + 1f) * PI).toFloat() / 2f) + 0.5f }

/** Tellomi (A-06): Telegram's `CubicBezierInterpolator.DEFAULT`, which its strip grows into the emoji panel with. */
private val ExpandEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

private class StripBounds {
  var barTop: Float = 0f
  var barBottom: Float = 0f
  var firstEmojiEdge: Float = 0f
  var lastEmojiEdge: Float = 0f
}

/**
 * Everything one long press put on screen, in overlay coordinates. [lastSeenDownY] is the exception
 * and is in window coordinates, as it arrives from the activity's touch relay.
 *
 * @param snapshot A picture of the row that was pressed, standing in for the real one.
 * @param appliedEmojiIndex Slot holding the reaction already on this message, or -1 for none.
 * @param canReact False for a message that takes no reactions, which hides the strip.
 * @param returnPosition Where the row is *now*, for the hide to fly back to. Null once it is gone.
 */
data class ReactionOverlaySelection(
  val snapshot: ImageBitmap,
  val bubbleX: Float,
  val bubbleY: Float,
  val bubbleWidth: Int,
  val contextMenuX: Float,
  val isMessageOnLeft: Boolean,
  val lastSeenDownY: Float,
  val emoji: List<Drawable?>,
  val appliedEmojiIndex: Int,
  val canReact: Boolean,
  val returnPosition: () -> Offset?
)

/**
 * The long press reaction overlay, drawn above the keyboard scaffold.
 */
@Composable
fun ChatReactionOverlay(
  controller: ChatReactionOverlayController,
  modifier: Modifier = Modifier
) {
  val selection = controller.selection
  val isRevealed = controller.isRevealed
  val scrubber = controller.scrubber
  val menuWidth = controller.menu?.getMaxWidth() ?: 0
  val menuHeight = controller.menu?.getMaxHeight() ?: 0

  val density = LocalDensity.current
  val context = LocalContext.current
  val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

  val revealDuration = integerResource(R.integer.reaction_scrubber_reveal_duration)
  val revealOffset = integerResource(R.integer.reaction_scrubber_reveal_offset)
  val hideDuration = integerResource(R.integer.reaction_scrubber_hide_duration)
  val indicatorDuration = integerResource(android.R.integer.config_mediumAnimTime)

  val scrubberHeight = dimensionResource(R.dimen.conversation_reaction_scrubber_height)
  val horizontalMargin = dimensionResource(R.dimen.conversation_reaction_scrub_horizontal_margin)
  val scrubDistanceBelowTouch = dimensionResource(R.dimen.conversation_reaction_scrub_deadzone_distance_from_touch_bottom)

  // Tellomi (interaction audit A-06): Telegram's 44 dp capsule rather than an action bar's height.
  val barHeightPx = with(density) { STRIP_HEIGHT.roundToPx() }
  val tier = remember(context) { TellomiMotion.tier(context) }
  // Tellomi (A-06): where the conversation behind is blurred (full tier, API 31+, #289), the strip lets a little of it through,
  // as Telegram's blurred strip does; elsewhere it stays an opaque surface (the motion spec: Android does not imitate glass).
  val stripAlpha = if (tier == TellomiMotion.Tier.FULL && Build.VERSION.SDK_INT >= 31) 0.92f else 1f

  val overlayTop = remember { mutableFloatStateOf(0f) }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow()

        overlayTop.floatValue = origin.y
        controller.onOriginChanged(origin)
      },
    contentAlignment = AbsoluteAlignment.TopLeft
  ) {
    if (selection == null) {
      return@BoxWithConstraints
    }

    val overlayWidthPx = with(density) { maxWidth.roundToPx() }
    val overlayHeightPx = with(density) { maxHeight.roundToPx() }
    val statusBarPx = overlayTop.floatValue.roundToInt()
    // Tellomi (interaction audit A-06, owner Q-05 = A): a long press leaves the system keyboard up, so the message, the
    // strip and the menu are placed above it; the keyboard's inset already counts the navigation bar it sits on.
    val navigationBarPx = max(WindowInsets.navigationBarsCompat.getBottom(density), WindowInsets.ime.getBottom(density))
    val scrubBelowTouchPx = with(density) { scrubDistanceBelowTouch.toPx() }

    val placement = remember(
      selection,
      overlayWidthPx,
      overlayHeightPx,
      statusBarPx,
      navigationBarPx,
      barHeightPx,
      menuWidth,
      menuHeight
    ) {
      ReactionOverlayPlacement.of(
        metrics = ReactionOverlayPlacement.Metrics(
          overlayWidth = overlayWidthPx,
          overlayHeight = overlayHeightPx,
          statusBarHeight = statusBarPx,
          navigationBarHeight = navigationBarPx,
          bubbleX = selection.bubbleX,
          bubbleY = selection.bubbleY,
          bubbleWidth = selection.bubbleWidth,
          contextMenuX = selection.contextMenuX,
          snapshotWidth = selection.snapshot.width,
          snapshotHeight = selection.snapshot.height,
          reactionBarHeight = barHeightPx,
          scrubberForegroundHeight = with(density) { scrubberHeight.roundToPx() },
          scrubberWidth = with(density) { STRIP_WIDTH.roundToPx() },
          scrubberHorizontalMargin = with(density) { horizontalMargin.roundToPx() },
          menuMaxWidth = menuWidth,
          menuMaxHeight = menuHeight,
          isMessageOnLeft = selection.isMessageOnLeft,
          lastSeenDownY = selection.lastSeenDownY
        ),
        dp = ReactionOverlayPlacement.DpConverter { with(density) { it.dp.toPx() } }
      )
    }

    val bounds = remember(selection) { StripBounds() }

    val pushGeometry = {
      scrubber.geometry = ReactionScrubber.Geometry(
        stripStart = bounds.firstEmojiEdge,
        stripEnd = bounds.lastEmojiEdge,
        stripTop = bounds.barTop,
        stripBottom = bounds.barBottom,
        scrubTop = bounds.barTop,
        scrubBottom = selection.lastSeenDownY + scrubBelowTouchPx,
        isStripVisible = selection.canReact
      )
    }

    // Tellomi (interaction audit A-06): the shade fades in as it fades out, instead of appearing at full strength.
    val shade = remember(selection) { Animatable(0f) }
    val emojiReveal = remember(selection) { List(REACTION_EMOJI_COUNT) { Animatable(0f) } }
    val indicatorReveal = remember(selection) { Animatable(0f) }
    val stripReveal = remember(selection) { Animatable(0f) }
    // Tellomi (A-06): the strip unrolls from the message's end and scales up with an overshoot (Telegram's transitionProgress).
    val stripEnter = remember(selection) { Animatable(0f) }
    val anchorOnLeft = selection.isMessageOnLeft
    // Tellomi (A-06): the pull on the expand slot, which springs back when let go short, and the strip's growth into the
    // emoji panel ([expand] is its shape, [panelFade] stands in for it where animations are off).
    val pull = remember(selection) { Animatable(0f) }
    val expand = remember(selection) { Animatable(0f) }
    val panelFade = remember(selection) { Animatable(0f) }
    val isExpanded = controller.isExpanded
    val snapshotX = remember(selection) { Animatable(selection.bubbleX) }
    val snapshotY = remember(selection) { Animatable(selection.bubbleY) }
    val snapshotScale = remember(selection) { Animatable(ConversationItem.LONG_PRESS_SCALE_FACTOR) }

    DisposableEffect(Unit) {
      onDispose { controller.onHideFinished() }
    }

    // Tellomi (A-06, Q-03 = B): the conversation behind blurs in step with the shade (the fragment decides whether it can).
    LaunchedEffect(selection) {
      snapshotFlow { shade.value }.collect { controller.onShadeProgress(it) }
    }
    DisposableEffect(selection) {
      onDispose { controller.onShadeProgress(0f) }
    }

    LaunchedEffect(selection) {
      snapshotFlow { controller.isPulling to controller.pullProgress }.collectLatest { (isPulling, progress) ->
        if (isPulling) {
          pull.snapTo(progress)
        } else {
          pull.animateTo(0f, tween(TellomiReactionStrip.PULL_BACK_DURATION_MS, easing = AccelerateDecelerate))
        }
      }
    }

    LaunchedEffect(selection, isExpanded) {
      if (!isExpanded) {
        return@LaunchedEffect
      }

      if (tier == TellomiMotion.Tier.MINIMAL) {
        // Animations off: the panel is simply faded in where it will stay.
        expand.snapTo(1f)
        panelFade.animateTo(1f, tween(revealDuration, easing = Decelerate))
      } else {
        panelFade.snapTo(1f)
        expand.animateTo(1f, tween(TellomiReactionStrip.EXPAND_DURATION_MS, easing = ExpandEasing))
      }
    }

    LaunchedEffect(selection) {
      snapshotFlow { expand.value * panelFade.value }.collect { controller.onExpandProgress(it) }
    }

    LaunchedEffect(selection, isRevealed) {
      if (isRevealed) {
        coroutineScope {
          launch { shade.animateTo(1f, tween(hideDuration, easing = Decelerate)) }
          launch { snapshotX.animateTo(placement.snapshotX, tween(revealDuration, easing = AccelerateDecelerate)) }
          launch { snapshotY.animateTo(placement.snapshotY, tween(revealDuration, easing = AccelerateDecelerate)) }
          launch { snapshotScale.animateTo(placement.snapshotScale, tween(revealDuration, easing = AccelerateDecelerate)) }
          launch { indicatorReveal.animateTo(1f, tween(indicatorDuration, easing = Decelerate)) }

          if (tier == TellomiMotion.Tier.MINIMAL) {
            // Animations off: no scaling or stagger, the strip just fades in.
            stripEnter.snapTo(1f)
            emojiReveal.forEach { it.snapTo(1f) }
            launch { stripReveal.animateTo(1f, tween(revealDuration, delayMillis = revealOffset, easing = Decelerate)) }
          } else {
            stripReveal.snapTo(1f)
            launch {
              stripEnter.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                  durationMillis = TellomiReactionStrip.ENTER_DURATION_MS,
                  delayMillis = revealOffset,
                  easing = overshoot(TellomiReactionStrip.ENTER_OVERSHOOT_TENSION)
                )
              )
            }

            emojiReveal.forEachIndexed { index, animatable ->
              val screenIndex = if (isLtr) index else REACTION_EMOJI_COUNT - 1 - index
              launch {
                animatable.animateTo(
                  targetValue = 1f,
                  animationSpec = tween(
                    durationMillis = TellomiReactionStrip.ITEM_ENTER_DURATION_MS,
                    delayMillis = revealOffset + TellomiReactionStrip.itemEnterDelayMs(screenIndex, anchorOnLeft).toInt(),
                    easing = overshoot(TellomiReactionStrip.ITEM_ENTER_OVERSHOOT_TENSION)
                  )
                )
              }
            }
          }
        }
      } else {
        val returned = selection.returnPosition()

        coroutineScope {
          launch { shade.animateTo(0f, tween(hideDuration, easing = Decelerate)) }
          launch { snapshotX.animateTo(returned?.x ?: selection.bubbleX, tween(hideDuration, easing = Decelerate)) }
          launch { snapshotY.animateTo(returned?.y ?: selection.bubbleY, tween(hideDuration, easing = Decelerate)) }
          launch { snapshotScale.animateTo(1f, tween(hideDuration, easing = Decelerate)) }
          launch { indicatorReveal.animateTo(0f, tween(hideDuration, easing = Decelerate)) }
          // Tellomi (A-06): the strip, its tail and the emoji fade out together, as Telegram's strip does.
          launch { stripReveal.animateTo(0f, tween(hideDuration, easing = Decelerate)) }
        }

        controller.onHideFinished()
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { controller.onPlaced(placement) }
        .graphicsLayer { alpha = shade.value }
        .background(colorResource(R.color.reactions_screen_light_shade_color))
        .background(colorResource(R.color.reactions_screen_dark_shade_color))
    )

    Image(
      bitmap = selection.snapshot,
      contentDescription = null,
      modifier = Modifier
        .absoluteOffset { IntOffset(snapshotX.value.roundToInt(), snapshotY.value.roundToInt()) }
        .size(
          width = with(density) { selection.snapshot.width.toDp() },
          height = with(density) { selection.snapshot.height.toDp() }
        )
        .graphicsLayer {
          scaleX = snapshotScale.value
          scaleY = snapshotScale.value
        }
    )

    if (!selection.canReact) {
      return@BoxWithConstraints
    }

    // Tellomi (A-06): the panel the strip grows into sits where Telegram's does, over the strip and 96 dp above it, kept
    // clear of the screen's edges and of the keyboard.
    val (panelTop, panelHeight) = TellomiReactionStrip.panelVerticalFrame(
      stripTop = placement.reactionBarY,
      top = 0f,
      bottom = (overlayHeightPx - navigationBarPx).toFloat(),
      density = density.density
    )
    val panelRadiusPx = with(density) { TellomiReactionStrip.PANEL_CORNER_RADIUS_DP.dp.toPx() }

    val stripColor = colorResource(CoreUiR.color.signal_colorSurface2).copy(alpha = stripAlpha)
    Box(
      modifier = Modifier
        .absoluteOffset { IntOffset(placement.scrubberX.roundToInt(), placement.reactionBarY.roundToInt()) }
        .width(STRIP_WIDTH)
        .height(STRIP_HEIGHT)
        .graphicsLayer { alpha = stripReveal.value }
        .drawBehind { drawStripBackground(stripEnter.value, anchorOnLeft, stripColor, panelFade.value, expand.value) }
        .onGloballyPositioned { coordinates ->
          bounds.barTop = coordinates.positionInWindow().y
          bounds.barBottom = bounds.barTop + coordinates.size.height
          pushGeometry()
        }
    )

    if (isExpanded) {
      // Its own layer the size of the overlay: a translucent layer only as big as the strip would cut the panel off as
      // everything fades on the way out.
      Box(
        modifier = Modifier
          .fillMaxSize()
          .drawBehind {
            drawExpandingPanel(
              // The strip lets a little of the blurred chat through; the panel, over the message itself, does not.
              color = stripColor.copy(alpha = lerp(stripColor.alpha, 1f, expand.value) * stripReveal.value * panelFade.value),
              expand = expand.value,
              strip = Rect(Offset(placement.scrubberX, placement.reactionBarY), Size(STRIP_WIDTH.toPx(), STRIP_HEIGHT.toPx())),
              panel = Rect(Offset(placement.scrubberX, panelTop), Size(STRIP_WIDTH.toPx(), panelHeight)),
              panelRadius = panelRadiusPx
            )
          }
      )
    }

    // Tellomi (A-06): pulling the expand slot crowds the emoji toward the strip's start, as Telegram's strip does.
    val pullDirection = if (isLtr) -1f else 1f
    Row(
      modifier = Modifier
        .absoluteOffset { IntOffset(placement.scrubberX.roundToInt(), placement.scrubberForegroundY.roundToInt()) }
        .width(STRIP_WIDTH)
        .height(scrubberHeight)
        .graphicsLayer { alpha = stripReveal.value * expandingStripAlpha(panelFade.value, expand.value) }
        .padding(horizontal = TellomiReactionStrip.SIDE_PADDING_DP.dp),
      horizontalArrangement = Arrangement.Start,
      verticalAlignment = Alignment.CenterVertically
    ) {
      for (index in 0 until REACTION_EMOJI_COUNT) {
        ReactionEmoji(
          index = index,
          emoji = selection.emoji.getOrNull(index),
          isApplied = index == selection.appliedEmojiIndex,
          isExpandSlot = index == REACTION_EMOJI_COUNT - 1,
          reveal = emojiReveal[index].value,
          indicatorAlpha = indicatorReveal.value,
          selectedIndex = { controller.selectedIndex },
          pullShift = { pullDirection * TellomiReactionStrip.pullShiftDp(index, pull.value) * density.density },
          pullGrowth = { 1f + TellomiReactionStrip.PULL_EXPAND_BUTTON_GROWTH * min(pull.value, 1f) },
          onSlotPlaced = { bounds -> controller.onSlotPlaced(index, bounds) },
          onEdgeMeasured = { left, right ->
            // Layout direction order, so under RTL start is the greater edge.
            if (index == 0) {
              bounds.firstEmojiEdge = if (isLtr) left else right
            }

            if (index == REACTION_EMOJI_COUNT - 1) {
              bounds.lastEmojiEdge = if (isLtr) right else left
            }

            pushGeometry()
          }
        )
      }
    }

    // Tellomi (A-06): the full picker, fading in on the strip's background as that grows into the panel.
    val expandedRecord = controller.expandedMessageRecord
    if (expandedRecord != null) {
      AndroidView(
        factory = { viewContext ->
          TellomiReactionPanelView(
            context = viewContext,
            messageId = expandedRecord.id,
            isMms = expandedRecord.isMms,
            onSelected = controller::onPanelEmojiSelected,
            onConfigure = controller::onPanelConfigure
          )
        },
        modifier = Modifier
          .absoluteOffset { IntOffset(placement.scrubberX.roundToInt(), panelTop.roundToInt()) }
          .width(STRIP_WIDTH)
          .height(with(density) { panelHeight.toDp() })
          .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            controller.onPanelPlaced(RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
          }
          .graphicsLayer {
            alpha = stripReveal.value * panelFade.value * expand.value
            // Kept inside the background as it grows, as Telegram clips its panel to the growing rectangle.
            clip = true
            shape = ExpandingPanelShape(
              expand = expand.value,
              stripTop = placement.reactionBarY - panelTop,
              stripHeight = STRIP_HEIGHT.toPx(),
              panelRadius = panelRadiusPx
            )
          }
      )
    }
  }
}

/**
 * One slot in the strip.
 *
 * Its own composable so that [selectedIndex], which changes as fast as a thumb moves, invalidates
 * seven small scopes rather than the whole overlay.
 *
 * @param onSlotPlaced Tellomi (A-07 fly-in): the slot's untransformed bounds, in window coordinates.
 * @param onEdgeMeasured The slot's untransformed leading and trailing edges, in window coordinates.
 */
@Composable
private fun ReactionEmoji(
  index: Int,
  emoji: Drawable?,
  isApplied: Boolean,
  isExpandSlot: Boolean,
  reveal: Float,
  indicatorAlpha: Float,
  selectedIndex: () -> Int,
  pullShift: () -> Float,
  pullGrowth: () -> Float,
  onSlotPlaced: (RectF) -> Unit,
  onEdgeMeasured: (Float, Float) -> Unit
) {
  val density = LocalDensity.current
  val growTranslation = dimensionResource(R.dimen.conversation_reaction_scrub_vertical_translation)

  val isUnderThumb = selectedIndex() == index && !isExpandSlot

  val grow by animateFloatAsState(
    targetValue = if (isUnderThumb) GROW_SCALE else 1f,
    animationSpec = tween(GROW_DURATION_MS, easing = Decelerate),
    label = "emojiGrow$index"
  )
  val growLift by animateFloatAsState(
    targetValue = if (isUnderThumb) -with(density) { growTranslation.toPx() } else 0f,
    animationSpec = tween(GROW_DURATION_MS, easing = Decelerate),
    label = "emojiGrowLift$index"
  )
  val restingScale = if (isApplied && !isUnderThumb) APPLIED_EMOJI_SCALE else 1f

  Box(
    modifier = Modifier
      .size(width = SLOT_WIDTH, height = EMOJI_HEIGHT)
      // Measured here rather than on the emoji, whose graphicsLayer scale would otherwise move the
      // reported edges as it grows and shift the segment boundaries under a stationary thumb.
      .onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        val left = position.x

        onSlotPlaced(RectF(left, position.y, left + coordinates.size.width, position.y + coordinates.size.height))
        onEdgeMeasured(left, left + coordinates.size.width)
      }
      // Tellomi (A-06): after the measurement, so a pull moves what is drawn and not where the slots are.
      .graphicsLayer { translationX = if (isExpandSlot) 0f else pullShift() },
    contentAlignment = Alignment.Center
  ) {
    if (isApplied) {
      Box(
        modifier = Modifier
          .requiredSize(SELECTION_INDICATOR_SIZE)
          .graphicsLayer {
            alpha = indicatorAlpha
            scaleX = reveal
            scaleY = reveal
          }
          .background(
            color = colorResource(CoreUiR.color.signal_colorSurfaceVariant_16_no_alpha),
            shape = CircleShape
          )
      )
    }

    if (isExpandSlot) {
      // Tellomi (A-06): Telegram's expand slot, a small circle with a downward chevron.
      Box(
        modifier = Modifier
          .requiredSize(TellomiReactionStrip.EXPAND_BUTTON_SIZE_DP.dp)
          .graphicsLayer {
            scaleX = reveal * pullGrowth()
            scaleY = reveal * pullGrowth()
          }
          .background(color = colorResource(CoreUiR.color.signal_colorSurfaceVariant_16_no_alpha), shape = CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Image(
          painter = rememberDrawablePainter(emoji),
          contentDescription = null,
          colorFilter = ColorFilter.tint(colorResource(CoreUiR.color.signal_colorOnSurfaceVariant)),
          modifier = Modifier.size(20.dp)
        )
      }
      return@Box
    }

    Image(
      painter = rememberDrawablePainter(emoji),
      contentDescription = null,
      modifier = Modifier
        .size(width = EMOJI_WIDTH, height = EMOJI_HEIGHT)
        .graphicsLayer {
          translationY = growLift
          scaleX = grow * restingScale * reveal
          scaleY = grow * restingScale * reveal
        }
    )
  }
}

/**
 * Tellomi (interaction audit A-06): the capsule and its "thought bubble" tail, as Telegram's `ReactionsContainerLayout` draws
 * them. While [enter] runs the capsule is uncovered from the message's end and scaled about a point near it; the two tail
 * circles grow once a quarter of the way in.
 *
 * Once the strip starts growing into the panel ([panelFade] 1, see [drawExpandingPanel]) the capsule gives way to the
 * panel's background, and the tail fades out in the first fifth of the way, as Telegram's bubbles do.
 */
private fun DrawScope.drawStripBackground(enter: Float, anchorOnLeft: Boolean, color: Color, panelFade: Float, expand: Float) {
  val span = TellomiReactionStrip.revealedSpan(enter, anchorOnLeft)
  val pivot = Offset(size.width * TellomiReactionStrip.pivotFraction(anchorOnLeft), size.height / 2f)
  val scale = enter.coerceAtLeast(0f)
  val capsuleColor = color.copy(alpha = color.alpha * (1f - panelFade))
  val tailColor = color.copy(alpha = color.alpha * (1f - panelFade * (1f - TellomiReactionStrip.stripAlphaWhileExpanding(expand) * (1f - expand))))

  if (capsuleColor.alpha > 0f) {
    withTransform({ scale(scale, scale, pivot) }) {
      val left = size.width * span.start
      val right = size.width * span.endInclusive
      drawRoundRect(
        color = capsuleColor,
        topLeft = Offset(left, 0f),
        size = Size(right - left, size.height),
        cornerRadius = CornerRadius(size.height / 2f)
      )
    }
  }

  val tail = TellomiReactionStrip.tailProgress(enter)
  if (tail > 0f && tailColor.alpha > 0f) {
    val dp = density
    drawCircle(
      color = tailColor,
      radius = TellomiReactionStrip.BIG_TAIL_RADIUS_DP * dp * tail,
      center = Offset(TellomiReactionStrip.bigTailCenterXDp(anchorOnLeft) * dp, size.height)
    )
    drawCircle(
      color = tailColor,
      radius = TellomiReactionStrip.SMALL_TAIL_RADIUS_DP * dp * tail,
      center = Offset(TellomiReactionStrip.smallTailCenterXDp(anchorOnLeft) * dp, size.height + TellomiReactionStrip.SMALL_TAIL_DROP_DP * dp)
    )
  }
}

/**
 * Tellomi (interaction audit A-06): the strip's background growing into the panel's, as Telegram's
 * `CustomEmojiReactionsWindow` draws it — the rectangle and its corner radius go from the capsule's ([strip]) to the
 * panel's as [expand] runs from 0 to 1.
 */
private fun DrawScope.drawExpandingPanel(color: Color, expand: Float, strip: Rect, panel: Rect, panelRadius: Float) {
  if (color.alpha <= 0f) {
    return
  }

  val left = lerp(strip.left, panel.left, expand)
  val top = lerp(strip.top, panel.top, expand)
  val right = lerp(strip.right, panel.right, expand)
  val bottom = lerp(strip.bottom, panel.bottom, expand)
  drawRoundRect(
    color = color,
    topLeft = Offset(left, top),
    size = Size(right - left, bottom - top),
    cornerRadius = CornerRadius(lerp(strip.height / 2f, panelRadius, expand))
  )
}

/**
 * Tellomi (A-06): the background's shape at [expand], in the panel's own coordinates ([stripTop] is where the strip's top
 * is in them), for the panel's content to be clipped to; see [drawExpandingPanel].
 */
private data class ExpandingPanelShape(val expand: Float, val stripTop: Float, val stripHeight: Float, val panelRadius: Float) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val top = lerp(stripTop, 0f, expand)
    val bottom = lerp(stripTop + stripHeight, size.height, expand)
    val radius = lerp(stripHeight / 2f, panelRadius, expand)
    return Outline.Rounded(RoundRect(0f, top, size.width, max(top, bottom), CornerRadius(radius)))
  }
}

/** Tellomi (A-06): what is left of the strip's emoji while it grows into the panel (gone in the first fifth, as Telegram's). */
private fun expandingStripAlpha(panelFade: Float, expand: Float): Float {
  return 1f - panelFade * (1f - TellomiReactionStrip.stripAlphaWhileExpanding(expand))
}
