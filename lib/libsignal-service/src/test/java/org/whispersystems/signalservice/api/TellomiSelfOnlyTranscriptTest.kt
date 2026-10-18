/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api

import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.util.Optional
import java.util.UUID

/**
 * Tellomi (ADR-0081 §4.3): the sent transcript that carries a pin / unpin "for me only" to my own devices. The content builder
 * reads its destination unconditionally, so a group's must not be empty.
 */
class TellomiSelfOnlyTranscriptTest {

  private val me = SignalServiceAddress(ACI.from(UUID.randomUUID()))
  private val friend = SignalServiceAddress(ACI.from(UUID.randomUUID()))
  private val unpin = SignalServiceDataMessage.UnpinnedMessage(targetAuthor = friend.serviceId, targetSentTimestamp = 1_000L)

  @Test
  fun `a 1-1 transcript names the other side`() {
    val message = SignalServiceDataMessage.newBuilder().withTimestamp(2_000L).withUnpinnedMessage(unpin).withTellomiPinForSelfOnly(true).build()

    val transcript = SignalServiceMessageSender.tellomiSelfOnlyTranscript(Optional.of(friend), me, message)

    assertEquals(friend, transcript.destination.get())
    assertEquals(2_000L, transcript.timestamp)
  }

  @Test
  fun `a group transcript names me rather than nobody`() {
    val group = SignalServiceGroupV2.newBuilder(GroupMasterKey(ByteArray(32))).build()
    val message = SignalServiceDataMessage.newBuilder().asGroupMessage(group).withTimestamp(2_000L).withUnpinnedMessage(unpin).withTellomiPinForSelfOnly(true).build()

    val transcript = SignalServiceMessageSender.tellomiSelfOnlyTranscript(Optional.empty(), me, message)

    assertEquals(me, transcript.destination.get())
  }
}
