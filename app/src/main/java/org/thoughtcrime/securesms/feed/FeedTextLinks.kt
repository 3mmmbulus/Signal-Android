/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.signal.core.util.Linkifier

/**
 * 动态正文、评论、引用框里的网址（和 iOS FeedTextLinks 同一套规则）：
 * 用聊天同一个 [Linkifier] 找，只认 http / https；没写协议的补 `https://`；邮箱、电话不变成链接。
 */
object FeedTextLinks {

  data class Link(val start: Int, val end: Int, val url: String)

  @JvmStatic
  fun find(text: CharSequence?): List<Link> {
    if (text.isNullOrEmpty()) return emptyList()
    return Linkifier.findLinks(text).mapNotNull { detected ->
      val raw = text.subSequence(detected.start, detected.end).toString()
      val url = normalize(raw) ?: return@mapNotNull null
      Link(detected.start, detected.end, url)
    }
  }

  /** 有协议的只放过 http / https；没协议的补 https://。别的（mailto、tel、ftp……）返回 null。 */
  @JvmStatic
  fun normalize(raw: String): String? {
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd < 0) {
      if (raw.contains('@')) return null
      return "https://$raw"
    }
    val scheme = raw.substring(0, schemeEnd).lowercase()
    return if (scheme == "http" || scheme == "https") raw else null
  }

  /** [offset] 落在哪条链接里（点按命中）；不在任何链接上是 null。 */
  @JvmStatic
  fun at(links: List<Link>, offset: Int): Link? = links.firstOrNull { offset >= it.start && offset < it.end }
}
