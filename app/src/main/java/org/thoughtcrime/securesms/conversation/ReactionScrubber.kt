/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.view.MotionEvent
import org.thoughtcrime.securesms.conversation.v2.TellomiReactionStrip
import kotlin.math.abs

/**
 * The scrub gesture behind the reaction overlay
 *
 * @param emojiCount Slots in the strip. The last opens the full picker.
 * @param deadZoneSize How far a pointer may drift before a tap becomes a scrub. Deliberately not
 *   part of [Geometry]: it owes nothing to where the strip landed, and a message that takes no
 *   reactions never lays one out, so a geometry-carried value would stay zero and turn every lift
 *   into a dismiss.
 * @param pullUnit Tellomi (interaction audit A-06): how far a pull on the last slot has to go for a progress of 1, in
 *   the gesture's units ([TellomiReactionStrip.PULL_UNIT_DP]); zero turns pulling off.
 */
class ReactionScrubber(private val emojiCount: Int, private val deadZoneSize: Float, private val pullUnit: Float = 0f) {

  /**
   * Where the strip ended up, in the coordinate space the gesture arrives in.
   *
   * [stripStart] and [stripEnd] are in layout direction order, so under RTL start is the greater of
   * the two and the segment width comes out negative. Deliberately not normalised: the sign is what
   * decides which emoji an x belongs to.
   *
   * @param scrubTop Top of the taller band a scrub may wander through, down to [scrubBottom].
   * @param isStripVisible False for a message that takes no reactions, making every point a miss.
   */
  data class Geometry(
    val stripStart: Float = 0f,
    val stripEnd: Float = 0f,
    val stripTop: Float = 0f,
    val stripBottom: Float = 0f,
    val scrubTop: Float = 0f,
    val scrubBottom: Float = 0f,
    val isStripVisible: Boolean = false
  )

  enum class Phase {
    HIDDEN,
    UNINITIALIZED,
    DEADZONE,
    SCRUB,
    TAP,

    /** Tellomi (A-06): a touch that went down on the last slot is dragging toward the strip's start. */
    PULL
  }

  /**
   * [consumed] is not the same as "something changed": a gesture that began outside the scrubber is
   * watched without being claimed.
   */
  sealed interface Outcome {
    val consumed: Boolean

    data class Scrubbing(override val consumed: Boolean, val previousIndex: Int, val index: Int) : Outcome

    data class Commit(override val consumed: Boolean, val index: Int) : Outcome

    data class Dismiss(override val consumed: Boolean) : Outcome

    /**
     * Tellomi (interaction audit A-06): the last slot is being pulled toward the strip's start, Telegram's way into the
     * full picker. [progress] is 0 to [TellomiReactionStrip.PULL_MAX_PROGRESS]; [isReleased] means the thumb lifted
     * short of [TellomiReactionStrip.PULL_EXPAND_THRESHOLD] and the strip should spring back. A lift past it is a
     * [Commit] of the last slot instead.
     */
    data class Pull(override val consumed: Boolean, val previousProgress: Float, val progress: Float, val isReleased: Boolean) : Outcome
  }

  var geometry: Geometry = Geometry()

  var phase: Phase = Phase.HIDDEN
    private set

  var selectedIndex: Int = NO_SELECTION
    private set

  private var downIsOurs: Boolean = false
  private var downIndex: Int = NO_SELECTION
  private var pullProgress: Float = 0f

  private var deadZoneX: Float = 0f
  private var deadZoneY: Float = 0f

  val isShowing: Boolean
    get() = phase != Phase.HIDDEN

  fun open() {
    phase = Phase.UNINITIALIZED
    selectedIndex = NO_SELECTION
    downIsOurs = false
    downIndex = NO_SELECTION
    pullProgress = 0f
  }

  fun close() {
    phase = Phase.HIDDEN
  }

  /** [action] is a raw [MotionEvent] action, so a non-primary pointer is swallowed rather than obeyed. */
  fun apply(action: Int, x: Float, y: Float): Outcome {
    check(isShowing) { "Touch events should only reach the scrubber while it is showing." }

    if (action and MotionEvent.ACTION_POINTER_INDEX_MASK != 0) {
      return unchanged(consumed = true)
    }

    if (phase == Phase.UNINITIALIZED) {
      downIsOurs = false
      deadZoneX = x
      deadZoneY = y
      phase = Phase.DEADZONE
    }

    if (phase == Phase.DEADZONE && startsPull(x, y)) {
      // Tellomi (A-06): decided well inside the dead zone, which is too wide for a pull to wait for.
      phase = Phase.PULL
    }

    if (phase == Phase.DEADZONE) {
      val escapedDeadZone = abs(deadZoneX - x) > deadZoneSize || abs(deadZoneY - y) > deadZoneSize

      if (escapedDeadZone) {
        phase = Phase.SCRUB
      } else {
        if (action == MotionEvent.ACTION_UP) {
          phase = Phase.TAP

          if (downIsOurs) {
            return endGesture(consumed = true)
          }
        }

        return unchanged(consumed = action == MotionEvent.ACTION_MOVE)
      }
    }

    if (phase == Phase.PULL) {
      return pull(action, x)
    }

    return when (action) {
      MotionEvent.ACTION_DOWN -> {
        val outcome = moveSelectionTo(selectionAt(x, y, geometry.stripTop, geometry.stripBottom), consumed = true)
        deadZoneX = x
        deadZoneY = y
        phase = Phase.DEADZONE
        downIsOurs = true
        downIndex = outcome.index
        outcome
      }

      MotionEvent.ACTION_MOVE -> moveSelectionTo(selectionAt(x, y, geometry.scrubTop, geometry.scrubBottom), consumed = true)

      MotionEvent.ACTION_UP -> endGesture(consumed = downIsOurs)

      MotionEvent.ACTION_CANCEL -> {
        close()
        Outcome.Dismiss(consumed = downIsOurs)
      }

      else -> unchanged(consumed = false)
    }
  }

