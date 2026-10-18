/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import org.signal.core.util.logging.Log
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.ReactionTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（chat-telegram-parity F01）：别人回应了我的消息、我还没在聊天里看到——聊天页右下角回应按钮上的数，数的就是这些消息。
 *
 * Telegram 两端是「看到才算读」：
 * - 安卓 `ChatActivity` 滚动时对每个露出来的 `ChatMessageCell` 调 `checkUnreadReactions`（回应那一行整个在可视区里）才减数；
 *   iOS `ChatHistoryListNode` 把可视范围里有未读回应的消息交给 `unseenReactionsProcessingManager`（0.2 s 后标看到）。
 * - 点按钮跳过去（安卓 `getNextReactionMention`、iOS `earliestUnseenPersonalReactionMessage`）、长按「Read all reactions」也算。
 * - 从对话列表「标为已读」不清回应（安卓 `DialogsActivity.markAsRead` 只清提及）。
 *
 * Signal 自己的 `MessageTable.REACTIONS_UNREAD` 是另一回事：会话一打开、标已读时整条会话一起清掉（给通知用），聊天里的按钮用不了。
 * 所以这里另记一份：别人的回应写进库的那一刻记下（[onReactionAdded]），在聊天里看到 / 跳到 / 全部已读才划掉。
 *
 * - 只在本机（键值库，不进备份），换设备、恢复备份后从空开始；别的设备上看过不会同步过来。
 * - 只记我发的、聊天里（不是动态）的消息；我自己的回应、我别的设备同步过来的回应不记。
 * - 消息删了、别人的回应撤光了，查的时候顺手划掉；我改过的消息换成最新一版（[unseen]）。
 * - 每个会话最多记 [MAX_PER_THREAD] 条，多了丢最早记下的。
 */
object TellomiUnseenReactions {

  private val TAG = Log.tag(TellomiUnseenReactions::class.java)

  /** 每个会话最多记这么多条消息；按钮上的数过了 99 本来也只显示「99+」。 */
  const val MAX_PER_THREAD = 200

  private val lock = Any()
  private var cache: LinkedHashMap<Long, LinkedHashSet<Long>>? = null
  private val changes = PublishSubject.create<Long>().toSerialized()

  /** [threadId] 里记下或划掉了消息时发一次。 */
  fun changes(threadId: Long): Observable<Unit> = changes.filter { it == threadId }.map { }

  /** 有人回应了 [messageId]（[ReactionTable.addReaction] 写完库以后调）。 */
  @WorkerThread
  @JvmStatic
  fun onReactionAdded(messageId: Long, author: RecipientId) {
    if (author == Recipient.self().id) {
      return
    }

    val threadId = outgoingChatMessageThread(messageId) ?: return
    val changed = synchronized(lock) {
      add(map(), threadId, messageId, MAX_PER_THREAD).also { if (it) save() }
    }

    if (changed) {
      changes.onNext(threadId)
    }
  }

  /** [messageId] 上有回应撤了（[ReactionTable.deleteReaction] 之后调）：别人的回应一个不剩，就不算还没看到。 */
  @WorkerThread
  @JvmStatic
  fun onReactionRemoved(messageId: Long) {
    val threadId = synchronized(lock) { map().entries.firstOrNull { messageId in it.value }?.key } ?: return
    if (!hasReactionFromOthers(messageId)) {
      markSeen(threadId, listOf(messageId))
    }
  }

  /** 在聊天里看到了，或者点按钮跳过去了。 */
  @JvmStatic
  fun markSeen(threadId: Long, messageIds: Collection<Long>) {
    if (messageIds.isEmpty()) {
      return
    }

    val changed = synchronized(lock) {
      remove(map(), threadId, messageIds).also { if (it) save() }
    }

    if (changed) {
      changes.onNext(threadId)
    }
  }

  /** 长按回应按钮「所有回应标为已读」。 */
  @JvmStatic
  fun markAllSeen(threadId: Long) {
    val changed = synchronized(lock) {
      (map().remove(threadId) != null).also { if (it) save() }
    }

    if (changed) {
      changes.onNext(threadId)
    }
  }

  /**
   * [threadId] 里还没看到回应的消息，按收到的先后、最早的在前（按钮跳的就是第一条）。
   * 删了的、别人的回应撤光了的顺手划掉；我改过的消息换成最新一版（回应跟着最新一版走，见 `DataMessageProcessor.handleReaction`）。
   */
  @WorkerThread
  fun unseen(threadId: Long): List<Long> {
    val pending = synchronized(lock) { map()[threadId]?.toList() } ?: return emptyList()
    if (pending.isEmpty()) {
      return emptyList()
    }

    val valid = queryValid(threadId, pending)
    val gone = pending.toSet() - valid.toSet()
    val replaced = valid.toSet() - pending.toSet()
    if (gone.isNotEmpty() || replaced.isNotEmpty()) {
      synchronized(lock) {
        val map = map()
        remove(map, threadId, gone)
        replaced.forEach { add(map, threadId, it, MAX_PER_THREAD) }
        save()
      }
    }

    return valid
  }

  private fun map(): LinkedHashMap<Long, LinkedHashSet<Long>> {
    return cache ?: decode(SignalStore.tellomiReactions.unseenSerialized).also { cache = it }
  }

