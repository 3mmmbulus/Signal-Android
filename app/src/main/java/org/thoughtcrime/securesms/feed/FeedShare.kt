/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.mediasend.MediaSendLauncher
import org.thoughtcrime.securesms.sharing.v2.ShareActivity
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.util.TellomiLinks

/**
 * 卡片「分享」里的「发到聊天」「分享到快拍」「复制链接」「分享到其他 App」（`dynamic-feed.md` 第三节第 26 条）。只在 [FeedCard.shareable] 时出现：
 * 私密账号和非公开动态没有外链（第 29 条）。链接里只有 id，打开的人照样要过 Card API 的可见性检查。
 */
/**
 * 分享结束的回调（I-13 `share` 事件）。默认什么都不做；详情页用 `CompositionLocalProvider` 换成自己的 ViewModel，
 * 所以只有详情页里的分享会报 `share`（和 iOS 一样，列表卡片上的分享不报）。
 *
 * 算 [FeedShareOutcome.COMPLETED] 的：「复制链接」，和系统分享面板里**选了目标 App**（[FeedShareChooser]，两端口径统一为
 * 「选了分享目标」，产品经理 2026-10-20 定）。发到聊天、分享到快拍拿不到结果，一律不报。
 */
val LocalFeedShareListener = staticCompositionLocalOf<(FeedCard, FeedShareOutcome) -> Unit> { { _, _ -> } }

object FeedShare {

  fun link(card: FeedCard): String = TellomiLinks.postLink(card.id)

  fun copyLink(context: Context, card: FeedCard) {
    val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.TellomiFeed__copy_link), link(card)))
    Toast.makeText(context, R.string.TellomiFeed__link_copied, Toast.LENGTH_SHORT).show()
  }

  /**
   * 发到聊天：Signal 自己的转发页（最近聊天多选、可附言），端到端加密发出。
   * 正文和「我的收藏」里那条一样：作者、开头一段、链接。
   */
  fun sendToChats(context: Context, card: FeedCard) {
    context.startActivity(ShareActivity.sendSimpleText(context, card.savedMessageBody(link(card))))
  }

  /**
   * 分享到快拍：打开 Signal 的文字快拍编辑页，草稿是同一段「作者：开头一段 + 链接」，发给「我的快拍」（可在编辑页改范围）。
   * 公开快拍走内容引擎（第 14 条），那条路还没有，这里只发 Signal 现有的好友范围快拍，端到端加密。
   * 快拍功能被关了就提示去设置里打开。
   */
  fun shareToStory(context: Context, card: FeedCard) {
    val draft = card.savedMessageBody(link(card))
    SignalExecutors.BOUNDED.execute {
      val myStory = if (Stories.isFeatureEnabled()) SignalDatabase.distributionLists.getRecipientId(DistributionListId.MY_STORY) else null
      ThreadUtil.runOnMain {
        if (myStory == null) {
          Toast.makeText(context, R.string.TellomiFeed__stories_off, Toast.LENGTH_LONG).show()
        } else {
          context.startActivity(
            MediaSendLauncher.share(context, emptyList(), listOf(ContactSearchKey.RecipientSearchKey(myStory, true)), draft, asTextStory = true)
          )
        }
      }
    }
  }

  /**
   * 系统分享面板。[onFinished] 不为 null 时带一个 `IntentSender`：用户选了目标 App 就回调 [FeedShareOutcome.COMPLETED]
   * （见 [FeedShareChooser]）；没选就关掉不回调。
   */
  @JvmOverloads
  fun shareToApps(context: Context, card: FeedCard, onFinished: ((FeedShareOutcome) -> Unit)? = null) {
    val send = Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, link(card))
    val title = context.getString(R.string.TellomiFeed__share_other_apps)
    val chooser = if (onFinished == null) {
      Intent.createChooser(send, title)
    } else {
      val token = FeedShareChooser.register(onFinished)
      val callback = Intent(context, FeedShareChosenReceiver::class.java)
        .setAction(FeedShareChooser.ACTION)
        .putExtra(FeedShareChooser.EXTRA_TOKEN, token)
      // 系统要往里填 EXTRA_CHOSEN_COMPONENT，所以必须可变；目标是显式的、不导出的 receiver
      val pendingIntent = PendingIntent.getBroadcast(context, token.hashCode(), callback, PendingIntentFlags.mutable() or PendingIntentFlags.updateCurrent())
      Intent.createChooser(send, title, pendingIntent.intentSender)
    }
    context.startActivity(chooser)
  }
}
