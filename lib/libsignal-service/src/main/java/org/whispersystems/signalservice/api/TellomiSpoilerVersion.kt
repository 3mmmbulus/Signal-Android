/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api

import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * Tellomi (tellomi/tellomi#1263, ADR-0068 §4.2–4.4): a data message with any covered pointer -- a body attachment or a quote
 * thumbnail -- requires protocol version 9 (`TELLOMI_MEDIA_SPOILER`). A client that can't cover it then shows "needs a newer
 * version" rather than the picture.
 */
object TellomiSpoilerVersion {
  @JvmStatic
  fun needsSpoilerVersion(attachments: List<AttachmentPointer>, quote: DataMessage.Quote?): Boolean {
    return attachments.any { it.tellomi?.spoiler == true } ||
      quote?.attachments.orEmpty().any { it.thumbnail?.tellomi?.spoiler == true }
  }
}
