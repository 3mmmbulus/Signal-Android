/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.feed.net.FeedCapabilities
import org.thoughtcrime.securesms.feed.net.FeedCapabilitiesResponse
import org.thoughtcrime.securesms.feed.net.FeedCapabilityChecker
import org.thoughtcrime.securesms.region.TellomiRegions

/**
 * 动态里哪些功能入口要画（契约 3.0 `GET /v1/capabilities`，产品经理 2026-10-20 定）：
 * `features` 里没有的功能，入口隐藏；**拿不到 capabilities（没问过、断网、超时、404、读不懂）一律按「没部署」处理，入口隐藏**。
 * 这件事要在首次提交商店审核之前做完：审核会因为点了没反应的入口被拒。
 *
 * 只管内容引擎的路由；Signal-Server 的 `/v1/tellomi/feed/` 那几条（取凭证、好友声明、名单）不在 capabilities 里，仍按 404 标记判断。
 *
 * 不在这里的入口（分享到聊天 / 快拍、复制链接、收藏到「我的收藏」）是纯本机或走 Signal 自己的通道，不看 capabilities。
 */
data class FeedEntries(
  /** 动态标签本身（列表、卡片、详情）。 */
  val feed: Boolean,
  /** 发布按钮、编辑、引用、转发。 */
  val posting: Boolean,
  /** 发帖页的加图。 */
  val mediaUpload: Boolean,
  /** 发帖页的投票、卡片上的投票。 */
  val polls: Boolean,
  /** 详情页的评论输入框（看评论不受影响）。 */
  val comments: Boolean,
  /** 评论框的话筒。 */
  val voiceComments: Boolean,
  /** 点赞。 */
  val reactions: Boolean,
  /** 「···」里的举报。 */
  val reports: Boolean,
  /** 「···」里的屏蔽作者。 */
  val blocks: Boolean,
  /** 顶栏搜索。 */
  val search: Boolean,
  /** 顶栏通知铃铛。 */
  val notifications: Boolean,
  /** 关注作者、关注话题。 */
  val follow: Boolean,
  /** 详情事件 / 曝光事件上报（不是入口，但没部署时别打）。 */
  val events: Boolean
) {
  companion object {
    @JvmField
    val NONE = FeedEntries(
      feed = false, posting = false, mediaUpload = false, polls = false, comments = false, voiceComments = false,
      reactions = false, reports = false, blocks = false, search = false, notifications = false, follow = false, events = false
    )

    /** 本机样例数据（不连引擎）和界面单测 / 预览用：全开。 */
    @JvmField
    val ALL = FeedEntries(
      feed = true, posting = true, mediaUpload = true, polls = true, comments = true, voiceComments = true,
      reactions = true, reports = true, blocks = true, search = true, notifications = true, follow = true, events = true
    )

    /** 语音评论要先能评论；投票、加图要先能发帖（加图的上传本身也要 `media_upload`）。 */
    @JvmStatic
    fun from(capabilities: FeedCapabilities): FeedEntries {
      fun has(name: String) = capabilities.supports(name)
      val feed = has(FeedCapabilities.FEED)
      val posting = feed && has(FeedCapabilities.POSTING)
      val comments = feed && has(FeedCapabilities.COMMENTS)
      return FeedEntries(
        feed = feed,
        posting = posting,
        mediaUpload = posting && has(FeedCapabilities.MEDIA_UPLOAD),
        polls = feed && has(FeedCapabilities.POLLS),
        comments = comments,
        voiceComments = comments && has(FeedCapabilities.VOICE_COMMENTS),
        reactions = feed && has(FeedCapabilities.REACTIONS),
        reports = feed && has(FeedCapabilities.REPORTS),
        blocks = feed && has(FeedCapabilities.BLOCKS),
        search = feed && has(FeedCapabilities.SEARCH),
        notifications = feed && has(FeedCapabilities.NOTIFICATIONS),
        follow = feed && has(FeedCapabilities.FOLLOW),
        events = feed && has(FeedCapabilities.EVENTS)
      )
    }

    /** [sampleData]：内部用户的本机样例，不连引擎，全开。 */
    @JvmStatic
    fun of(sampleData: Boolean, capabilities: FeedCapabilities): FeedEntries {
      return if (sampleData) ALL else from(capabilities)
    }
  }
}

