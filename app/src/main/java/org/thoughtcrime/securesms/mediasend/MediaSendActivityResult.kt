package org.thoughtcrime.securesms.mediasend

import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.signal.core.models.media.Media
import org.signal.core.util.ParcelUtil
import org.signal.core.util.getParcelableExtraCompat
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender.PreUploadResult

/**
 * A class that lets us nicely format data that we'll send back to [ConversationActivity].
 */
@Parcelize
class MediaSendActivityResult(
  val recipientId: RecipientId,
  val preUploadResults: List<PreUploadResult> = emptyList(),
  val nonUploadedMedia: List<Media> = emptyList(),
  val body: String,
  val messageSendType: MessageSendType,
  val isViewOnce: Boolean,
  val mentions: List<Mention>,
  @TypeParceler<BodyRangeList?, BodyRangeListParceler>() val bodyRanges: BodyRangeList?,
  val storyType: StoryType,
  val scheduledTime: Long = -1,
  /**
   * Tellomi（tellomi/tellomi#1261 P-5「单独发送」）：会话页一张一条地发（说明挂最后一条），而不是整组一条相册消息。
   */
  val sendSeparately: Boolean = false,
  /**
   * Tellomi（tellomi/tellomi#1263 P-5「以文件发送」，ADR-0068）：[nonUploadedMedia] 是按文件发的原图（[TellomiMediaAsFiles]），
   * 会话页一个一条地发（说明挂最后一条）。
   */
  val sendAsFile: Boolean = false,
  /** Tellomi（ADR-0068 §4.7）：说明文字画在媒体上方（「···」→「说明文字上移」）。 */
  val captionAboveMedia: Boolean = false,
  /**
   * Tellomi（tellomi/tellomi#1714，ADR-0079）：[preUploadResults] 每一张的计时（同顺序）：[TellomiTimedSends.NONE] 没有，
   * [TellomiTimedSends.VIEW_ONCE] 一次性查看，3 / 10 / 30 是打开后看几秒。有计时的时候会话页一张一条地发（[TellomiTimedSends.parts]）。
   */
  val tellomiViewOnceChoices: List<Int> = emptyList()
) : Parcelable {

  val isPushPreUpload: Boolean
    get() = preUploadResults.isNotEmpty()

  init {
    require((preUploadResults.isNotEmpty() && nonUploadedMedia.isEmpty()) || (preUploadResults.isEmpty() && nonUploadedMedia.isNotEmpty()))
  }

  /** Tellomi（#1261、#1263）：同一份结果，改成一张一条地发 / 说明放在上方。 */
  fun withTellomiOptions(sendSeparately: Boolean, captionAboveMedia: Boolean): MediaSendActivityResult = MediaSendActivityResult(
    recipientId = recipientId,
    preUploadResults = preUploadResults,
    nonUploadedMedia = nonUploadedMedia,
    body = body,
    messageSendType = messageSendType,
    isViewOnce = isViewOnce,
    mentions = mentions,
    bodyRanges = bodyRanges,
    storyType = storyType,
    scheduledTime = scheduledTime,
    sendSeparately = sendSeparately,
    sendAsFile = sendAsFile,
    captionAboveMedia = captionAboveMedia,
    tellomiViewOnceChoices = tellomiViewOnceChoices
  )

  /** Tellomi（#1714）：有没有计时的（含一次性查看）。 */
  val hasTellomiTimers: Boolean
    get() = tellomiViewOnceChoices.any { it != TellomiTimedSends.NONE }

  companion object {
    const val EXTRA_RESULT = "result"

    @JvmStatic
    fun fromData(data: Intent): MediaSendActivityResult {
      return data.getParcelableExtraCompat(EXTRA_RESULT, MediaSendActivityResult::class.java) ?: throw IllegalArgumentException()
    }
  }
}

object BodyRangeListParceler : Parceler<BodyRangeList?> {
  override fun create(parcel: Parcel): BodyRangeList? {
    val data: ByteArray? = ParcelUtil.readByteArray(parcel)
    return if (data != null) {
      BodyRangeList.ADAPTER.decode(data)
    } else {
      null
    }
  }

  override fun BodyRangeList?.write(parcel: Parcel, flags: Int) {
    ParcelUtil.writeByteArray(parcel, this?.encode())
  }
}
