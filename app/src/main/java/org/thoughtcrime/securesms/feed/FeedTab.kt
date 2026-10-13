/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

/**
 * 动态页的两个分栏（`dynamic-feed.md` 第三节第 18 条：只有「推荐 / 关注」）。顺序就是空间顺序：
 * 推荐在左、关注在右，左右滑切换（`10-feed-prototype-log.md` 第 435 行）。
 *
 * [apiName] 是内容引擎的路由名：`GET /v1/feed/recommend`、`GET /v1/feed/following`（`docs/adr/0071/client-api-2026-10.md` 4.1）。
 */
enum class FeedTab(
  val apiName: String,
  @get:StringRes val label: Int,
  @get:StringRes val emptyTitle: Int,
  @get:StringRes val emptyBody: Int
) {
  RECOMMEND(
    apiName = "recommend",
    label = R.string.TellomiFeed__tab_recommend,
    emptyTitle = R.string.TellomiFeed__recommend_empty_title,
    emptyBody = R.string.TellomiFeed__recommend_empty_body
  ),
  FOLLOWING(
    apiName = "following",
    label = R.string.TellomiFeed__tab_following,
    emptyTitle = R.string.TellomiFeed__following_empty_title,
    emptyBody = R.string.TellomiFeed__following_empty_body
  );

  companion object {
    /**
     * 服务端 `config.tabs` 给的分栏（契约第三节），按服务端的顺序；不认识的名字跳过。
     * 一个都不认识时退回两栏都显示，免得页面空着。
     */
    @JvmStatic
    fun fromApiNames(names: List<String>): List<FeedTab> {
      val tabs = names.mapNotNull { name -> entries.firstOrNull { it.apiName == name } }.distinct()
      return tabs.ifEmpty { entries.toList() }
    }
  }
}
