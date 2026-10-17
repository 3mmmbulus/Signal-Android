/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2.items

import android.app.Application
import android.graphics.Path
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.util.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contactshare.Contact
import org.thoughtcrime.securesms.conversation.ConversationItemDisplayMode
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.Quote
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stickers.StickerLocator
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Tellomi：气泡的小尾巴（tellomi/tellomi#1206；规范 #1204 第 1、2 节）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiBubbleTailTest {

  private val standard = ConversationItemDisplayMode.Standard

  @Test
  fun `only the last message of a group, or a message on its own, gets a tail`() {
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = true, displayMode = standard)).isTrue()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = false, hasReactions = false, hasBubble = true, displayMode = standard)).isFalse()
  }

  @Test
  fun `no tail under a reaction, without a bubble, or outside the conversation itself`() {
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = true, hasBubble = true, displayMode = standard)).isFalse()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = false, displayMode = standard)).isFalse()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = true, displayMode = ConversationItemDisplayMode.EditHistory)).isFalse()
    assertThat(TellomiBubbleTail.shouldDraw(isEndOfCluster = true, hasReactions = false, hasBubble = true, displayMode = ConversationItemDisplayMode.Detailed)).isFalse()
  }

  @Test
  fun `my tail sticks out 6 to the right of the bottom corner, 14 high`() {
    val bounds = outlineBounds(towardsRight = true)

    assertThat(bounds.top).isCloseTo(200f - 14f * DENSITY, 0.01f)
    assertThat(bounds.bottom).isCloseTo(200f, 0.01f)
    assertThat(bounds.left).isCloseTo(100f - 4f * DENSITY, 0.01f)
    // computeBounds 按控制点算（新版 Android 忽略 exact），尖端最远的控制点在 6.3；曲线本身到 6.08。
    assertThat(bounds.right - 100f).isCloseTo(6.3f * DENSITY, 0.01f)
  }

  @Test
  fun `their tail is the mirror image, to the left`() {
    val bounds = outlineBounds(towardsRight = false)

    assertThat(bounds.right).isCloseTo(100f + 4f * DENSITY, 0.01f)
    assertThat(100f - bounds.left).isCloseTo(6.3f * DENSITY, 0.01f)
    assertThat(bounds.top).isCloseTo(200f - 14f * DENSITY, 0.01f)
  }

  @Test
  fun `the corner under the tail becomes the small one, on the sender's side`() {
    val big = 18f.dp
    val small = 4f.dp

    val mine = V2ConversationItemShape(mockk(relaxed = true))
    mine.applyTellomiTail(isOutgoing = true)
    assertThat(mine.cornersLTR.bottomRight).isEqualTo(small)
    assertThat(mine.cornersLTR.bottomLeft).isEqualTo(big)
    assertThat(mine.cornersLTR.topRight).isEqualTo(big)
    assertThat(mine.cornersRTL.bottomLeft).isEqualTo(small)

    val theirs = V2ConversationItemShape(mockk(relaxed = true))
    theirs.applyTellomiTail(isOutgoing = false)
    assertThat(theirs.cornersLTR.bottomLeft).isEqualTo(small)
    assertThat(theirs.cornersLTR.bottomRight).isEqualTo(big)
  }

  @Test
  fun `groups are 12 apart in both renderers`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val density = context.resources.displayMetrics.density
    val legacy = context.resources.getDimensionPixelOffset(R.dimen.conversation_vertical_message_spacing_default)

    assertThat(legacy * 2f).isCloseTo(12f * density, 0.5f)
    assertThat(V2ConversationItemShape.MessageShape.SINGLE.topPadding + V2ConversationItemShape.MessageShape.SINGLE.bottomPadding).isCloseTo(12f.dp, 0.5f)
  }

  /** 「正在输入」的卡片四角都是 18：尾巴轮廓往气泡里多盖一块 18 的方块，扣掉气泡后正好补满圆角的缺口。 */
  @Test
  fun `a tail on a card with big corners also covers that corner`() {
    val bounds = outlineBounds(towardsRight = false, coverRadiusDp = 18f)

    // 尾巴照旧伸出 6.3、只有 14 高的部分在气泡外；往气泡里盖到 18 × 18
    assertThat(100f - bounds.left).isCloseTo(6.3f * DENSITY, 0.01f)
    assertThat(bounds.right).isCloseTo(100f + 18f * DENSITY, 0.01f)
    assertThat(bounds.top).isCloseTo(200f - 18f * DENSITY, 0.01f)
    assertThat(bounds.bottom).isCloseTo(200f, 0.01f)
  }

  @Test
  fun `the typing indicator reports its tail to the same decoration`() {
    assertThat(TellomiBubbleTail.Provider::class.java.isAssignableFrom(org.thoughtcrime.securesms.conversation.v2.ConversationTypingIndicatorAdapter.ViewHolder::class.java)).isTrue()
  }

  @Test
  fun `voice notes, files and view-once messages without text still have a bubble, captionless photos do not`() {
    val context = ApplicationProvider.getApplicationContext<Application>()

    // 都带一个附件：纯文字消息会走大号表情的判断，要装表情库（单测里没装，会一直等）。已删除 = 记着谁删的（deletedBy）
    fun mms(contentType: String, body: String = "", hasThumbnail: Boolean = false, voiceNote: Boolean = false, viewOnce: Boolean = false, deleted: Boolean = false): MmsMessageRecord {
      val attachment = FakeMessageRecords.buildDatabaseAttachment(contentType = contentType, hasThumbnail = hasThumbnail, voiceNote = voiceNote)
      return FakeMessageRecords.buildMediaMmsMessageRecord(
        body = body,
        viewOnce = viewOnce,
        deletedBy = if (deleted) RecipientId.from(1) else null,
        slideDeck = SlideDeck(attachment)
      )
    }

    // 旧版渲染里这几种都有底色：组尾要带尾巴
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.AUDIO_AAC, voiceNote = true), context), "voice note").isTrue()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms("application/pdf"), context), "file").isTrue()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.IMAGE_JPEG, hasThumbnail = true, viewOnce = true), context), "view-once photo").isTrue()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.IMAGE_JPEG, body = "看这个", hasThumbnail = true), context), "photo with a caption").isTrue()

    // 图铺满了气泡、没有露出来的底色：不画
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms(MediaUtil.IMAGE_JPEG, hasThumbnail = true), context), "captionless photo").isFalse()
    assertThat(TellomiBubbleTail.hasVisibleBubble(mms("application/pdf", deleted = true), context), "deleted").isFalse()
  }

  @Test
  fun `a sticker has no bubble unless it quotes something`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val stickerDeck = SlideDeck(FakeMessageRecords.buildDatabaseAttachment(contentType = MediaUtil.IMAGE_WEBP, stickerLocator = StickerLocator("pack", "key", 1, null)))
    val quote = Quote(1L, RecipientId.from(2), "原消息", false, SlideDeck(), emptyList(), QuoteModel.Type.NORMAL)

    // 单独的贴纸没有底色；带引用的贴纸，引用那块有底色
    assertThat(TellomiBubbleTail.hasVisibleBubble(FakeMessageRecords.buildMediaMmsMessageRecord(body = "", slideDeck = stickerDeck), context), "sticker").isFalse()
    assertThat(TellomiBubbleTail.hasVisibleBubble(FakeMessageRecords.buildMediaMmsMessageRecord(body = "", slideDeck = stickerDeck, quote = quote), context), "sticker quoting a message").isTrue()
  }

  @Test
  fun `a contact card without text keeps its bubble`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val contact = Contact(Contact.Name("小", "林", null, null, null, null), null, emptyList(), emptyList(), emptyList(), null)

    // 联系人名片（没有附件、没有文字）是一张有底色的卡片
    assertThat(TellomiBubbleTail.hasVisibleBubble(FakeMessageRecords.buildMediaMmsMessageRecord(body = "", contacts = listOf(contact)), context), "contact card").isTrue()
  }

  private fun outlineBounds(towardsRight: Boolean, coverRadiusDp: Float = TellomiBubbleTail.DEFAULT_COVER_RADIUS_DP): RectF {
    val path = Path()
    TellomiBubbleTail.addOutline(path, cornerX = 100f, bottomY = 200f, towardsRight = towardsRight, density = DENSITY, coverRadiusDp = coverRadiusDp)
    val bounds = RectF()
    @Suppress("DEPRECATION")
    path.computeBounds(bounds, true)
    return bounds
  }

  companion object {
    private const val DENSITY = 2f
  }

  /** 尾巴交接（规范 #1204 第 3 节）：同一条消息没了尾巴（新消息接到这一组下面），尾巴 150 ms ease-out 淡出。 */
  @Test
  fun `the tail of the message a new one joins fades out over 150 ms`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val start = TellomiBubbleTail.handoverStart(messageId = 7, hasTail = false, previousMessageId = 7, previousHadTail = true, fadeStart = 0, context = context, now = 1000)
    assertThat(start).isEqualTo(1000L)
    assertThat(TellomiBubbleTail.handoverAlpha(start, now = 1000)).isEqualTo(1f)
    val halfway = TellomiBubbleTail.handoverAlpha(start, now = 1075)
    assertThat(halfway > 0f && halfway < 0.5f).isTrue() // ease-out: more than half gone at half time
    assertThat(TellomiBubbleTail.handoverAlpha(start, now = 1150)).isEqualTo(0f)
    assertThat(TellomiBubbleTail.handoverAlpha(0, now = 1000)).isEqualTo(0f)
  }

  @Test
  fun `no fade when the row now shows another message or the tail comes back, and a running fade keeps going`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    assertThat(TellomiBubbleTail.handoverStart(8, hasTail = false, previousMessageId = 7, previousHadTail = true, fadeStart = 0, context = context, now = 1000)).isEqualTo(0L)
    assertThat(TellomiBubbleTail.handoverStart(7, hasTail = true, previousMessageId = 7, previousHadTail = false, fadeStart = 900, context = context, now = 1000)).isEqualTo(0L)
    assertThat(TellomiBubbleTail.handoverStart(7, hasTail = false, previousMessageId = 7, previousHadTail = false, fadeStart = 900, context = context, now = 1000)).isEqualTo(900L)
    assertThat(TellomiBubbleTail.handoverStart(7, hasTail = false, previousMessageId = -1, previousHadTail = false, fadeStart = 0, context = context, now = 1000)).isEqualTo(0L)
  }
}
