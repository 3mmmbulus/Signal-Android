/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.transport.UndeliverableMessageException
import java.io.IOException

/**
 * Tellomi（ADR-0081 §4.3）：取消「仅为我」的置顶——先告诉自己的其它设备（带 `pinForSelfOnly` 的「已发送」同步），再在这台取消；
 * 从不给对方发取消。长按「取消置顶」和置顶列表的「取消所有置顶」都走这里。
 */
object TellomiPinForMe {

  @WorkerThread
  @Throws(IOException::class, UndeliverableMessageException::class)
  @JvmStatic
  fun unpin(messageId: Long) {
    val message = SignalDatabase.messages.getMessageRecord(messageId)
    val threadRecipient = SignalDatabase.threads.getRecipientForThreadId(message.threadId) ?: throw IOException("No chat for the pinned message")
    PinSendUtil.sendSelfOnlyUnpin(threadRecipient, message.fromRecipient.requireServiceId(), message.dateSent, System.currentTimeMillis())
    SignalDatabase.messages.unpinMessage(messageId = messageId, threadId = message.threadId)
    AppDependencies.databaseObserver.notifyConversationListeners(message.threadId)
  }
}