  private fun save() {
    SignalStore.tellomiReactions.unseenSerialized = encode(map())
  }

  /** 我发的、聊天里的消息所在的会话；别的（别人的消息、动态、动态回复）返回 null。 */
  private fun outgoingChatMessageThread(messageId: Long): Long? {
    SignalDatabase.rawDatabase.query(
      MessageTable.TABLE_NAME,
      arrayOf(MessageTable.THREAD_ID, MessageTable.TYPE, MessageTable.STORY_TYPE, MessageTable.PARENT_STORY_ID),
      "${MessageTable.ID} = ?",
      arrayOf(messageId.toString()),
      null,
      null,
      null
    ).use { cursor ->
      if (!cursor.moveToFirst()) {
        return null
      }

      val isChatMessage = cursor.requireInt(MessageTable.STORY_TYPE) == 0 && cursor.requireLong(MessageTable.PARENT_STORY_ID) <= 0
      if (!isChatMessage || !MessageTypes.isOutgoingMessageType(cursor.requireLong(MessageTable.TYPE))) {
        return null
      }

      return cursor.requireLong(MessageTable.THREAD_ID)
    }
  }

  private fun hasReactionFromOthers(messageId: Long): Boolean {
    SignalDatabase.rawDatabase.rawQuery(
      "SELECT 1 FROM ${ReactionTable.TABLE_NAME} WHERE ${ReactionTable.MESSAGE_ID} = ? AND ${ReactionTable.AUTHOR_ID} != ? LIMIT 1",
      arrayOf(messageId.toString(), Recipient.self().id.serialize())
    ).use { cursor ->
      return cursor.moveToFirst()
    }
  }

  private fun queryValid(threadId: Long, messageIds: List<Long>): List<Long> {
    val placeholders = messageIds.joinToString(",") { "?" }
    val sql = """
      SELECT t.${MessageTable.ID}, t.${MessageTable.TYPE}
      FROM ${MessageTable.TABLE_NAME} AS t
      WHERE t.${MessageTable.ID} IN (
        SELECT COALESCE(m.${MessageTable.LATEST_REVISION_ID}, m.${MessageTable.ID})
        FROM ${MessageTable.TABLE_NAME} AS m
        WHERE m.${MessageTable.ID} IN ($placeholders)
      )
      AND t.${MessageTable.THREAD_ID} = ?
      AND t.${MessageTable.STORY_TYPE} = 0
      AND t.${MessageTable.PARENT_STORY_ID} <= 0
      AND t.${MessageTable.DELETED_BY} IS NULL
      AND EXISTS (
        SELECT 1 FROM ${ReactionTable.TABLE_NAME} AS r
        WHERE r.${ReactionTable.MESSAGE_ID} = t.${MessageTable.ID} AND r.${ReactionTable.AUTHOR_ID} != ?
      )
      ORDER BY t.${MessageTable.DATE_RECEIVED} ASC, t.${MessageTable.ID} ASC
    """
    val args = messageIds.map { it.toString() } + threadId.toString() + Recipient.self().id.serialize()

    val valid = mutableListOf<Long>()
    SignalDatabase.rawDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
      while (cursor.moveToNext()) {
        if (MessageTypes.isOutgoingMessageType(cursor.requireLong(MessageTable.TYPE))) {
          valid += cursor.requireLong(MessageTable.ID)
        }
      }
    }

    if (valid.size != messageIds.size) {
      Log.d(TAG, "Thread $threadId: ${messageIds.size} recorded, ${valid.size} still unseen.")
    }

    return valid
  }

  /** `会话:消息,消息;会话:消息`；认不出的段落跳过。 */
  internal fun decode(serialized: String): LinkedHashMap<Long, LinkedHashSet<Long>> {
    val map = LinkedHashMap<Long, LinkedHashSet<Long>>()
    for (entry in serialized.split(';')) {
      val threadId = entry.substringBefore(':', "").toLongOrNull() ?: continue
      val messageIds = entry.substringAfter(':', "").split(',').mapNotNullTo(LinkedHashSet()) { it.toLongOrNull() }
      if (messageIds.isNotEmpty()) {
        map[threadId] = messageIds
      }
    }
    return map
  }

  internal fun encode(map: Map<Long, Set<Long>>): String {
    return map.entries
      .filter { it.value.isNotEmpty() }
      .joinToString(";") { (threadId, messageIds) -> "$threadId:${messageIds.joinToString(",")}" }
  }

  /** 记下一条；已经记着就不动。超过 [max] 条丢最早记下的。返回有没有变。 */
  internal fun add(map: MutableMap<Long, LinkedHashSet<Long>>, threadId: Long, messageId: Long, max: Int): Boolean {
    val messageIds = map.getOrPut(threadId) { LinkedHashSet() }
    if (!messageIds.add(messageId)) {
      return false
    }

    while (messageIds.size > max) {
      messageIds.remove(messageIds.first())
    }
    return true
  }

  /** 划掉；一个会话划空了就整个去掉。返回有没有变。 */
  internal fun remove(map: MutableMap<Long, LinkedHashSet<Long>>, threadId: Long, messageIds: Collection<Long>): Boolean {
    val recorded = map[threadId] ?: return false
    val changed = recorded.removeAll(messageIds.toSet())
    if (recorded.isEmpty()) {
      map.remove(threadId)
    }
    return changed
  }
}
