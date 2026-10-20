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
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.region.TellomiRegions

/**
 * 动态里哪些功能入口要画（契约 3.0 `GET /v1/capabilities`，产品经理 2026-10-20 定）：
 * `features` 里没有的功能，入口隐藏；**拿不到 capabilities（没问过、断网、超时、404、读不懂）一律按「没部署」处理，入口隐藏**。
 * 这件事要在首次提交商店审核之前做完：审核会因为点了没反应的入口被拒。
 *
 * 只管内容引擎的路由；Signal-Server 的 `/v1/tellomi/feed/` 那几条（取凭证、好友声明、名单）不在 capabilities 里，仍按 404 标记判断。
 *
 * 不在这里的入口（分享到聊天 / 快拍、复制链接、收藏到「我的收藏」）是纯本机或走 Signal 自己的通道，不看 capabilities。
 *
 * **举报入口永远不藏**（合规红线，产品经理 2026-10-20）：引擎没部署 `reports` 时整个动态（[feed]）一起藏，不留一个举报不了的动态。
 * 规则和 iOS 一致：标签 `feed`（+`reports`）；发布、我的动态、发布附加项 `posting`；作者主页 `profiles`；搜索 `search`；
 * 通知 `notifications`；名单 `follow` + `blocks`；详情事件 `events`；评论输入、点赞、投票、语音评论用引擎各自的能力名。
 */