  /** A gesture that outlives the strip cannot apply a reaction. */
  private fun endGesture(consumed: Boolean): Outcome {
    if (selectedIndex != NO_SELECTION && geometry.isStripVisible) {
      return Outcome.Commit(consumed = consumed, index = selectedIndex)
    }

    close()
    return Outcome.Dismiss(consumed = consumed)
  }

  /**
   * Tellomi (interaction audit A-06): Telegram's strip scrolls, and dragging on past its end pulls the picker open. This
   * strip does not scroll, and a drag along it already picks a reaction, so only a touch that went down on the last slot
   * and leaves it toward the start — past a touch slop, more along the strip than across it — pulls.
   */
  private fun startsPull(x: Float, y: Float): Boolean {
    val toward = towardStart(x)
    return pullUnit > 0f &&
      downIsOurs &&
      downIndex == emojiCount - 1 &&
      geometry.isStripVisible &&
      toward > pullSlop() &&
      toward > abs(deadZoneY - y)
  }

  /** [TellomiReactionStrip.PULL_SLOP_DP] in the gesture's units, which [pullUnit] is the 42 dp of. */
  private fun pullSlop(): Float = pullUnit * TellomiReactionStrip.PULL_SLOP_DP / TellomiReactionStrip.PULL_UNIT_DP

  /** How far [x] is from where the touch went down, toward the strip's start; negative going the other way. */
  private fun towardStart(x: Float): Float {
    val direction = if (geometry.stripEnd < geometry.stripStart) -1f else 1f
    return (deadZoneX - x) * direction
  }

  /** The pull's progress at [x], counted from where it got past the slop. */
  private fun pullProgressAt(x: Float): Float = TellomiReactionStrip.pullProgress(towardStart(x) - pullSlop(), pullUnit)

  private fun pull(action: Int, x: Float): Outcome {
    val previous = pullProgress

    return when (action) {
      MotionEvent.ACTION_MOVE -> {
        pullProgress = pullProgressAt(x)
        Outcome.Pull(consumed = true, previousProgress = previous, progress = pullProgress, isReleased = false)
      }

      MotionEvent.ACTION_UP -> {
        val progress = pullProgressAt(x)
        pullProgress = 0f

        if (progress > TellomiReactionStrip.PULL_EXPAND_THRESHOLD) {
          selectedIndex = emojiCount - 1
          Outcome.Commit(consumed = true, index = emojiCount - 1)
        } else {
          // Back to waiting for the next touch, as after a tap.
          phase = Phase.TAP
          selectedIndex = NO_SELECTION
          Outcome.Pull(consumed = true, previousProgress = previous, progress = 0f, isReleased = true)
        }
      }

      MotionEvent.ACTION_CANCEL -> {
        pullProgress = 0f
        close()
        Outcome.Dismiss(consumed = true)
      }

      else -> Outcome.Pull(consumed = true, previousProgress = previous, progress = previous, isReleased = false)
    }
  }

  private fun moveSelectionTo(index: Int, consumed: Boolean): Outcome.Scrubbing {
    val previousIndex = selectedIndex
    selectedIndex = index

    return Outcome.Scrubbing(consumed = consumed, previousIndex = previousIndex, index = index)
  }

  private fun unchanged(consumed: Boolean): Outcome {
    return Outcome.Scrubbing(consumed = consumed, previousIndex = selectedIndex, index = selectedIndex)
  }

  private fun selectionAt(x: Float, y: Float, bandStart: Float, bandEnd: Float): Int {
    if (!geometry.isStripVisible) {
      return NO_SELECTION
    }

    val segmentSize = segmentSize()
    var selection = NO_SELECTION

    for (index in 0 until emojiCount) {
      val segmentStart = segmentSize * index + geometry.stripStart

      if (contains(segmentStart, segmentStart + segmentSize, x) && contains(bandStart, bandEnd, y)) {
        selection = index
      }
    }

    return selection
  }

  private fun segmentSize(): Float {
    return (geometry.stripEnd - geometry.stripStart) / emojiCount
  }

  companion object {
    const val NO_SELECTION = -1

    /** Exclusive on both ends, and indifferent to which of [min] and [max] is greater. */
    private fun contains(min: Float, max: Float, value: Float): Boolean {
      return if (min < max) {
        min < value && max > value
      } else {
        min > value && max < value
      }
    }
  }
}
