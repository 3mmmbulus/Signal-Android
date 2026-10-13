/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.util

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * ADR-0068 §4.7 (tellomi/tellomi#1263): a message whose caption sits above its media carries `DataMessage.tellomi = 1000`
 * with `captionAboveMedia`; any other message carries nothing, so its bytes stay upstream's.
 */
class TellomiDataMessagesTest {

  @Test
  fun `caption above writes the 1000 field with the golden bytes shared by the three clients`() {
    val message = SignalServiceDataMessage.newBuilder().withBody("caption").withTellomiCaptionAboveMedia(true).build()

    val tellomi = TellomiDataMessages.tellomiFor(message)

    assertEquals("c23e020801", DataMessage(tellomi = tellomi).encode().toByteString().hex())
  }

  @Test
  fun `no caption above, no field`() {
    assertNull(TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withBody("caption").build()))
    assertNull(TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withBody("caption").withTellomiCaptionAboveMedia(false).build()))
  }
}