data class FeedEntries(
  /** 动态标签本身（列表、卡片、详情）。要 `feed` 和 `reports` 都在。 */
  val feed: Boolean,
  /** 发布按钮、「我的动态」、编辑、引用、转发。 */
  val posting: Boolean,
  /** 作者主页（点头像 / 名字）。 */
  val profiles: Boolean,
  /** 名单（不看、不让看、已屏蔽、关注请求、关注的话题）：要 `follow` 和 `blocks`。 */
  val lists: Boolean,
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
  /** 「···」里的屏蔽作者。 */
  val blocks: Boolean,
  /** 顶栏搜索。 */
  val search: Boolean,
  /** 顶栏通知铃铛。 */
  val notifications: Boolean,
  /** 关注作者、关注话题。 */
  val follow: Boolean,
  /** 详情事件 / 曝光事件上报（不是入口，但没部署时别打）。 */
  val events: Boolean,
  /** 好友同步（`FeedFriendSync`）。 */
  val friendAssertions: Boolean
) {
  companion object {
    @JvmField
    val NONE = FeedEntries(
      feed = false, posting = false, profiles = false, lists = false, mediaUpload = false, polls = false, comments = false, voiceComments = false,
      reactions = false, blocks = false, search = false, notifications = false, follow = false, events = false, friendAssertions = false
    )

    /** 本机样例数据（不连引擎）和界面单测 / 预览用：全开。 */
    @JvmField
    val ALL = FeedEntries(
      feed = true, posting = true, profiles = true, lists = true, mediaUpload = true, polls = true, comments = true, voiceComments = true,
      reactions = true, blocks = true, search = true, notifications = true, follow = true, events = true, friendAssertions = true
    )

    /** 语音评论要先能评论；投票、加图要先能发帖（加图的上传本身也要 `media_upload`）。 */
    @JvmStatic
    fun from(capabilities: FeedCapabilities): FeedEntries {
      fun has(name: String) = capabilities.supports(name)
      // 举报后端没部署就整个动态不出（举报入口不能藏）
      val feed = has(FeedCapabilities.FEED) && has(FeedCapabilities.REPORTS)
      val posting = feed && has(FeedCapabilities.POSTING)
      val comments = feed && has(FeedCapabilities.COMMENTS)
      return FeedEntries(
        feed = feed,
        posting = posting,
        profiles = feed && has(FeedCapabilities.PROFILES),
        lists = feed && has(FeedCapabilities.FOLLOW) && has(FeedCapabilities.BLOCKS),
        mediaUpload = posting && has(FeedCapabilities.MEDIA_UPLOAD),
        polls = feed && has(FeedCapabilities.POLLS),
        comments = comments,
        voiceComments = comments && has(FeedCapabilities.VOICE_COMMENTS),
        reactions = feed && has(FeedCapabilities.REACTIONS),
        blocks = feed && has(FeedCapabilities.BLOCKS),
        search = feed && has(FeedCapabilities.SEARCH),
        notifications = feed && has(FeedCapabilities.NOTIFICATIONS),
        follow = feed && has(FeedCapabilities.FOLLOW),
        events = feed && has(FeedCapabilities.EVENTS),
        friendAssertions = feed && has(FeedCapabilities.FRIEND_ASSERTIONS)
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
 * 进程里一份能力检查（每个账号 + 引擎地址一个 [FeedCapabilityChecker]），上次成功的结果存本机（[FeedCapabilityCache]）：
 * 冷启动先用存的画底栏（不闪），回到前台在后台刷新，变了 MainActivity 再重画；存的超过 24 小时或从没成功过 = 没部署，入口藏。
 */
object FeedCapabilityStore {
  private val TAG = Log.tag(FeedCapabilityStore::class)

  private class Entry(val key: String, val checker: FeedCapabilityChecker)

  private var entry: Entry? = null

  private val cache = FeedCapabilityCache(
    object : FeedCapabilityCache.State {
      override var json: String?
        get() = SignalStore.tellomiFeed.capabilities
        set(value) {
          SignalStore.tellomiFeed.capabilities = value
        }
    }
  )

  private val _flow = MutableStateFlow(FeedEntries.NONE)

  /**
   * 现在该画哪些入口（纯函数，单测用）：样例数据全开；否则这个进程里最近一次成功的结果，
   * 没有就用本机存的上次成功（24 小时内），再没有就是 [FeedEntries.NONE]。
   */
  @JvmStatic
  fun resolve(sampleData: Boolean, inMemory: FeedCapabilities?, cached: FeedCapabilities?): FeedEntries {
    if (sampleData) return FeedEntries.ALL
    return FeedEntries.from(inMemory ?: cached ?: FeedCapabilities.DISABLED)
  }

  /** 给界面收集：每次 [refreshIfStale] / [clear] 之后更新。第一次收集前调一次 [entries] 拿当前值。 */
  @JvmStatic
  fun flow(): StateFlow<FeedEntries> {
    _flow.value = entries()
    return _flow
  }

  /** 不走网络：冷启动时直接用本机存的上次成功结果画底栏，所以不闪。 */
  @JvmStatic
  fun entries(): FeedEntries {
    if (TellomiFeed.isSampleDataEnabled()) return FeedEntries.ALL
    val current = current() ?: return FeedEntries.NONE
    return resolve(sampleData = false, inMemory = current.second.lastSuccess(), cached = cache.get(current.first))
  }

  /** 过期了才问（60 秒）；成功就存本机。返回入口有没有变（变了要重画底栏）。会走网络，别在主线程调。 */
  @JvmStatic
  suspend fun refreshIfStale(): Boolean {
    if (!TellomiFeed.shouldAskEngine()) return false
    val (key, checker) = current() ?: return false
    val before = entries()
    checker.refreshIfStale()
    checker.lastSuccess()?.let { cache.put(key, it) }
    val after = entries()
    _flow.value = after
    return before != after
  }

  /** 退出登录时调：内存和本机存的都清掉。 */
  @JvmStatic
  suspend fun clear() {
    val old = synchronized(this) { entry.also { entry = null } }
    old?.checker?.clear()
    cache.clear()
    _flow.value = entries()
  }

  /** 账号 + 引擎地址 → 缓存 key 和这一份的检查器；没登录（没有 ACI）或引擎地址不对就是 null。 */
  @Synchronized
  private fun current(): Pair<String, FeedCapabilityChecker>? {
    val region = TellomiRegions.current()
    val baseUrl = region.feed.toHttpUrlOrNull() ?: return null
    val key = FeedCapabilityCache.key(SignalStore.account.aci?.toString(), region.feed) ?: return null
    entry?.takeIf { it.key == key }?.let { return key to it.checker }
    val checker = FeedCapabilityChecker(fetch = { fetch(baseUrl, AppDependencies.okHttpClient) })
    entry = Entry(key, checker)
    return key to checker
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
