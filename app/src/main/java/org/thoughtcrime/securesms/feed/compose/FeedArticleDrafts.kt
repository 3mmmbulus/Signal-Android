/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import kotlinx.serialization.Serializable
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.keyvalue.SignalStore

/** 没写完的长文：标题和正文。 */
@Serializable
data class FeedSavedArticle(val title: String = "", val text: String = "")

/**
 * 长文自动保存（`dynamic-feed.md` 第三节第 25 条）。只存一篇、只在本机：关掉发布页再打开，接着写；发出去就清掉。
 * 编辑已发的、引用别人的不存（那两种关页面照旧问「放弃吗」）。
 */
interface FeedArticleDrafts {
  fun load(): FeedSavedArticle?
  fun save(article: FeedSavedArticle)
  fun clear()
}

class InMemoryFeedArticleDrafts(private var saved: FeedSavedArticle? = null) : FeedArticleDrafts {
  override fun load(): FeedSavedArticle? = saved
  override fun save(article: FeedSavedArticle) {
    saved = article
  }
  override fun clear() {
    saved = null
  }
}

object StoredFeedArticleDrafts : FeedArticleDrafts {
  private val TAG = Log.tag(StoredFeedArticleDrafts::class)

  override fun load(): FeedSavedArticle? {
    val text = SignalStore.tellomiFeed.articleDraft ?: return null
    return try {
      FeedJson.decodeFromString(FeedSavedArticle.serializer(), text)
    } catch (e: Exception) {
      Log.w(TAG, "bad article draft", e)
      null
    }
  }

  override fun save(article: FeedSavedArticle) {
    SignalStore.tellomiFeed.articleDraft = FeedJson.encodeToString(FeedSavedArticle.serializer(), article)
  }

  override fun clear() {
    SignalStore.tellomiFeed.articleDraft = null
  }
}
