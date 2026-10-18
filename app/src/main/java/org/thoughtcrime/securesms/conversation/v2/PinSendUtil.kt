package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import org.signal.core.models.ServiceId
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupAccessControl
import org.thoughtcrime.securesms.groups.GroupNotAMemberException
import org.thoughtcrime.securesms.messages.GroupSendUtil
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.transport.UndeliverableMessageException
import org.thoughtcrime.securesms.util.GroupUtil
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Companion.newBuilder
import java.io.IOException
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Functions used when pinning/unpinning messages
 */
object PinSendUtil {

  private val PIN_TERMINATE_TIMEOUT = 7000.milliseconds

  @Throws(IOException::class, GroupNotAMemberException::class, UndeliverableMessageException::class)
  fun sendPinMessage(applicationContext: Context, threadRecipient: Recipient, message: OutgoingMessage, destinations: List<Recipient>, includeSelf: Boolean, relatedMessageId: Long, silent: Boolean = false): List<SendMessageResult?> {
    val builder = newBuilder()
    val groupId = if (threadRecipient.isPushV2Group) threadRecipient.requireGroupId().requireV2() else null

    if (groupId != null) {
      val groupRecord: GroupRecord? = SignalDatabase.groups.getGroup(groupId).getOrNull()

      if (groupRecord != null && !groupRecord.isActive) {
        throw UndeliverableMessageException("Cannot pin messages in an inactive group!")
      }

      if (groupRecord != null && groupRecord.attributesAccessControl == GroupAccessControl.ONLY_ADMINS && !groupRecord.isAdmin(Recipient.self())) {
        throw UndeliverableMessageException("Non-admins cannot pin messages!")
      }
      GroupUtil.setDataMessageGroupContext(AppDependencies.application, builder, groupId)
    }

    val sentTime = System.currentTimeMillis()
    val message = builder
      .withTimestamp(sentTime)
      .withExpiration((message.expiresIn / 1000).toInt())
      .withProfileKey(ProfileKeyUtil.getSelfProfileKey().serialize())
      .withPinnedMessage(
        SignalServiceDataMessage.PinnedMessage(
          targetAuthor = ServiceId.parseOrThrow(message.messageExtras!!.pinnedMessage!!.targetAuthorAci),
          targetSentTimestamp = message.messageExtras.pinnedMessage.targetTimestamp,
          pinDurationInSeconds = message.messageExtras.pinnedMessage.pinDurationInSeconds.takeIf { it != MessageTable.PIN_FOREVER }?.toInt(),
          forever = (message.messageExtras.pinnedMessage.pinDurationInSeconds == MessageTable.PIN_FOREVER).takeIf { it }
        )
      )
      // Tellomi (ADR-0081 §4.6): a group pin for everyone without notifying.
      .withTellomiPinSilent(silent)
      .build()

    return if (includeSelf) {
      listOf(AppDependencies.signalServiceMessageSender.sendSyncMessage(message))
    } else {
      GroupSendUtil.sendResendableDataMessage(
        applicationContext,
        groupId,
        null,
        destinations,
        false,
        ContentHint.RESENDABLE,
        MessageId(relatedMessageId),
        message,
        false,
        false,
        null
      ) { System.currentTimeMillis() - sentTime > PIN_TERMINATE_TIMEOUT.inWholeMilliseconds }
    }
  }

