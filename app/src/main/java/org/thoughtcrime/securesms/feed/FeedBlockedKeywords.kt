/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.keyvalue.SignalStore

/** 一个「完全屏蔽」的关键词；[expiresAt] 毫秒，null = 永久。 */
@Serializable
data class FeedLocalKeyword(val keyword: String, val expiresAt: Long? = null)

/**
 * 「完全屏蔽」的关键词（第三节第 12 条）：只存在手机上，不告诉服务器（引擎 `relations.go`：「完全屏蔽」的词不离开手机），
 * 信息流里命中的卡片直接不显示。只作用于信息流（推荐、关注），搜索、主页、详情照常。
 */
interface FeedBlockedKeywords {
  /** 还没过期的词。 */
  fun active(now: Long = System.currentTimeMillis()): List<FeedLocalKeyword>
  fun add(keyword: String, expiresAt: Long?)
  fun remove(keyword: String)
}

open class InMemoryFeedBlockedKeywords : FeedBlockedKeywords {
  protected var list: List<FeedLocalKeyword> = emptyList()

  override fun active(now: Long): List<FeedLocalKeyword> = list.filter { it.expiresAt == null || it.expiresAt > now }

  override fun add(keyword: String, expiresAt: Long?) {
    val k = keyword.trim()
    if (k.isEmpty()) return
    list = listOf(FeedLocalKeyword(k, expiresAt)) + list.filterNot { it.keyword.equals(k, ignoreCase = true) }
    onChanged()
  }

  override fun remove(keyword: String) {
    list = list.filterNot { it.keyword == keyword }
    onChanged()
  }

  protected open fun onChanged() = Unit
}

/** 存在 [SignalStore.tellomiFeed]，第一次用到时才读。过期的在下次改动时顺手清掉。 */
class StoredFeedBlockedKeywords : InMemoryFeedBlockedKeywords() {

  companion object {
    private val TAG = Log.tag(StoredFeedBlockedKeywords::class)
    private val SERIALIZER = ListSerializer(FeedLocalKeyword.serializer())
  }

  private var loaded = false

  private fun ensureLoaded() {
    if (loaded) return
    loaded = true
    val text = SignalStore.tellomiFeed.blockedKeywords
    list = if (text.isNullOrEmpty()) {
      emptyList()
    } else {
      try {
        FeedJson.decodeFromString(SERIALIZER, text)
      } catch (e: Exception) {
        Log.w(TAG, "bad blocked keywords", e)
        emptyList()
      }
    }
  }

  override fun active(now: Long): List<FeedLocalKeyword> {
    ensureLoaded()
    return super.active(now)
  }

  override fun add(keyword: String, expiresAt: Long?) {
    ensureLoaded()
    super.add(keyword, expiresAt)
  }

  override fun remove(keyword: String) {
    ensureLoaded()
    super.remove(keyword)
  }

  override fun onChanged() {
    val keep = super.active(System.currentTimeMillis())
    SignalStore.tellomiFeed.blockedKeywords = if (keep.isEmpty()) null else FeedJson.encodeToString(SERIALIZER, keep)
  }
}

/** 标题或正文里出现了任何一个词（不分大小写）。 */
fun FeedCard.matchesAny(keywords: List<FeedLocalKeyword>): Boolean {
  if (keywords.isEmpty()) return false
  return keywords.any { k -> title?.contains(k.keyword, ignoreCase = true) == true || text?.contains(k.keyword, ignoreCase = true) == true }
}
