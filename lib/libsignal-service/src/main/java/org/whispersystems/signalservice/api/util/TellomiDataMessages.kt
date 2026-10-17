/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.util

import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.internal.push.TellomiDataMessage

/**
 * Tellomi（ADR-0068 §4.7）：要发的消息里的 `DataMessage.tellomi = 1000`。只有说明在上方、或是计时的一次性查看（ADR-0079）时才有，
 * 别的消息不写这个字段，字节和上游一样；都不提协议版本。
 */
object TellomiDataMessages {

  /** ADR-0079 §4.1：只认这几档。 */
  @JvmField
  val VIEW_ONCE_SECONDS: Set<Int> = setOf(3, 10, 30)

  @JvmStatic
  fun tellomiFor(message: SignalServiceDataMessage): TellomiDataMessage? {
    val seconds = message.tellomiViewOnceSeconds?.takeIf { message.isViewOnce && it in VIEW_ONCE_SECONDS }
    if (!message.tellomiCaptionAboveMedia && seconds == null) {
      return null
    }
    return TellomiDataMessage(
      captionAboveMedia = if (message.tellomiCaptionAboveMedia) true else null,
      viewOnceSeconds = seconds
    )
  }
}
