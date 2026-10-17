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
import org.whispersystems.signalservice.internal.push.TellomiDataMessage

/**
 * ADR-0079 (tellomi/tellomi#1714): a timed view-once message carries `DataMessage.tellomi.viewOnceSeconds` (3 / 10 / 30)
 * next to `isViewOnce`, with no protocol version bump.
 */
class TellomiViewOnceSecondsTest {

  @Test
  fun `golden bytes shared by the three clients`() {
    assertEquals("c23e021803", DataMessage(tellomi = TellomiDataMessage(viewOnceSeconds = 3)).encode().toByteString().hex())
    assertEquals("c23e02181e", DataMessage(tellomi = TellomiDataMessage(viewOnceSeconds = 30)).encode().toByteString().hex())
  }

  @Test
  fun `a timed view once message goes out with its seconds`() {
    val message = SignalServiceDataMessage.newBuilder().withViewOnce(true).withTellomiViewOnceSeconds(10).build()
    assertEquals(TellomiDataMessage(viewOnceSeconds = 10), TellomiDataMessages.tellomiFor(message))
  }

  @Test
  fun `seconds only ride on view once, and only the allowed ones`() {
    assertNull(TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withViewOnce(false).withTellomiViewOnceSeconds(10).build()))
    for (odd in listOf(0, 5, 60)) {
      assertNull(TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withViewOnce(true).withTellomiViewOnceSeconds(odd).build()))
    }
    assertNull("plain view once: upstream bytes", TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withViewOnce(true).build()))
  }

  @Test
  fun `caption above and seconds share the field`() {
    val message = SignalServiceDataMessage.newBuilder().withViewOnce(true).withTellomiViewOnceSeconds(3).withTellomiCaptionAboveMedia(true).build()
    assertEquals(TellomiDataMessage(captionAboveMedia = true, viewOnceSeconds = 3), TellomiDataMessages.tellomiFor(message))
  }
}
