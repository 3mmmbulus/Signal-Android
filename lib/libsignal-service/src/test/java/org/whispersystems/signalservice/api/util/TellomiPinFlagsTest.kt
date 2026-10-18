/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.util

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.TellomiDataMessage
import java.util.UUID

/**
 * ADR-0081 (tellomi/tellomi#1197): `DataMessage.tellomi.pinForSelfOnly = 4` (a pin / unpin for my own devices only, sent
 * only in a sync transcript) and `pinSilent = 5` (a group pin for everyone without notifying). No protocol version bump.
 */
class TellomiPinFlagsTest {

  private val pin = SignalServiceDataMessage.PinnedMessage(
    targetAuthor = ACI.from(UUID.fromString("11111111-2222-3333-4444-555555555555")),
    targetSentTimestamp = 1_000L,
    pinDurationInSeconds = null,
    forever = true
  )

  private val unpin = SignalServiceDataMessage.UnpinnedMessage(
    targetAuthor = ACI.from(UUID.fromString("11111111-2222-3333-4444-555555555555")),
    targetSentTimestamp = 1_000L
  )

  private val group = SignalServiceGroupV2.newBuilder(GroupMasterKey(ByteArray(32))).build()

  @Test
  fun `golden bytes shared by the three clients`() {
    assertEquals("c23e022001", DataMessage(tellomi = TellomiDataMessage(pinForSelfOnly = true)).encode().toByteString().hex())
    assertEquals("c23e022801", DataMessage(tellomi = TellomiDataMessage(pinSilent = true)).encode().toByteString().hex())
  }

  @Test
  fun `the flags decode back`() {
    val decoded = DataMessage.ADAPTER.decode("c23e022001".hexToByteArray())
    assertEquals(true, decoded.tellomi?.pinForSelfOnly)
    assertEquals(true, DataMessage.ADAPTER.decode("c23e022801".hexToByteArray()).tellomi?.pinSilent)
  }

  @Test
  fun `self only rides on a pin or an unpin`() {
    assertEquals(
      TellomiDataMessage(pinForSelfOnly = true),
      TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withPinnedMessage(pin).withTellomiPinForSelfOnly(true).build())
    )
    assertEquals(
      TellomiDataMessage(pinForSelfOnly = true),
      TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withUnpinnedMessage(unpin).withTellomiPinForSelfOnly(true).build())
    )
    assertNull("not on anything else", TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withBody("hi").withTellomiPinForSelfOnly(true).build()))
  }

  @Test
  fun `silent only rides on a group pin`() {
    assertEquals(
      TellomiDataMessage(pinSilent = true),
      TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().asGroupMessage(group).withPinnedMessage(pin).withTellomiPinSilent(true).build())
    )
    assertNull("1:1 pins always notify", TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withPinnedMessage(pin).withTellomiPinSilent(true).build()))
    assertNull("not on an unpin", TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().asGroupMessage(group).withUnpinnedMessage(unpin).withTellomiPinSilent(true).build()))
  }

  @Test
  fun `a plain pin keeps upstream bytes`() {
    assertNull(TellomiDataMessages.tellomiFor(SignalServiceDataMessage.newBuilder().withPinnedMessage(pin).build()))
  }

  private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
