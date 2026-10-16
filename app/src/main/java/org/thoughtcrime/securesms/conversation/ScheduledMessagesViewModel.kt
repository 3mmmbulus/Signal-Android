package org.thoughtcrime.securesms.conversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.linkpreview.LinkPreview

class ScheduledMessagesViewModel @JvmOverloads constructor(
  private val threadId: Long,
  private val repository: ScheduledMessagesRepository = ScheduledMessagesRepository()
) : ViewModel() {

  fun getMessages(context: Context): Observable<List<ConversationMessage>> {
    return repository.getScheduledMessages(context, threadId)
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun rescheduleMessage(messageId: Long, scheduleTime: Long) {
    repository.rescheduleMessage(threadId, messageId, scheduleTime)
  }

  /**
   * Tellomi（tellomi/tellomi#1611）：定时消息页输入框里写好的一条。新写的先放在这里等选时间（选好了才排）；改的直接存。
   *
   * @param linkPreviews 改的时候 null = 预览不动。
   */
  data class TellomiDraft(
    /** 输入框里的原样（带 @、样式的 span）。 */
    val displayText: CharSequence,
    val body: String,
    val mentions: List<Mention>,
    val bodyRanges: BodyRangeList?,
    val linkPreviews: List<LinkPreview>?,
    val editMessageId: Long?
  )

  /** 新写的、正在选时间的那条（选时间取消了字还在输入框里，下次点发送重新取）。 */
  var tellomiPendingDraft: TellomiDraft? = null

  /** 正在改的那条（两端对齐清单 P-5：输入框就在整页底下，转屏后接着改）。 */
  var tellomiEditingMessage: ConversationMessage? = null

  /** 开始改一条之前输入框里写了一半的新消息，改完 / 不改了放回去（同会话输入框改消息）。 */
  var tellomiStashedText: CharSequence? = null

  /** 存一条改好的；已经发出去了（到点被认领）返回 false。 */
  fun tellomiSaveEdit(draft: TellomiDraft): Single<Boolean> {
    val messageId = draft.editMessageId ?: return Single.just(false)
    return Single.fromCallable {
      SignalDatabase.messages.tellomiEditScheduledMessage(threadId, messageId, draft.body, draft.bodyRanges, draft.mentions, draft.linkPreviews)
    }
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
  }

  companion object {
    private val TAG = Log.tag(ScheduledMessagesViewModel::class.java)
  }

  class Factory(private val threadId: Long) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(ScheduledMessagesViewModel(threadId)) as T
    }
  }
}
