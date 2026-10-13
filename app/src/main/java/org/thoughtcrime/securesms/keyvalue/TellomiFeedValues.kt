/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

/**
 * Tellomi 动态（M11）：内容引擎上一次说的「能不能看」（`GET /v1/feed/status`，契约 3.1），和内部用户的样例数据开关。
 *
 * 只存在本机，不进备份：内测名单和紧急开关随时会变，换设备应该重新问。
 */
class TellomiFeedValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val ENGINE_VISIBLE = "tellomi_feed.engine_visible"
    private const val ENGINE_CHECKED_AT = "tellomi_feed.engine_checked_at"
    private const val USE_SAMPLE_DATA = "tellomi_feed.use_sample_data"
    private const val SEARCH_QUERIES = "tellomi_feed.search_queries"
    private const val SEARCH_PEOPLE = "tellomi_feed.search_people"
    private const val FRIENDS_REPORTED = "tellomi_feed.friends_reported"
    private const val FRIENDS_CHECKED_AT = "tellomi_feed.friends_checked_at"
    private const val BLOCKED_KEYWORDS = "tellomi_feed.blocked_keywords"
    private const val ARTICLE_DRAFT = "tellomi_feed.article_draft"
    private const val MINOR_USAGE_DAY = "tellomi_feed.minor_usage_day"
    private const val MINOR_USED_MILLIS = "tellomi_feed.minor_used_millis"
    private const val MINOR_MODE = "tellomi_feed.minor_mode"
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  /** 引擎上一次回的 `visible`。没问过是 false：入口要等引擎说了能看才出现。 */
  var engineVisible: Boolean by booleanValue(ENGINE_VISIBLE, false)

  /** 上一次问到结果的时间（毫秒）；0 = 没问过。 */
  var engineCheckedAt: Long by longValue(ENGINE_CHECKED_AT, 0)

  /** 内部用户强制打开动态标签时，用本机样例（true）还是连内容引擎（false）。 */
  var useSampleData: Boolean by booleanValue(USE_SAMPLE_DATA, true)

  /** 最近搜索（JSON 字符串数组，新的在前）。只在本机（第三节第 34 条：搜索历史只存在手机上）。 */
  var searchQueries: String? by stringValue(SEARCH_QUERIES, null)

  /** 搜索里最近点开过的人（JSON），同样只在本机。 */
  var searchPeople: String? by stringValue(SEARCH_PEOPLE, null)

  /** 报给内容引擎的好友 ACI（JSON，带引擎地址：切区后重新报）。见 `FeedFriendSync`。 */
  var friendsReported: String? by stringValue(FRIENDS_REPORTED, null)

  /** 上次对好友名单的时间（毫秒）；0 = 下次回到前台就对。 */
  var friendsCheckedAt: Long by longValue(FRIENDS_CHECKED_AT, 0)

  /** 「完全屏蔽」的关键词（JSON）。只在本机，不发给服务器（第三节第 12 条；引擎 `relations.go`）。见 `FeedBlockedKeywords`。 */
  var blockedKeywords: String? by stringValue(BLOCKED_KEYWORDS, null)

  /** 没写完的长文（JSON：标题和正文），只在本机。见 `FeedArticleDrafts`。 */
  var articleDraft: String? by stringValue(ARTICLE_DRAFT, null)

  /** 未成年人模式：今天是哪天（ISO 日期）和今天在动态上用了多少毫秒。见 `FeedMinorUsage`。 */
  var minorUsageDay: String? by stringValue(MINOR_USAGE_DAY, null)
  var minorUsedMillis: Long by longValue(MINOR_USED_MILLIS, 0)

  /** 引擎上一次 `config` 说的是不是未成年人模式：动态里单独打开的页面（详情、主页……）也按它锁时间。 */
  var minorMode: Boolean by booleanValue(MINOR_MODE, false)
}
