/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

/**
 * Tellomi（pinned-messages.md 3.5 / 3.6）：取消了置顶、还没真的生效的那几条（单条取消，或置顶列表里的「取消所有置顶」）。
 *
 * - 「撤销」的 5 秒里：置顶栏上已经没有它们，还能撤销；
 * - 5 秒过了：发出去。发出去以后、置顶真的没了以前，栏上仍然没有它们（不会先跳回来再消失）；发不出去就放回栏上；
 * - 置顶真的没了就不再记着：以后再被置顶要回到栏上。
 *
 * 两端 Telegram 都是这样延后发的（安卓 `BulletinFactory.createUnpinAllMessagesBulletin`、iOS `ChatController.performRequestedUnpinAllMessages`）。
 * 与 iOS `TellomiHeldUnpins` 同一套。
 */
class TellomiHeldUnpins {

  /** 栏上不显示的：还在等撤销的，和已经发出去、置顶还没真没了的。 */
  var held: Set<Long> = emptySet()
    private set

  private var undoable: Set<Long> = emptySet()

  @Synchronized
  fun hold(messageIds: Collection<Long>) {
    held = held + messageIds
    undoable = undoable + messageIds
  }

  /** 撤销：还能撤销的放回栏上；返回放回去的。 */
  @Synchronized
  fun undo(messageIds: Collection<Long>): Set<Long> {
    val back = messageIds.filterTo(LinkedHashSet()) { it in undoable }
    undoable = undoable - back
    held = held - back
    return back
  }

  /** 撤销的时间过了：还没撤销的就要发出去（栏上仍然不显示）；返回要发的。 */
  @Synchronized
  fun commit(messageIds: Collection<Long>): Set<Long> {
    val send = messageIds.filterTo(LinkedHashSet()) { it in undoable }
    undoable = undoable - send
    return send
  }

  /** 会话关掉时：还在等撤销的全部发出去。 */
  @Synchronized
  fun commitAll(): Set<Long> {
    return commit(undoable)
  }

  /** 发不出去：放回栏上。 */
  @Synchronized
  fun failed(messageIds: Collection<Long>) {
    held = held - messageIds.toSet()
  }

  /** 置顶的消息有变化时：发出去、置顶也真没了的不再记着。 */
  @Synchronized
  fun prune(pinnedMessageIds: Set<Long>) {
    held = held.filterTo(LinkedHashSet()) { it in undoable || it in pinnedMessageIds }
  }
}