  @Throws(IOException::class, GroupNotAMemberException::class, UndeliverableMessageException::class)
  fun sendUnpinMessage(applicationContext: Context, threadRecipient: Recipient, targetAuthor: ServiceId, targetSentTimestamp: Long, destinations: List<Recipient>, includeSelf: Boolean, relatedMessageId: Long): List<SendMessageResult?> {
    val builder = newBuilder()
    val groupId = if (threadRecipient.isPushV2Group) threadRecipient.requireGroupId().requireV2() else null
    if (groupId != null) {
      val groupRecord: GroupRecord? = SignalDatabase.groups.getGroup(groupId).getOrNull()

      if (groupRecord != null && !groupRecord.isActive) {
        throw UndeliverableMessageException("Cannot unpin messages in an inactive group!")
      }

      if (groupRecord != null && groupRecord.attributesAccessControl == GroupAccessControl.ONLY_ADMINS && !groupRecord.isAdmin(Recipient.self())) {
        throw UndeliverableMessageException("Non-admins cannot pin messages!")
      }

      GroupUtil.setDataMessageGroupContext(AppDependencies.application, builder, groupId)
    }

    val sentTime = System.currentTimeMillis()
    val message = builder
      .withTimestamp(sentTime)
      .withProfileKey(ProfileKeyUtil.getSelfProfileKey().serialize())
      .withUnpinnedMessage(
        SignalServiceDataMessage.UnpinnedMessage(
          targetAuthor = targetAuthor,
          targetSentTimestamp = targetSentTimestamp
        )
      )
      .build()

    return if (includeSelf) {
      listOf(AppDependencies.signalServiceMessageSender.sendSyncMessage(message))
    } else {
      GroupSendUtil.sendResendableDataMessage(
        applicationContext,
        groupId,
        null,
        destinations,
        false,
        ContentHint.RESENDABLE,
        MessageId(relatedMessageId),
        message,
        false,
        false,
        null
      ) { System.currentTimeMillis() - sentTime > PIN_TERMINATE_TIMEOUT.inWholeMilliseconds }
    }
  }

  /**
   * Tellomi (ADR-0081 §4.3): a pin "for me only" goes to my own linked devices as a sent transcript carrying
   * `tellomi.pinForSelfOnly`, never to the other side. No group permission needed. Without linked devices there is nothing to send.
   */
  @Throws(IOException::class, UndeliverableMessageException::class)
  fun sendSelfOnlyPin(threadRecipient: Recipient, targetAuthor: ServiceId, targetSentTimestamp: Long, pinDurationInSeconds: Long, sentTimestamp: Long): SendMessageResult {
    val pin = SignalServiceDataMessage.PinnedMessage(
      targetAuthor = targetAuthor,
      targetSentTimestamp = targetSentTimestamp,
      pinDurationInSeconds = pinDurationInSeconds.takeIf { it != MessageTable.PIN_FOREVER }?.toInt(),
      forever = (pinDurationInSeconds == MessageTable.PIN_FOREVER).takeIf { it }
    )
    return sendSelfOnlyTranscript(threadRecipient, selfOnlyBuilder(threadRecipient, sentTimestamp).withPinnedMessage(pin))
  }

  /** Tellomi (ADR-0081 §4.3): taking a "for me only" pin back, also only to my own devices. */
  @Throws(IOException::class, UndeliverableMessageException::class)
  fun sendSelfOnlyUnpin(threadRecipient: Recipient, targetAuthor: ServiceId, targetSentTimestamp: Long, sentTimestamp: Long): SendMessageResult {
    val unpin = SignalServiceDataMessage.UnpinnedMessage(targetAuthor = targetAuthor, targetSentTimestamp = targetSentTimestamp)
    return sendSelfOnlyTranscript(threadRecipient, selfOnlyBuilder(threadRecipient, sentTimestamp).withUnpinnedMessage(unpin))
  }

  private fun selfOnlyBuilder(threadRecipient: Recipient, sentTimestamp: Long): SignalServiceDataMessage.Builder {
    val builder = newBuilder()
    if (threadRecipient.isPushV2Group) {
      GroupUtil.setDataMessageGroupContext(AppDependencies.application, builder, threadRecipient.requireGroupId().requireV2())
    }
    // The chat's own timer, so an older linked device that reads this as an ordinary pin does not change it.
    return builder
      .withTimestamp(sentTimestamp)
      .withExpiration(threadRecipient.expiresInSeconds)
      .withTellomiPinForSelfOnly(true)
  }

  private fun sendSelfOnlyTranscript(threadRecipient: Recipient, builder: SignalServiceDataMessage.Builder): SendMessageResult {
    val destination = if (threadRecipient.isPushV2Group) {
      Optional.empty()
    } else {
      Optional.of(RecipientUtil.toSignalServiceAddress(AppDependencies.application, threadRecipient))
    }
    return AppDependencies.signalServiceMessageSender.sendTellomiSelfOnlyTranscript(destination, builder.build())
  }
}
