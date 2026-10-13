/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.sharing.v2.ShareActivity
import org.thoughtcrime.securesms.util.TellomiLinks

/**
 * 卡片「分享」里的「发到聊天」「复制链接」「分享到其他 App」（`dynamic-feed.md` 第三节第 26 条）。只在 [FeedCard.shareable] 时出现：
 * 私密账号和非公开动态没有外链（第 29 条）。链接里只有 id，打开的人照样要过 Card API 的可见性检查。
 */
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

  fun shareToApps(context: Context, card: FeedCard) {
    val send = Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, link(card))
    context.startActivity(Intent.createChooser(send, context.getString(R.string.TellomiFeed__share_other_apps)))
  }
}