/**
 * 当前页面能画哪些入口。默认 [FeedEntries.ALL] 只是为了界面单测和预览；正式页面都由 `setFeedContent` 和 `FeedListPane`
 * 换成 [FeedCapabilityStore.entries]（拿不到就是 [FeedEntries.NONE]）。
 */
val LocalFeedEntries = staticCompositionLocalOf { FeedEntries.ALL }

/**
 * 进程里一份能力检查（每个区一个 [FeedCapabilityChecker]，换区就是新的、从「没有能力」开始）。只在内存里：
 * 冷启动时还没问过 = 没部署，入口先藏着，回到前台问到了再画（MainActivity 重画底栏）。
 */
object FeedCapabilityStore {
  private val TAG = Log.tag(FeedCapabilityStore::class)

  private class Entry(val key: String, val checker: FeedCapabilityChecker)

  private var entry: Entry? = null

  private val _flow = MutableStateFlow(FeedEntries.NONE)

  /** 给界面收集：每次 [refreshIfStale] / [clear] 之后更新。第一次收集前调一次 [entries] 拿当前值。 */
  @JvmStatic
  fun flow(): StateFlow<FeedEntries> {
    _flow.value = entries()
    return _flow
  }

  @JvmStatic
  fun entries(): FeedEntries {
    if (TellomiFeed.isSampleDataEnabled()) return FeedEntries.ALL
    return FeedEntries.from(currentChecker()?.capabilities() ?: FeedCapabilities.DISABLED)
  }

  /** 过期了才问（60 秒，和引擎 `max-age=60` 对齐）；返回入口有没有变（变了要重画底栏）。会走网络，别在主线程调。 */
  @JvmStatic
  suspend fun refreshIfStale(): Boolean {
    if (!TellomiFeed.shouldAskEngine()) return false
    val checker = currentChecker() ?: return false
    val before = FeedEntries.from(checker.capabilities())
    val after = FeedEntries.from(checker.refreshIfStale())
    _flow.value = entries()
    return before != after
  }

  /** 退出登录、切区时调。 */
  @JvmStatic
  suspend fun clear() {
    val old = synchronized(this) { entry.also { entry = null } }
    old?.checker?.clear()
    _flow.value = entries()
  }

  @Synchronized
  private fun currentChecker(): FeedCapabilityChecker? {
    val region = TellomiRegions.current()
    val baseUrl = region.feed.toHttpUrlOrNull() ?: return null
    val key = "${region.id.id} ${region.feed}"
    entry?.takeIf { it.key == key }?.let { return it.checker }
    val checker = FeedCapabilityChecker(fetch = { fetch(baseUrl, AppDependencies.okHttpClient) })
    entry = Entry(key, checker)
    return checker
  }

  /** 不要凭证（契约 3.0）；不重试，失败（抛异常）交给 [FeedCapabilityChecker] 按「没有能力」处理。 */
  @JvmStatic
  suspend fun fetch(baseUrl: HttpUrl, client: OkHttpClient): FeedCapabilitiesResponse {
    val url = baseUrl.newBuilder().addPathSegments(FeedCapabilities.PATH.trimStart('/')).build()
    return withContext(Dispatchers.IO) {
      try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
          // 最多读 MAX_BODY_CHARS + 1 个字节：超长的由 parse 判成读不懂
          FeedCapabilitiesResponse(response.code, response.peekBody((FeedCapabilities.MAX_BODY_CHARS + 1).toLong()).string())
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "capabilities failed", e)
        throw e
      }
    }
  }
}
