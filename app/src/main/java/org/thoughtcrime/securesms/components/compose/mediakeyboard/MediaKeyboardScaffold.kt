/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.compose.mediakeyboard

import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.navigationBarsCompat
import org.signal.core.ui.compose.safeDrawingCompat
import org.signal.core.ui.compose.systemBarsCompat
import org.signal.core.ui.getWindowSizeClass
import org.signal.core.ui.isHeightCompact
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** Shapes back gesture progress into travel. Matches the platform's IME hide curve. */
private val BACK_TRACKING_EASING = FastOutLinearInEasing

/** Settles the keyboard once a back gesture lets go. Lower stiffness is heavier. */
private val BACK_SETTLE_MOTION = spring<Float>(
  dampingRatio = Spring.DampingRatioNoBouncy,
  stiffness = Spring.StiffnessMediumLow
)

/** How long to hold space for a system keyboard that was asked for but never appeared. */
private val SYSTEM_KEYBOARD_ARRIVAL_TIMEOUT = 1.seconds

private val SHEET_POSITIONAL_THRESHOLD = 56.dp
private val SHEET_VELOCITY_THRESHOLD = 125.dp

/**
 * Displays [content] alongside keyboards of our own that stand in for the system keyboard.
 *
 * Only one is ever up, and never alongside the system keyboard. The scaffold owns every window inset,
 * handing [content] an already-inset space to lay out in.
 *
 * @param controller Requests which keyboard is up. Stable, so view code may hold one.
 * @param onEvent Receives everything the host may need to react to.
 * @param keyboardsProvider Declares the available keyboards.
 * @param keyboardHeight Bounds on how tall a keyboard may be.
 * @param adjustContentForInput False to let a keyboard cover [content] rather than resize it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaKeyboardScaffold(
  controller: MediaKeyboardController,
  onEvent: (MediaKeyboardEvents) -> Unit,
  keyboardsProvider: MediaKeyboardScope.() -> Unit,
  modifier: Modifier = Modifier,
  keyboardHeight: MediaKeyboardHeight = MediaKeyboardHeight(),
  adjustContentForInput: Boolean = true,
  content: @Composable () -> Unit
) {
  val registry = remember(keyboardsProvider) { MediaKeyboardRegistry().apply(keyboardsProvider) }

  val density = LocalDensity.current
  val imeInsets = WindowInsets.ime
  val imeAnimationTarget = WindowInsets.imeAnimationTarget
  val windowHeightPx = LocalWindowInfo.current.containerSize.height

  val resources = LocalResources.current
  val configuration = LocalConfiguration.current
  val isHeightCompact = remember(resources, configuration) { resources.getWindowSizeClass().isHeightCompact }

  val minimumHeightPx = with(density) { keyboardHeight.minimum.roundToPx() }
  val topMarginPx = with(density) { keyboardHeight.topMargin.roundToPx() }

  val activeKey = controller.current?.takeIf { registry.isEnabled(it) }

  // The target, so a hide reads as gone the moment it is asked for rather than a whole animation later.
  val systemKeyboardVisible = imeAnimationTarget.getBottom(density) > 0

  // Whether the live inset has yet to catch up with the target. Deliberately not imeAnimationSource,
  // which the platform leaves behind whenever an animation ends without a duration to run down, and
  // which would then read as animating until the next keyboard came and went. Derived, so the frames
  // the live inset walks through are not each a recomposition.
  val systemKeyboardAnimating by remember(imeInsets, imeAnimationTarget, density) {
    derivedStateOf { imeInsets.getBottom(density) != imeAnimationTarget.getBottom(density) }
  }

  // Tellomi (interaction audit A-35): whether the system keyboard takes any of the screen right now, coming, going or up.
  // Derived, like the above, so the frames the live inset walks through are not each a recomposition.
  val systemKeyboardOnScreen by remember(imeInsets, density) {
    derivedStateOf { imeInsets.getBottom(density) > 0 }
  }

  SideEffect {
    controller.isSystemKeyboardVisible = systemKeyboardVisible
  }

  // The controller outlives us. current is left set so a rebuilt view restores its keyboard.
  DisposableEffect(controller) {
    onDispose {
      controller.isSystemKeyboardVisible = false
      controller.awaitingSystemKeyboard = false
    }
  }

  var hasReportedKeyboardVisibility by remember { mutableStateOf(false) }
  LaunchedEffect(systemKeyboardVisible) {
    if (hasReportedKeyboardVisibility) {
      onEvent(MediaKeyboardEvents.SystemKeyboardVisibilityChanged(systemKeyboardVisible))
    }
    hasReportedKeyboardVisibility = true
  }

  LaunchedEffect(controller.awaitingSystemKeyboard) {
    if (controller.awaitingSystemKeyboard) {
      delay(SYSTEM_KEYBOARD_ARRIVAL_TIMEOUT)
      controller.awaitingSystemKeyboard = false
    }
  }

  // From the animation target, not the live inset, which walks down through every closing frame.
  LaunchedEffect(imeAnimationTarget, density, minimumHeightPx, isHeightCompact) {
    if (isHeightCompact) {
      return@LaunchedEffect
    }

    snapshotFlow { imeAnimationTarget.getBottom(density) }
      .filter { it > minimumHeightPx }
      .distinctUntilChanged()
      .collect {
        controller.keyboardHeightPx = it
        onEvent(MediaKeyboardEvents.SystemKeyboardHeightMeasured(it))
      }
  }

  // dropWhile rather than drop, so a composition that starts mid-animation still reports the settle
  // it is in the middle of instead of swallowing it as the initial state.
  LaunchedEffect(imeInsets, imeAnimationTarget, density) {
    snapshotFlow { imeInsets.getBottom(density) == imeAnimationTarget.getBottom(density) }
      .distinctUntilChanged()
      .dropWhile { settled -> settled }
      .filter { settled -> settled }
      .collect {
        controller.awaitingSystemKeyboard = false
        onEvent(MediaKeyboardEvents.SystemKeyboardAnimationEnded)
      }
  }

  val heightPx = keyboardHeight.resolve(
    preferredPx = controller.keyboardHeightPx,
    windowHeightPx = windowHeightPx,
    minimumPx = minimumHeightPx,
    topMarginPx = topMarginPx
  )
  val height = with(density) { heightPx.toDp() }

  val backProgress = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()

  val sheetState = remember {
    SheetState(
      skipPartiallyExpanded = true,
      positionalThreshold = { with(density) { SHEET_POSITIONAL_THRESHOLD.toPx() } },
      velocityThreshold = { with(density) { SHEET_VELOCITY_THRESHOLD.toPx() } },
      initialValue = SheetValue.Hidden,
      skipHiddenState = false
    )
  }
  val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

  // This just makes sure the previously visible state doesn't go away too early while we're mid swap.
  var visibleKey by remember { mutableStateOf<MediaKeyboardKey?>(null) }

  // Tellomi (interaction audit A-35): set while a keyboard of ours opens from nothing, see [claimedKeyboardSpacePx].
  var followSheet by remember { mutableStateOf(false) }

  LaunchedEffect(activeKey) {
    if (activeKey != null) {
      if (visibleKey != null && visibleKey != activeKey) {
        onEvent(MediaKeyboardEvents.KeyboardHidden)
      }

      // Taking over from the system keyboard: in place at once, for the keyboard to slide away over it (both Telegram apps show
      // the panel without animating it when the keyboard is up). From nothing on screen, the space opens with the sheet.
      val swappingInForSystemKeyboard = visibleKey == null && (systemKeyboardOnScreen || controller.awaitingSystemKeyboard)
      followSheet = visibleKey == null && !swappingInForSystemKeyboard
      visibleKey = activeKey
      backProgress.snapTo(0f)
      if (swappingInForSystemKeyboard) {
        withContext(InstantMotion) { sheetState.expand() }
      } else {
        sheetState.expand()
      }
      followSheet = false
      onEvent(MediaKeyboardEvents.KeyboardShown(activeKey))
    } else if (visibleKey != null) {
      if (controller.awaitingSystemKeyboard) {
        // Tellomi (A-35): put away for the system keyboard, it stays where it is until the keyboard is up over it, then goes at
        // once; sliding away first left the space empty until the keyboard arrived (Telegram Android keeps its panel for the
        // keyboard to open over, `showPopup(2, …)`).
        snapshotFlow { controller.awaitingSystemKeyboard }.first { awaiting -> !awaiting }
        withContext(InstantMotion) { sheetState.hide() }
      } else {
        // A gesture may already have carried it off screen; hold that until the hide completes.
        sheetState.hide()
      }
      backProgress.snapTo(0f)
      visibleKey = null
      onEvent(MediaKeyboardEvents.KeyboardHidden)
    }
  }

  PredictiveBackHandler(enabled = activeKey != null) { progress ->
    try {
      progress.collect { backEvent -> backProgress.snapTo(BACK_TRACKING_EASING.transform(backEvent.progress)) }

      backProgress.animateTo(1f, BACK_SETTLE_MOTION)
      controller.hide()
      onEvent(MediaKeyboardEvents.DismissedByBack)
    } catch (cancelled: CancellationException) {
      // PredictiveBackHandler cancels this job, so the unwind must run somewhere that outlives it.
      scope.launch { backProgress.animateTo(0f, BACK_SETTLE_MOTION) }
      throw cancelled
    }
  }

  val systemKeyboardTakingOverSpace = activeKey == null &&
    (controller.awaitingSystemKeyboard || (systemKeyboardVisible && systemKeyboardAnimating))

  // Tellomi (interaction audit A-35): how much of our keyboard's sheet is on screen while it slides; null before it has been
  // laid out. The sheet's offset is from the top of the scaffold.
  var scaffoldHeightPx by remember { mutableIntStateOf(0) }
  val sheetShowingPx: () -> Int? = {
    val offset = if (scaffoldHeightPx > 0) runCatching { sheetState.requireOffset() }.getOrNull() else null
    offset?.let { (scaffoldHeightPx - it).roundToInt().coerceIn(0, heightPx) }
  }

  val claimedBottomPx = {
    // Also before the effect above has run, so the first frame of an opening from nothing already follows the sheet.
    val openingFromNothing = activeKey != null && visibleKey == null && !systemKeyboardOnScreen && !controller.awaitingSystemKeyboard
    claimedKeyboardSpacePx(
      requested = activeKey != null,
      showing = visibleKey != null,
      followSheet = followSheet || openingFromNothing,
      systemKeyboardTakingOverSpace = systemKeyboardTakingOverSpace,
      heightPx = heightPx,
      sheetShowingPx = sheetShowingPx,
      backProgress = backProgress.value
    )
  }

  var ancestorConsumedBottomPx by remember { mutableIntStateOf(0) }
  val safeDrawingInsets = WindowInsets.safeDrawingCompat

  val windowInsets = if (adjustContentForInput) {
    safeDrawingInsets
  } else {
    WindowInsets.systemBarsCompat.add(WindowInsets.displayCutout)
  }

  Box(modifier = modifier.fillMaxSize().onSizeChanged { scaffoldHeightPx = it.height }) {
    BottomSheetScaffold(
      scaffoldState = scaffoldState,
      sheetPeekHeight = 0.dp,
      sheetShape = RectangleShape,
      sheetDragHandle = null,
      sheetSwipeEnabled = false,
      sheetContainerColor = Color.Transparent,
      sheetTonalElevation = 0.dp,
      sheetShadowElevation = 0.dp,
      containerColor = Color.Transparent,
      sheetContent = {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { translationY = backProgress.value * heightPx }
            .background(registry.containerColorFor(visibleKey).takeOrElse { MaterialTheme.colorScheme.surfaceContainerLow })
            .windowInsetsPadding(WindowInsets.navigationBarsCompat)
        ) {
          registry.contentFor(visibleKey)?.invoke()
        }
      }
    ) { _ ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .onConsumedWindowInsetsChanged { ancestorConsumedBottomPx = it.getBottom(density) }
          .windowInsetsPadding(windowInsets)
          .layout { measurable, constraints ->
            // Window insets are already out of these constraints; take only the excess claim.
            val windowBottomPx = (safeDrawingInsets.getBottom(this) - ancestorConsumedBottomPx).coerceAtLeast(0)
            val extraPx = if (adjustContentForInput) (claimedBottomPx() - windowBottomPx).coerceAtLeast(0) else 0

            val available = (constraints.maxHeight - extraPx).coerceAtLeast(0)
            val placeable = measurable.measure(constraints.copy(minHeight = available, maxHeight = available))
            layout(constraints.maxWidth, constraints.maxHeight) {
              placeable.place(0, 0)
            }
          }
      ) {
        content()
      }
    }
  }
}

/** Tellomi (interaction audit A-35): runs Compose animations in its context straight to their end. */
private object InstantMotion : MotionDurationScale {
  override val scaleFactor: Float = 0f
}

