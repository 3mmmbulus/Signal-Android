/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 入口能力检查：`GET /v1/capabilities` → `{"version":1,"features":["friend_assertions", …]}`。
 *
 * 为什么要有：好友新增遇到 404 分不清是「对方没有账号」还是「这条路由还没部署」。引擎只在路由真的在了才把
 * `friend_assertions` 列进 `features`，客户端先检查通过再开好友入口、再跑 `FeedFriendSync`。
 * 接口在内容引擎上、不需要登录，回包 `Cache-Control: max-age=60`。注意它**不覆盖** Signal-Server 的 `/v1/tellomi/feed/` 下面那几条
 * （`feed/auth`、`friend-assertions`、`audience-lists`），那几条是否部署仍要另外判断（见 [NotDeployedBackoffTokenSource]）。
 *
 * 规则：**只有一个完整、读得懂的 200 才算数**；404、其他状态码、没有回包、超时、读不懂（不是 JSON、不是对象、`version` 不是整数 1、
 * `features` 不是数组）一律等于「没有任何能力」（[DISABLED]）。`features` 里不是字符串的元素忽略，名字区分大小写、原样比较。
 */
data class FeedCapabilities(val features: Set<String>) {

  fun supports(feature: String): Boolean = feature in features

  /** 好友入口和 `FeedFriendSync` 的开关。 */
  val friendAssertions: Boolean
    get() = supports(FRIEND_ASSERTIONS)

  companion object {
    const val PATH = "/v1/capabilities"

    // 引擎 `/v1/capabilities` 现在会列的名字（content-engine bot/engine-capabilities d0a5a01）。没有 friend_sync / friend_report。
    const val FEED = "feed"
    const val EVENTS = "events"
    const val POSTING = "posting"
    const val AUDIENCE_TOKENS = "audience_tokens"
    const val COMMENTS = "comments"
    const val REACTIONS = "reactions"
    const val POLLS = "polls"
    const val FRIEND_ASSERTIONS = "friend_assertions"
    const val FOLLOW = "follow"
    const val BLOCKS = "blocks"
    const val PROFILES = "profiles"
    const val SEARCH = "search"
    const val TOPICS = "topics"
    const val NOTIFICATIONS = "notifications"
    const val REPORTS = "reports"
    const val VOICE_COMMENTS = "voice_comments"
    const val MEDIA_UPLOAD = "media_upload"
    const val MEDIA_URL = "media_url"

    /** 认得的协议版本；以后换了回包形状会升版本号，旧客户端宁可当作没有也不去猜。 */
    const val SUPPORTED_VERSION = 1

    /** 回包超过这么多字符不看（正常只有几十个字节）。 */
    const val MAX_BODY_CHARS = 64 * 1024

    val DISABLED = FeedCapabilities(emptySet())

    private val json = Json

    /** 读不懂返回 null；调用方要「没有能力」用 [parse]。 */
    @JvmStatic
    fun parseOrNull(status: Int, body: String?): FeedCapabilities? {
      if (status != 200 || body == null || body.isBlank() || body.length > MAX_BODY_CHARS) return null
      val root = try {
        json.parseToJsonElement(body)
      } catch (e: IllegalArgumentException) {
        // kotlinx 的解析错误（SerializationException）是 IllegalArgumentException 的子类
        return null
      }
      if (root !is JsonObject) return null

      val version = root["version"] as? JsonPrimitive ?: return null
      // 严格等于整数 1：按原文比（1.0、1e0、01 数值上等于 1 也不算），和 iOS 一致
      if (version.isString || version.content != SUPPORTED_VERSION.toString()) return null

      val list = root["features"] as? JsonArray ?: return null
      val features = list.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
      }
      return FeedCapabilities(features.toSet())
    }

    @JvmStatic
    fun parse(status: Int, body: String?): FeedCapabilities = parseOrNull(status, body) ?: DISABLED
  }
}

/** 一次 `GET /v1/capabilities` 的原始结果；没有回包（断网等）由 fetch 抛异常表示。 */
class FeedCapabilitiesResponse(val status: Int, val body: String?)

/**
 * 记住最近一次能力检查的结果，并按时间重新问。[fetch] 做真正的网络请求（带不带账号鉴权看这个接口放在哪一边），
 * 抛任何异常都按「没有能力」处理；整个请求超过 [timeoutMillis] 也一样。
 *
 * 用法：回到前台先 `refreshIfStale()`，再看 [isEnabled]（不走网络、不挂起，可以直接给 `FeedFriendSync(enabled = …)` 用）。
 * 成功和失败的结果都只保留 60 秒（和引擎回包的 `max-age=60` 对齐：临时断网不要把入口藏很久，也不要每次回前台都打）。两个时长分开，调用方可以调。
 * 时钟往回拨过（现在早于记录的时刻）当作过期。
 */
class FeedCapabilityChecker(
  private val fetch: suspend () -> FeedCapabilitiesResponse,
  private val clock: () -> Long = System::currentTimeMillis,
  private val timeoutMillis: Long = TIMEOUT_MILLIS,
  private val okTtlMillis: Long = OK_TTL_MILLIS,
  private val failTtlMillis: Long = FAIL_TTL_MILLIS
) {
  companion object {
    const val TIMEOUT_MILLIS = 5_000L
    const val OK_TTL_MILLIS = 60 * 1000L
    const val FAIL_TTL_MILLIS = 60 * 1000L
  }

  private val mutex = Mutex()

  @Volatile
  private var current: FeedCapabilities = FeedCapabilities.DISABLED
  private var checkedAt = 0L
  private var lastOk = false

  /** 最近一次检查的结果；没检查过就是 [FeedCapabilities.DISABLED]。 */
  fun capabilities(): FeedCapabilities = current

  fun isEnabled(feature: String): Boolean = current.supports(feature)

  /** 过期了（或从没问过）才问；返回现在的能力。并发的调用只会发一次请求。会走网络，别在主线程调。 */
  suspend fun refreshIfStale(): FeedCapabilities {
    return mutex.withLock {
      val now = clock()
      val ttl = if (lastOk) okTtlMillis else failTtlMillis
      if (checkedAt != 0L && now - checkedAt in 0 until ttl) {
        return@withLock current
      }
      val parsed = try {
        withTimeoutOrNull(timeoutMillis) { fetch() }?.let { FeedCapabilities.parseOrNull(it.status, it.body) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      current = parsed ?: FeedCapabilities.DISABLED
      lastOk = parsed != null
      checkedAt = now
      current
    }
  }

  /** 退出登录、切区时调：忘掉结果，下次重新问。 */
  suspend fun clear() {
    mutex.withLock {
      current = FeedCapabilities.DISABLED
      checkedAt = 0L
      lastOk = false
    }
  }
}
