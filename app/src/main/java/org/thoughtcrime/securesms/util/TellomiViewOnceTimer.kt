/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.whispersystems.signalservice.api.util.TellomiDataMessages
import org.whispersystems.signalservice.internal.push.DataMessage
import kotlin.math.max

/**
 * Tellomi（tellomi/tellomi#1714，ADR-0079）：照片 / 视频的计时——一次性查看打开后只看 N 秒（3 / 10 / 30）。
 *
 * 线上是 `DataMessage.tellomi.viewOnceSeconds`，只和 `isViewOnce = true` 一起有意义；不提协议版本，认不得的老版本照普通一次性查看显示
 * （看一次、不限时）。本机记在消息 `message_extras` 的 [org.thoughtcrime.securesms.database.model.databaseprotos.TellomiMessageExtras]
 * 里（同说明在上方），不进备份（§4.5）。打开照 Signal 一次性查看的流程走，查看器只留 [viewerDurationMillis] 这么久。
 */
object TellomiViewOnceTimer {

  @JvmStatic
  fun isAllowed(seconds: Int): Boolean = seconds in TellomiDataMessages.VIEW_ONCE_SECONDS

  /** 收到的 DataMessage 上的计时：一次性查看、而且是认得的那几档才算；别的当普通一次性查看（ADR-0079 §4.1）。 */
  @JvmStatic
  fun seconds(message: DataMessage): Int? {
    if (message.isViewOnce != true) {
      return null
    }
    return message.tellomi?.viewOnceSeconds?.takeIf { isAllowed(it) }
  }

  /** 本机记着的秒数；没有就是 null。 */
  @JvmStatic
  fun seconds(extras: MessageExtras?): Int? = extras?.tellomi?.viewOnceSeconds?.takeIf { isAllowed(it) }

  @JvmStatic
  fun seconds(record: MessageRecord?): Int? = if (record?.isViewOnce == true) seconds(record.messageExtras) else null

  /** 查看器开多久：照片 N 秒；视频取 max(时长, N)（ADR-0079 §4.3，照 Telegram iOS）。 */
  @JvmStatic
  fun viewerDurationMillis(seconds: Int, videoDurationMillis: Long?): Long = max(seconds * 1000L, videoDurationMillis ?: 0L)
}