/**
 * How much of the bottom of the window our keyboard takes from the content.
 *
 * Tellomi (interaction audit A-35): opening from nothing or closing to nothing, the space follows the sheet as it slides, so the
 * input bar rides on its top edge, as both Telegram apps move the input panel together with the emoji panel. Claimed all at once,
 * the bar jumped up and left a gap for the sheet to fill (and dropped over a sheet still sliding away). Swapping with the system
 * keyboard still claims the whole height at once: the keyboard going away holds that space meanwhile.
 *
 * @param requested A keyboard of ours is asked for.
 * @param showing A keyboard of ours is still on screen (it may be sliding away).
 * @param followSheet The requested keyboard is opening from nothing.
 * @param sheetShowingPx How much of the sheet is on screen; null before it has been laid out.
 * @param backProgress How far a back gesture has carried the keyboard off.
 */
@VisibleForTesting
internal fun claimedKeyboardSpacePx(
  requested: Boolean,
  showing: Boolean,
  followSheet: Boolean,
  systemKeyboardTakingOverSpace: Boolean,
  heightPx: Int,
  sheetShowingPx: () -> Int?,
  backProgress: Float
): Int {
  val claimedPx = when {
    requested -> if (followSheet) sheetShowingPx() ?: heightPx else heightPx
    systemKeyboardTakingOverSpace -> return heightPx
    showing -> sheetShowingPx() ?: 0
    else -> 0
  }

  return (claimedPx * (1f - backProgress)).roundToInt().coerceAtLeast(0)
}

/**
 * Bounds on how tall a keyboard may be. The height it wants comes from
 * [MediaKeyboardController.keyboardHeightPx].
 *
 * Holds a lambda, so callers should [remember] it or the scaffold cannot skip recomposition.
 *
 * @param minimum Floor, used before any keyboard has been measured.
 * @param topMargin Kept clear of the top of the window, capping the height.
 * @param overrideForWindow Derives a height from the window instead, for windows unlike the one a
 *   keyboard was measured in.
 */
@Immutable
data class MediaKeyboardHeight(
  val minimum: Dp = 260.dp,
  val topMargin: Dp = 170.dp,
  val overrideForWindow: ((windowHeightPx: Int) -> Int)? = null
) {
  internal fun resolve(preferredPx: Int, windowHeightPx: Int, minimumPx: Int, topMarginPx: Int): Int {
    if (windowHeightPx <= 0) {
      return maxOf(preferredPx, minimumPx)
    }

    val maximumPx = (windowHeightPx - topMarginPx).coerceAtLeast(minimumPx)

    overrideForWindow?.let { return it(windowHeightPx).coerceAtMost(maximumPx) }

    return preferredPx.coerceIn(minimumPx, maximumPx)
  }
}
