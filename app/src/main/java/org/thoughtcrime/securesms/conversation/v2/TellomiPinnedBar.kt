/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

/**
 * Tellomi（pinned-messages.md 3.4）：会话顶上的置顶栏显示哪一条、标题、左边的竖线。置顶按消息在会话里的先后排（最早的在前），
 * 不按什么时候置顶的排——两端 Telegram 都这样。
 *
 * 照 Telegram 两端（只读机制，没搬代码）：
 * - 显示哪一条：屏幕上最新那条消息之前（含）最新的一条置顶；都比它新就显示最早的。iOS `ChatController.swift` 的
 *   置顶信号（按可见范围上界取 `id ≤` 的最新一条）；安卓 `ChatActivity.findClosest`（`maxVisibleId`）。
 * - 点一下：跳到正显示的这条，然后换成更早的一条，最早的之后回到最新的。iOS `scrolledToMessageIdValue` 之后取更早的一条、
 *   最早的回到最新；安卓 `forceNextPinnedMessageId = currentPinned - 1`。
 * - 标题：只有一条「置顶消息」；两条时较早的那条是「上一条置顶」；多于两条时最新的不带编号，其余「置顶消息 #N」，最早的是 #1。
 *   iOS `ChatPinnedMessageTitlePanelNode`（`Conversation_PinnedMessage` / `Conversation_PinnedPreviousMessage` / `#N`）；
 *   安卓 `ChatActivity` 标题 + `NumberTextView` 计数。
 * - 竖线：最早的在上；多于 3 条只露 3 段，选中的尽量在中间，上下有更多时渐隐。安卓 `PinnedLineView`（最多 3 格、6dp 渐隐）；
 *   iOS `AnimatedNavigationStripeNode` 多了也滚动、两端渐隐。
 */
object TellomiPinnedBar {

  /** 竖线最多露几段。 */
  const val VISIBLE_SEGMENTS = 3

  enum class TitleKind { PINNED, PREVIOUS, NUMBERED }

  data class Title(val kind: TitleKind, val number: Int = 0)

  /** 竖线露出来的那几段：从第 [first] 段起 [count] 段，上 / 下还有没露出来的就渐隐。 */
  data class Segments(val first: Int, val count: Int, val fadeTop: Boolean, val fadeBottom: Boolean)

  /** 滚动时显示哪一条：[pinDates]（最早的在前）里不晚于 [newestVisibleDate] 的最新一条；都更晚就是最早的那条。 */
  @JvmStatic
  fun pinFor(pinDates: List<Long>, newestVisibleDate: Long): Int {
    if (pinDates.isEmpty()) {
      return 0
    }
    val atOrBefore = pinDates.indexOfLast { it <= newestVisibleDate }
    return if (atOrBefore >= 0) atOrBefore else 0
  }

  /** 点了第 [index] 条（跳过去）之后显示的那条：更早的一条，最早的之后是最新的。 */
  @JvmStatic
  fun nextAfterTap(index: Int, size: Int): Int {
    return if (index <= 0) size - 1 else index - 1
  }

  @JvmStatic
  fun title(index: Int, size: Int): Title {
    return when {
      size <= 1 || index == size - 1 -> Title(TitleKind.PINNED)
      size == 2 -> Title(TitleKind.PREVIOUS)
      else -> Title(TitleKind.NUMBERED, index + 1)
    }
  }

  @JvmStatic
  fun segments(index: Int, size: Int): Segments {
    if (size <= VISIBLE_SEGMENTS) {
      return Segments(first = 0, count = size, fadeTop = false, fadeBottom = false)
    }
    val first = (index - 1).coerceIn(0, size - VISIBLE_SEGMENTS)
    return Segments(first = first, count = VISIBLE_SEGMENTS, fadeTop = first > 0, fadeBottom = first + VISIBLE_SEGMENTS < size)
  }

  /**
   * 点过之后定住的那一条什么时候放开，回到跟着滚动走：用户自己往新消息那边拖就放开；往旧消息那边拖，拖过了那一条才放开
   * （安卓 `ChatActivity` 拖动时清掉 `forceNextPinnedMessageId` 的两种情况；iOS 同样）。跳转本身的滚动不算。
   */
  @JvmStatic
  fun releasesForcedPin(userDragging: Boolean, newestVisibleBefore: Long, newestVisibleNow: Long, forcedPinDate: Long): Boolean {
    if (!userDragging) {
      return false
    }
    return newestVisibleNow > newestVisibleBefore || newestVisibleNow < forcedPinDate
  }
}
