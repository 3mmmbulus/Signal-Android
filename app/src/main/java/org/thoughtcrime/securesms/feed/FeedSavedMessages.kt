/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.jobs.MultiDeviceDeleteSyncJob
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import java.util.concurrent.TimeUnit

/**
 * 收藏动态 = 存进「我的收藏」（第三节第 27 条；ADR-0071「收藏」：两个收藏合并，取消收藏就删掉那张卡）。
 * 端到端加密、电脑上也能看，都是 Signal 给自己发消息本来就有的。
 */
interface FeedSavedMessages {
  /** 给自己发一条带动态链接的消息。 */
  suspend fun add(card: FeedCard)

  /** 删掉这台手机存过的那条（同步给已关联设备）；没存过或已经手动删了就什么都不做。 */
  suspend fun remove(contentId: String)
}

/** 「我的收藏」里那条消息的正文：作者和开头一段给电脑端和没装渲染器的版本看，最后一行是 `tell.cc/m#<id>`。 */
internal fun FeedCard.savedMessageBody(link: String): String {
  val name = author?.displayName?.trim().orEmpty()
  val snippet = (title?.takeIf { it.isNotBlank() } ?: text.orEmpty()).trim().replace('\n', ' ').takeCodePointsWithEllipsis(SNIPPET_CHARS)
  val head = listOf(name, snippet).filter { it.isNotEmpty() }.joinToString("：")
  return if (head.isEmpty()) link else "$head\n$link"
}

private const val SNIPPET_CHARS = 60

/**
 * 收藏 / 取消收藏的两步：先存进或删掉「我的收藏」里那条（失败就抛，调用方把界面改回来），
 * 再以假名上报（第三节第 27 条）；上报失败不影响收藏本身，只记日志。
 */
internal suspend fun FeedSavedMessages.setSaved(card: FeedCard, saved: Boolean, source: FeedSource, ioDispatcher: CoroutineDispatcher) {
  withContext(ioDispatcher) { if (saved) add(card) else remove(card.id) }
  try {
    withContext(ioDispatcher) { source.setSaved(card.id, saved) }
  } catch (e: Exception) {
    Log.w(SAVE_TAG, "save report failed", e)
  }
}

private val SAVE_TAG = Log.tag(FeedSavedMessages::class)

object NoteToSelfFeedSavedMessages : FeedSavedMessages {

  private val TAG = Log.tag(NoteToSelfFeedSavedMessages::class)

  /** 动态 id → 发给自己那条消息的发送时间（用它找回消息）。只记这台手机发的。 */
  private const val PREFS = "tellomi_feed_saved_messages"

  private fun prefs() = AppDependencies.application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override suspend fun add(card: FeedCard) {
    withContext(Dispatchers.IO) {
      val self = Recipient.self()
      val sentAt = System.currentTimeMillis()
      val message = OutgoingMessage.text(
        threadRecipient = self,
        body = card.savedMessageBody(FeedShare.link(card)),
        expiresIn = TimeUnit.SECONDS.toMillis(self.expiresInSeconds.toLong()),
        sentTimeMillis = sentAt
      )
      MessageSender.send(AppDependencies.application, message, -1, MessageSender.SendType.SIGNAL, null, null)
      prefs().edit().putLong(card.id, sentAt).apply()
    }
  }

  override suspend fun remove(contentId: String) {
    withContext(Dispatchers.IO) {
      val sentAt = prefs().getLong(contentId, 0L)
      if (sentAt == 0L) return@withContext
      val record = SignalDatabase.messages.getMessageFor(sentAt, Recipient.self().id)
      if (record != null) {
        SignalDatabase.messages.deleteMessage(record.id)
        MultiDeviceDeleteSyncJob.enqueueMessageDeletes(setOf(record))
      } else {
        Log.i(TAG, "saved message already gone")
      }
      prefs().edit().remove(contentId).apply()
    }
  }
}
