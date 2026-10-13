/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.util

import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.internal.push.TellomiDataMessage

/**
 * Tellomi（ADR-0068 §4.7）：要发的消息里的 `DataMessage.tellomi = 1000`。只有说明在上方时才有，别的消息不写这个字段，字节和上游一样。
 */
object TellomiDataMessages {

  @JvmStatic
  fun tellomiFor(message: SignalServiceDataMessage): TellomiDataMessage? {
    return if (message.tellomiCaptionAboveMedia) TellomiDataMessage(captionAboveMedia = true) else null
  }
}
