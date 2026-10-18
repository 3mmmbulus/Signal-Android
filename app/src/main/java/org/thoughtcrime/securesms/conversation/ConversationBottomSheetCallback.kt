package org.thoughtcrime.securesms.conversation

import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.linkpreview.LinkPreview

/**
 * Callback interface for bottom sheets that show conversation data in a conversation and
 * want to manipulate the conversation view.
 */
interface ConversationBottomSheetCallback {
  fun getConversationAdapterListener(): ConversationAdapter.ItemClickListener
  fun jumpToMessage(messageRecord: MessageRecord)
  fun unpin(conversationMessage: ConversationMessage)
  fun copy(conversationMessage: ConversationMessage)
  fun delete(conversationMessage: ConversationMessage)
  fun save(conversationMessage: ConversationMessage)

  /** Tellomi（tellomi/tellomi#1611，M-4）：定时消息页能不能写新的 / 改（会话输入框能用的时候才能）。 */
  fun tellomiCanWriteScheduledMessages(): Boolean = false

  /** Tellomi（tellomi/tellomi#1611，M-4）：定时消息页上写好、选好时间的一条，按会话页排定时那条路排。 */
  fun tellomiScheduleText(body: String, mentions: List<Mention>, bodyRanges: BodyRangeList?, linkPreviews: List<LinkPreview>, scheduledTime: Long) = Unit

  /** Tellomi（pinned-messages.md 3.5）：置顶列表底下的「取消所有置顶」——不问，会话页给 5 秒撤销后才发出。 */
  fun tellomiUnpinAll(messageIds: List<Long>) = Unit

  /** Tellomi（pinned-messages.md 3.5）：没有权限时的「隐藏置顶消息」——只在本机藏起置顶栏，直到有新的置顶，可撤销。 */
  fun tellomiHidePinnedMessages() = Unit
}
