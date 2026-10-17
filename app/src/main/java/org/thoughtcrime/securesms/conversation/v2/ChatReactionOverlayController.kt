/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import org.signal.core.ui.motion.TellomiHaptics
import org.signal.emoji.EmojiProvider
import org.signal.emoji.EmojiUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import org.thoughtcrime.securesms.conversation.ConversationContextMenu
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.ReactionAction
import org.thoughtcrime.securesms.conversation.ReactionMenu
import org.thoughtcrime.securesms.conversation.ReactionOverlayPlacement
import org.thoughtcrime.securesms.conversation.ReactionScrubber
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.keyvalue.EmojiValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.reactions.any.ReactWithAnyEmojiBottomSheetDialogFragment
import org.thoughtcrime.securesms.reactions.edit.EditReactionsActivity
import org.thoughtcrime.securesms.recipients.Recipient
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Holds what the long press overlay is showing and carries the activity's raw touch stream into it.
 *
 * [hapticView] and [menuAnchor] are providers because this outlives the fragment's view, which is
 * rebuilt whenever a conversation is re-entered; a captured anchor would by then be detached and
 * carry no window token for the popup to show against.
 */
class ChatReactionOverlayController(
  private val context: Context,
  private val hapticView: () -> View,
  private val menuAnchor: () -> View,
  /** Tellomi (A-07): the third argument is where the chosen emoji is on screen for it to fly from, or null when it should not fly. */
  private val onReactionSelected: (MessageRecord, String, RectF?) -> Unit,
  private val onActionSelected: (ReactionAction) -> Unit,
  private val onStartHide: (View?) -> Unit,
  private val onHidden: () -> Unit,
  /** Tellomi (interaction audit A-06, Q-03 = B): the shade's progress, 0 hidden to 1 shown, for blurring what is behind it. */
  private val onShadeChanged: (Float) -> Unit = {}
) {

  var selection: ReactionOverlaySelection? by mutableStateOf(null)
    private set

  var isRevealed: Boolean by mutableStateOf(false)
    private set

  var selectedIndex: Int by mutableIntStateOf(ReactionScrubber.NO_SELECTION)
    private set

  /** Read during composition to size the placement, so it has to be observable. */
  var menu: ConversationContextMenu? by mutableStateOf(null)
    private set

  /**
   * Where the overlay's composition starts, in window coordinates. Everything the placement works
   * in is relative to this, so a row has to be projected into it before it can be snapshotted.
   */
  var originInWindow: Offset by mutableStateOf(Offset.Zero)
    private set

  /**
   * Tellomi (interaction audit A-06, owner 2026-10-17): the strip has grown into the full emoji panel in place, as
   * Telegram's does, instead of a separate sheet opening.
   */
  var isExpanded: Boolean by mutableStateOf(false)
    private set

  /** Tellomi (A-06): how far the expand slot is pulled toward the strip's start, 0 to 2 ([TellomiReactionStrip.pullProgress]). */
  var pullProgress: Float by mutableFloatStateOf(0f)
    private set

  /** Tellomi (A-06): the thumb is still on a pull; once it lifts short of expanding the strip springs back. */
  var isPulling: Boolean by mutableStateOf(false)
    private set

  val scrubber = ReactionScrubber(
    emojiCount = REACTION_EMOJI_COUNT,
    deadZoneSize = context.resources.getDimensionPixelSize(R.dimen.conversation_reaction_touch_deadzone_size).toFloat(),
    pullUnit = TellomiReactionStrip.PULL_UNIT_DP * context.resources.displayMetrics.density
  )

  val isShowing: Boolean
    get() = selection != null

  private val lastSeenDownPoint = PointF()

  private var messageRecord: MessageRecord? = null
  private var focusedView: View? = null
  private var strip: Strip = Strip.EMPTY
  private var menuPlaced = false
  private var pendingAction: ReactionAction? = null

  /** Tellomi (A-07 fly-in): each slot's untransformed bounds in window coordinates, as last placed. */
  private val slotBounds = Array(REACTION_EMOJI_COUNT) { RectF() }

  /** Tellomi (A-06): the expanded panel in window coordinates, and whether the gesture in progress began inside it. */
  private val panelBounds = RectF()
  private var touchInPanel = false

  private data class Strip(
    val emojiStrings: List<String>,
    val drawables: List<Drawable?>,
    val appliedIndex: Int
  ) {
    companion object {
      val EMPTY = Strip(emptyList(), emptyList(), ReactionScrubber.NO_SELECTION)
    }
  }

  fun messageRecord(): MessageRecord = requireNotNull(messageRecord) { "Nothing is showing." }

  /** Tellomi (A-06): the message the expanded panel reacts to, or null while the strip has not grown into it. */
  val expandedMessageRecord: MessageRecord?
    get() = messageRecord.takeIf { isExpanded }

  /** The pointer is already down by now, so the scrubber is opened rather than waiting for one. */
  fun show(
    conversationRecipient: Recipient,
    conversationMessage: ConversationMessage,
    snapshot: ReactionOverlaySnapshot,
    isNonAdminInAnnouncementGroup: Boolean,
    canEditGroupInfo: Boolean,
    focusedView: View?
  ) {
    if (isShowing) {
      return
    }

    val record = conversationMessage.messageRecord
    val builtMenu = ReactionMenu.of(
      context = context,
      conversationRecipient = conversationRecipient,
      conversationMessage = conversationMessage,
      isNonAdminInAnnouncementGroup = isNonAdminInAnnouncementGroup,
      canEditGroupInfo = canEditGroupInfo,
      onAction = ::onMenuAction
    )

    this.messageRecord = record
    this.focusedView = focusedView
    this.strip = buildStrip(record)
    this.menuPlaced = false
    this.pendingAction = null
    this.slotBounds.forEach { it.setEmpty() }
    this.panelBounds.setEmpty()
    this.touchInPanel = false
    this.menu = ConversationContextMenu(menuAnchor(), builtMenu.items)

    selectedIndex = ReactionScrubber.NO_SELECTION
    isExpanded = false
    pullProgress = 0f
    isPulling = false

    // Holds window coordinates from a view a re-entered conversation has already replaced.
    scrubber.geometry = ReactionScrubber.Geometry()
    scrubber.open()

    selection = ReactionOverlaySelection(
      snapshot = snapshot.bitmap,
      bubbleX = snapshot.bubbleX,
      bubbleY = snapshot.bubbleY,
      bubbleWidth = snapshot.bubbleWidth,
      contextMenuX = snapshot.contextMenuX,
      isMessageOnLeft = snapshot.isMessageOnLeft,
      lastSeenDownY = lastSeenDownPoint.y,
      emoji = strip.drawables,
      appliedEmojiIndex = strip.appliedIndex,
      canReact = builtMenu.canReact,
      returnPosition = snapshot.returnPosition
    )

    isRevealed = true
  }

  fun hide() {
    if (!isShowing || !isRevealed) {
      return
    }

    isRevealed = false
    scrubber.close()
    menu?.dismiss()

    onStartHide(if (pendingAction == ReactionAction.VIEW_INFO) null else focusedView)

    // Tellomi (interaction audit A-06): the chosen menu action runs as the overlay starts to close rather than after the
    // close animation has finished, so "Copy", "Reply" and the rest answer the tap at once.
    pendingAction?.let { action ->
      pendingAction = null
      onActionSelected(action)
    }
  }

  /**
   * Also called if the overlay's composition is disposed part way through a hide, so the teardown
   * runs whether or not the animation got to finish. Idempotent.
   */
  fun onHideFinished() {
    if (selection == null) {
      return
    }

    isRevealed = false
    menu?.dismiss()

    val action = pendingAction

    selection = null
    messageRecord = null
    focusedView = null
    strip = Strip.EMPTY
    menu = null
    pendingAction = null
    selectedIndex = ReactionScrubber.NO_SELECTION
    isExpanded = false
    pullProgress = 0f
    isPulling = false
    panelBounds.setEmpty()

    onHidden()

    if (action != null) {
      onActionSelected(action)
    }
  }

  fun onShadeProgress(progress: Float) {
    onShadeChanged(progress)
  }

  /** Corrects the offsets from overlay coordinates into the anchor's, which is all the move costs. */
  fun onOriginChanged(origin: Offset) {
    originInWindow = origin
  }

  fun onPlaced(placement: ReactionOverlayPlacement.Placement) {
    val menu = this.menu ?: return

    if (menuPlaced || !isRevealed) {
      return
    }

    placement.menuHeight?.let { menu.height = it }

    val anchorOrigin = IntArray(2).also { menuAnchor().getLocationInWindow(it) }
    val offsetX = originInWindow.x - anchorOrigin[0] + placement.menuOffsetX
    val offsetY = originInWindow.y - anchorOrigin[1] + placement.menuOffsetY

    // Tellomi (A-06): when the message has to travel to make room (the keyboard is up, it sits near an edge), the menu waits
    // for it to land and then grows out of it; growing at once would put the menu over the message's old place mid-move.
    val travel = abs(placement.snapshotY - (selection?.bubbleY ?: placement.snapshotY))
    val growDelayMs = if (travel > MENU_WAIT_TRAVEL_DP * context.resources.displayMetrics.density) {
      context.resources.getInteger(R.integer.reaction_scrubber_reveal_duration).toLong()
    } else {
      0L
    }
    menu.show(offsetX.roundToInt(), offsetY.roundToInt(), growFromLeft = selection?.isMessageOnLeft ?: true, growDelayMs = growDelayMs)
    menuPlaced = true
  }

  /** While nothing is showing this only remembers where a press landed, for the next [show]. */
  fun applyTouchEvent(motionEvent: MotionEvent): Boolean {
    if (isShowing && isExpanded && isRevealed) {
      return applyExpandedTouchEvent(motionEvent)
    }

    if (!isShowing || !scrubber.isShowing) {
      if (motionEvent.action == MotionEvent.ACTION_DOWN) {
        lastSeenDownPoint.set(motionEvent.x, motionEvent.y)
      }

      return false
    }

    val outcome = scrubber.apply(motionEvent.action, motionEvent.x, motionEvent.y)

    when (outcome) {
      is ReactionScrubber.Outcome.Scrubbing -> {
        if (outcome.index != outcome.previousIndex) {
          selectedIndex = outcome.index

          if (outcome.index != ReactionScrubber.NO_SELECTION) {
            // Tellomi (interaction audit A-06): sliding from one slot to the next is a selection tick (SEGMENT_TICK on
            // API 34+), as the motion spec's haptics table says, not a keyboard tap.
            TellomiHaptics.perform(hapticView(), TellomiHaptics.Event.SELECTION_TICK)
          }
        }
      }

      is ReactionScrubber.Outcome.Commit -> commit(outcome.index)
      is ReactionScrubber.Outcome.Dismiss -> hide()
      is ReactionScrubber.Outcome.Pull -> onPull(outcome)
    }

    return outcome.consumed
  }

  /**
   * Tellomi (interaction audit A-06): Telegram ticks as the pull crosses a progress of 1 either way (where letting go
   * would open the picker); here that is the gesture threshold pair rather than a keyboard tap.
   */
  private fun onPull(outcome: ReactionScrubber.Outcome.Pull) {
    val crossedUp = outcome.previousProgress <= 1f && outcome.progress > 1f
    val crossedDown = outcome.previousProgress > 1f && outcome.progress <= 1f && !outcome.isReleased
    if (crossedUp) {
      TellomiHaptics.perform(hapticView(), TellomiHaptics.Event.THRESHOLD_ACTIVATE)
    } else if (crossedDown) {
      TellomiHaptics.perform(hapticView(), TellomiHaptics.Event.THRESHOLD_DEACTIVATE)
    }

    selectedIndex = scrubber.selectedIndex
    pullProgress = outcome.progress
    isPulling = !outcome.isReleased
  }

  /**
   * Tellomi (A-06): once the panel is out, a gesture that starts in it is left to the panel's own views (the grid scrolls,
   * search takes focus); one that starts anywhere else is kept from the conversation behind and closes everything when it
   * lifts, as a tap outside the strip did.
   */
  private fun applyExpandedTouchEvent(motionEvent: MotionEvent): Boolean {
    if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
      touchInPanel = panelBounds.contains(motionEvent.x, motionEvent.y)
    }

    if (touchInPanel) {
      return false
    }

    if (motionEvent.actionMasked == MotionEvent.ACTION_UP) {
      hide()
    }

    return true
  }

  /** Tellomi (A-06): where the expanded panel ended up, in window coordinates. */
  fun onPanelPlaced(bounds: RectF) {
    panelBounds.set(bounds)
  }

  /**
   * Tellomi (A-06): the strip's growth into the panel, 0 to 1. Telegram fades the menu out as it goes and the panel takes
   * its place; the menu is a popup window above this one, so it also has to go once it is invisible or it would keep
   * taking touches meant for the panel.
   */
  fun onExpandProgress(progress: Float) {
    if (!isExpanded) {
      return
    }

    menu?.setContentAlpha(1f - progress)
    if (progress >= 1f) {
      menu?.dismiss()
    }
  }

  /**
   * Tellomi (A-06): a reaction picked in the panel goes the way one picked on the strip does (fly-in, announcement, send),
   * and is remembered as the sheet did: its skin tone as the preferred one, and among recently used unless it took the
   * reaction back.
   */
  fun onPanelEmojiSelected(emoji: String, source: RectF?) {
    val record = messageRecord ?: return
    if (!isRevealed) {
      return
    }

    TellomiHaptics.perform(hapticView(), TellomiHaptics.Event.CONFIRM)
    SignalStore.emoji.setPreferredVariation(emoji)

    val isTakingBack = ReactionMenu.appliedEmoji(record) == emoji
    if (!isTakingBack) {
      RecentEmojiPageModel(context, ReactWithAnyEmojiBottomSheetDialogFragment.REACTION_STORAGE_KEY).onCodePointSelected(emoji)
    }

    onReactionSelected(record, emoji, if (isTakingBack) null else source)
  }

  /** Tellomi (A-06): "Customize reactions" in the panel, as the sheet had it. */
  fun onPanelConfigure() {
    hide()
    context.startActivity(Intent(context, EditReactionsActivity::class.java))
  }

  private fun expand() {
    if (isExpanded || messageRecord == null) {
      return
    }

    scrubber.close()
    selectedIndex = ReactionScrubber.NO_SELECTION
    isPulling = false
    pullProgress = 0f
    touchInPanel = false
    isExpanded = true
  }

  private fun commit(index: Int) {
    val record = messageRecord ?: return

    // Tellomi (interaction audit A-07): lifting the thumb on a reaction confirms it, so it gets the confirm haptic.
    TellomiHaptics.perform(hapticView(), TellomiHaptics.Event.CONFIRM)

    if (index == REACTION_EMOJI_COUNT - 1) {
      // Tellomi (A-06): the expand slot grows the strip into the full picker where it is, rather than opening a sheet.
      expand()
    } else {
      strip.emojiStrings.getOrNull(index)?.let { emoji ->
        // Tellomi (A-07): taking a reaction back does not fly anywhere.
        val flyInSource = if (index != strip.appliedIndex) flyInSource(index) else null
        onReactionSelected(record, SignalStore.emoji.getPreferredVariation(emoji), flyInSource)
      }
    }
  }

  fun onSlotPlaced(index: Int, bounds: RectF) {
    slotBounds.getOrNull(index)?.set(bounds)
  }

  /**
   * Tellomi (A-07 fly-in): where the emoji at [index] is drawn while it is under the thumb — [EMOJI_WIDTH] grown by
   * [GROW_SCALE] and lifted by the scrub translation, centred in its slot — in window coordinates.
   */
  private fun flyInSource(index: Int): RectF? {
    val slot = slotBounds.getOrNull(index)?.takeUnless { it.isEmpty } ?: return null
    val size = EMOJI_WIDTH.value * context.resources.displayMetrics.density * GROW_SCALE
    val lift = context.resources.getDimension(R.dimen.conversation_reaction_scrub_vertical_translation)
    val centerX = slot.centerX()
    val centerY = slot.centerY() - lift

    return RectF(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f)
  }

  private fun onMenuAction(action: ReactionAction) {
    pendingAction = action
    hide()
  }

  /**
   * Tellomi (interaction audit A-06, owner 2026-10-17): seven reactions and an expand slot, as Telegram has. The six the user
   * picked (synced) come first unchanged; the seventh is filled in for display only ([TellomiReactionStrip.reactions]), which
   * is also where a reaction outside the six that is already on the message shows up, so tapping it takes it back.
   */
  private fun buildStrip(record: MessageRecord): Strip {
    val applied = ReactionMenu.appliedEmoji(record)
    val recents = RecentEmojiPageModel(context, ReactWithAnyEmojiBottomSheetDialogFragment.REACTION_STORAGE_KEY).emoji
    val emojiStrings = TellomiReactionStrip.reactions(
      saved = SignalStore.emoji.reactions,
      applied = applied,
      signalDefaults = EmojiValues.DEFAULT_REACTIONS_LIST,
      recents = recents,
      isSame = EmojiUtil::isCanonicallyEqual
    )

    var appliedIndex = ReactionScrubber.NO_SELECTION
    val drawables = mutableListOf<Drawable?>()

    emojiStrings.forEachIndexed { index, emoji ->
      val isApplied = applied != null && EmojiUtil.isCanonicallyEqual(emoji, applied)
      if (isApplied && appliedIndex == ReactionScrubber.NO_SELECTION) {
        appliedIndex = index
        drawables += EmojiProvider.getEmojiDrawable(context, applied, true)
      } else {
        drawables += EmojiProvider.getEmojiDrawable(context, SignalStore.emoji.getPreferredVariation(emoji), true)
      }
    }
    drawables += ContextCompat.getDrawable(context, R.drawable.ic_chevron_down_24)

    return Strip(
      emojiStrings = emojiStrings,
      drawables = drawables,
      appliedIndex = appliedIndex
    )
  }
}

/** Tellomi (A-06): how far the message may move into place before the menu waits for it. */
private const val MENU_WAIT_TRAVEL_DP = 8f

/** What the overlay reports back as it goes away, matching the pair the view code had. */
interface ReactionOverlayHideListener {
  fun startHide(focusedView: View?)

  fun onHide()
}

/**
 * The picture of the pressed row and where it sits, handed over by the fragment that took it.
 *
 * @param returnPosition Where the row is now, for the hide to fly back to. Null once it is gone.
 */
data class ReactionOverlaySnapshot(
  val bitmap: androidx.compose.ui.graphics.ImageBitmap,
  val bubbleX: Float,
  val bubbleY: Float,
  val bubbleWidth: Int,
  val contextMenuX: Float,
  val isMessageOnLeft: Boolean,
  val returnPosition: () -> Offset?
)
