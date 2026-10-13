/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedDraftKind
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedBlockEntry
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCommentsPage
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedEventPayload
import org.thoughtcrime.securesms.feed.net.FeedFollowRequest
import org.thoughtcrime.securesms.feed.net.FeedMedia
import org.thoughtcrime.securesms.feed.net.FeedMyPost
import org.thoughtcrime.securesms.feed.net.FeedMyPostsPage
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedNotification
import org.thoughtcrime.securesms.feed.net.FeedNotificationSettings
import org.thoughtcrime.securesms.feed.net.FeedNotificationsResponse
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedProfilePostsPage
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate
import org.thoughtcrime.securesms.feed.net.FeedRelation
import org.thoughtcrime.securesms.feed.net.FeedReportRequest
import org.thoughtcrime.securesms.feed.net.FeedSearchPage
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import org.thoughtcrime.securesms.feed.net.FeedStats
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage
import org.thoughtcrime.securesms.feed.net.FeedTopic
import org.thoughtcrime.securesms.feed.net.FeedUnreadCounts
import org.thoughtcrime.securesms.feed.net.FeedViewer
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 本机样例：内部用户在内容引擎接上之前看界面用。人名、内容全是虚构的，守 `internal-beta-content-generator.md` 4.1
 * （不涉政、不点名真人与品牌、不编造新闻）。只在内存里，点赞和不感兴趣重启 App 就没了。
 */
object SampleFeedSource : FeedSource {

  private val TAG = Log.tag(SampleFeedSource::class)

  private const val PAGE_SIZE = 8

  private val liked = mutableSetOf<String>()
  private val hidden = mutableSetOf<String>()

  /** 自己在样例里发的，放在两栏最上面。 */
  private val mine = mutableListOf<FeedCard>()
  private var nextMineId = 0
  private val published = mutableMapOf<String, FeedCreatePostResponse>()

  /** 「我的动态」里另外两条只给作者看的：一条审核中、一条未通过，内部用户能看到这两种状态长什么样。 */
  private val reviewSamples = mutableListOf(
    FeedMyPost(id = "s-mine-pending", state = FeedMyPost.STATE_PENDING, text = "周末的市集有一家卖手工皂的，味道很好闻。", createdAt = "2026-10-13T08:00:00Z"),
    FeedMyPost(
      id = "s-mine-rejected",
      state = FeedMyPost.STATE_REJECTED,
      text = "加我领福利，名额有限。",
      createdAt = "2026-10-12T08:00:00Z",
      rejectionReasons = listOf("prohibited_words"),
      appealable = true
    )
  )

  private val me = FeedAuthor(profileId = "s-me", displayName = "我", tellomiNumber = "1000000001")

  private val authors = listOf(
    FeedAuthor(profileId = "s-1", displayName = "山风", handle = "shanfeng"),
    FeedAuthor(profileId = "s-2", displayName = "小满", handle = null, tellomiNumber = "3820145967"),
    FeedAuthor(profileId = "s-3", displayName = "阿禾在路上", handle = "ahe"),
    FeedAuthor(profileId = "s-4", displayName = "周末烘焙", handle = "weekend_bake"),
    FeedAuthor(profileId = "s-5", displayName = "夜跑的猫", handle = "nightcat")
  )

  private val texts = listOf(
    "今天的云很低，像是伸手就能摸到。下班路上停下来看了五分钟。",
    "第一次自己做酸面包，外皮裂得刚刚好，就是有点酸过头了。",
    "楼下新开了一家修自行车的小铺，老板说他修了三十年车。",
    "雨后的石板路，踩上去会响。",
    "夜跑第 100 天，配速没变快，但心情变好了。",
    "整理旧照片，翻到小时候在外婆家院子里的那棵枇杷树。",
    "周末去山里住了一晚，早上被鸟叫醒，比闹钟好听多了。",
    "学会了一个新词：「回甘」。喝完这杯茶才懂。",
    "阳台的薄荷又长疯了，谁要来摘一点？",
    "地铁上看完一本小说，最后一页没舍得翻。",
    "今天试着一整天不看手机，坚持到了下午三点。",
    "给猫换了新猫砂，它研究了半个小时才肯进去。"
  )

  private val topics = listOf(
    FeedTopic(slug = "daily", label = "日常"),
    FeedTopic(slug = "food", label = "美食"),
    FeedTopic(slug = "outdoors", label = "户外"),
    FeedTopic(slug = "reading", label = "读书")
  )

  private val ipLabels = listOf("四川", "重庆", "浙江", "广东", "云南")

  private val recommend: List<FeedCard> by lazy { (0 until 24).map { sampleCard(it) } }
  private val following: List<FeedCard> by lazy { recommend.filter { it.author?.profileId == "s-1" || it.author?.profileId == "s-3" } }

  override suspend fun config(): FeedConfig {
    return FeedConfig(
      enabled = true,
      region = "cn",
      tabs = FeedTab.entries.map { it.apiName },
      recommendMode = FeedConfig.RECOMMEND_MODE_EDITORIAL,
      ipRegionVisible = true
    )
  }

  override suspend fun isVisible(): Boolean = true

  override suspend fun timeline(tab: FeedTab, cursor: String?): FeedTimelinePage {
    val all = synchronized(this) { mine.toList() } + when (tab) {
      FeedTab.RECOMMEND -> recommend
      FeedTab.FOLLOWING -> following
    }.filter { it.id !in hidden }

    val start = cursor?.toIntOrNull()?.coerceIn(0, all.size) ?: 0
    val end = (start + PAGE_SIZE).coerceAtMost(all.size)
    val page = all.subList(start, end).map { card ->
      val isLiked = card.id in liked
      val likes = card.stats.likes + if (isLiked) 1 else 0
      FeedEntry(id = card.id, card = card.copy(viewer = card.viewer.copy(liked = isLiked), stats = card.stats.copy(likes = likes)))
    }

    return FeedTimelinePage(
      entries = page,
      nextCursor = if (end < all.size) end.toString() else null
    )
  }

  override suspend fun setLiked(contentId: String, liked: Boolean) {
    if (liked) this.liked += contentId else this.liked -= contentId
  }

  override suspend fun notInterested(contentId: String) {
    hidden += contentId
  }

  override suspend fun undoNotInterested(contentId: String) {
    hidden -= contentId
  }

  /** 样例不连网：事件只记个数，方便内部用户在日志里看曝光有没有在算。 */
  override suspend fun reportEvents(events: List<FeedEventPayload>) {
    Log.d(TAG, "events: ${events.size} (${events.groupingBy { it.type }.eachCount()})")
  }

  /** 同一个 [clientNonce] 重复发只算一次（契约 7.2 的去重）。 */
  override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse = synchronized(this) {
    published[clientNonce]?.let { return it }

    val id = "mine-${nextMineId++}"
    val card = FeedCard(
      id = id,
      kind = draft.kind.apiName,
      displayMode = when {
        draft.kind == FeedDraftKind.ARTICLE -> FeedCard.DISPLAY_ARTICLE_CARD
        draft.attachedImages.isNotEmpty() -> FeedCard.DISPLAY_TEXT_IMAGES
        else -> FeedCard.DISPLAY_TEXT
      },
      publishedAt = Instant.now().toString(),
      author = me,
      title = draft.title.trim().takeIf { draft.kind == FeedDraftKind.ARTICLE },
      text = draft.text.trim(),
      media = draft.attachedImages.map { FeedMedia(key = it.toString()) },
      ipRegionLabel = ipLabels.first(),
      viewer = FeedViewer(isAuthor = true)
    )
    mine.add(0, card)

    FeedCreatePostResponse(id = id, state = FeedCreatePostResponse.STATE_PUBLISHED).also { published[clientNonce] = it }
  }

  override suspend fun myPosts(before: String?): FeedMyPostsPage = synchronized(this) {
    val posts = mine.map { card ->
      FeedMyPost(id = card.id, kind = card.kind, state = FeedMyPost.STATE_PUBLISHED, title = card.title, text = card.text, createdAt = card.publishedAt)
    } + reviewSamples
    FeedMyPostsPage(posts = if (before == null) posts else emptyList())
  }

  override suspend fun appeal(contentId: String, text: String) {
    synchronized(this) {
      val index = reviewSamples.indexOfFirst { it.id == contentId }
      if (index >= 0) {
        reviewSamples[index] = reviewSamples[index].copy(appealable = false, appealState = FeedMyPost.APPEAL_RECEIVED)
      }
    }
  }

  /** 样例：不真传，给个假 key。 */
  override suspend fun uploadImage(uri: Uri): String = "m/sample-${uri.hashCode()}"

  /** 样例：举报只记日志。 */
  override suspend fun report(request: FeedReportRequest) {
    Log.i(TAG, "sample report ${request.targetType} ${request.group}/${request.reason}")
  }

  /** 样例不审核：改了直接生效，标「已编辑」。 */
  override suspend fun editPost(contentId: String, draft: FeedDraft): FeedCreatePostResponse = synchronized(this) {
    val title = draft.title.trim().takeIf { draft.kind == FeedDraftKind.ARTICLE }
    val index = mine.indexOfFirst { it.id == contentId }
    if (index >= 0) {
      mine[index] = mine[index].copy(text = draft.text.trim(), title = title, edited = true)
      return FeedCreatePostResponse(id = contentId, state = FeedCreatePostResponse.STATE_PUBLISHED, version = 2)
    }
    val review = reviewSamples.indexOfFirst { it.id == contentId }
    if (review >= 0) {
      reviewSamples[review] = reviewSamples[review].copy(text = draft.text.trim(), title = title, editedAt = Instant.now().toString())
      return FeedCreatePostResponse(id = contentId, state = FeedCreatePostResponse.STATE_PENDING, version = 2)
    }
    throw IllegalArgumentException("not my post: $contentId")
  }

  override suspend fun deletePost(contentId: String): Unit = synchronized(this) {
    mine.removeAll { it.id == contentId }
    reviewSamples.removeAll { it.id == contentId }
  }

  override suspend fun card(contentId: String): FeedCard? = synchronized(this) {
    (mine + recommend).firstOrNull { it.id == contentId && it.id !in hidden }?.let { card ->
      val isLiked = card.id in liked
      card.copy(viewer = card.viewer.copy(liked = isLiked), stats = card.stats.copy(likes = card.stats.likes + if (isLiked) 1 else 0))
    }
  }

  /** 每条帖子一组样例评论：几条直接评论，其中一条带两层回复，作者本人也回了一条。 */
  override suspend fun comments(postId: String, cursor: String?): FeedCommentsPage = synchronized(this) {
    FeedCommentsPage(comments = if (cursor == null) commentsFor(postId).toList() else emptyList())
  }

  /** 样例不审核：直接出现在列表里。 */
  override suspend fun postComment(postId: String, text: String, parentId: String?): String = synchronized(this) {
    val list = commentsFor(postId)
    list += FeedCard(
      id = "$postId-c${list.size}",
      kind = FeedCard.KIND_COMMENT,
      publishedAt = Instant.now().toString(),
      author = me,
      text = text.trim(),
      rootId = postId,
      parentId = parentId,
      ipRegionLabel = ipLabels.first(),
      viewer = FeedViewer(isAuthor = true)
    )
    FeedCreatePostResponse.STATE_PUBLISHED
  }

  private val comments = mutableMapOf<String, MutableList<FeedCard>>()

  private var profile: FeedMyProfile? = FeedMyProfile(id = "s-me", tellomiNumber = me.tellomiNumber, displayName = me.displayName)

  override suspend fun myProfile(): FeedMyProfile? = synchronized(this) { profile }

  override suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile = synchronized(this) {
    val current = profile ?: FeedMyProfile(id = "s-me", tellomiNumber = me.tellomiNumber, displayName = update.displayName ?: throw IllegalArgumentException("name required"))
    current.copy(displayName = update.displayName ?: current.displayName, bio = update.bio ?: current.bio).also { profile = it }
  }

  /** 样例里关注着「关注」栏那两位；「周末烘焙」是私密账号，关注要等批准（样例里一直是待批准）。 */
  private val follows = mutableMapOf("s-1" to FeedRelation.FOLLOW_APPROVED, "s-3" to FeedRelation.FOLLOW_APPROVED)
  private val blocked = mutableSetOf<String>()
  private const val PRIVATE_SAMPLE = "s-4"

  private val bios = mapOf(
    "s-1" to "喜欢看云，也喜欢走路。",
    "s-2" to "慢慢来。",
    "s-3" to "背包走过一些地方，还想走更多。",
    "s-4" to "周末烤点面包，偶尔成功。",
    "s-5" to "夜里跑步，白天睡觉的猫。"
  )

  override suspend fun profile(profileId: String): FeedProfilePage? = synchronized(this) { profileOf(profileId) }

  private fun profileOf(profileId: String): FeedProfilePage? {
    val self = profileId == me.profileId
    val author = if (self) me.copy(displayName = profile?.displayName ?: me.displayName) else authors.firstOrNull { it.profileId == profileId } ?: return null
    val index = authors.indexOf(author).coerceAtLeast(0)
    val follow = follows[profileId] ?: FeedRelation.FOLLOW_NONE
    return FeedProfilePage(
      id = profileId,
      handle = author.handle,
      tellomiNumber = author.tellomiNumber,
      displayName = author.displayName,
      bio = if (self) profile?.bio else bios[profileId],
      shownInterests = if (self) emptyList() else listOf(topics[index % topics.size].label),
      isPrivate = profileId == PRIVATE_SAMPLE,
      ipRegionLabel = ipLabels[index % ipLabels.size],
      followers = 120 + index * 37 + if (follow == FeedRelation.FOLLOW_APPROVED) 1 else 0,
      following = 40 + index * 11,
      me = FeedRelation(self = self, follow = follow, followedBy = profileId == "s-1", blocked = profileId in blocked)
    )
  }

  override suspend fun profilePosts(profileId: String, cursor: String?): FeedProfilePostsPage = synchronized(this) {
    val page = profileOf(profileId) ?: return FeedProfilePostsPage()
    if (!page.canSeePosts || page.me.blocked) return FeedProfilePostsPage()
    FeedProfilePostsPage(posts = (mine + recommend).filter { it.author?.profileId == profileId && it.id !in hidden })
  }

  override suspend fun setFollowing(profileId: String, following: Boolean): String = synchronized(this) {
    val state = when {
      !following -> FeedRelation.FOLLOW_NONE
      profileId == PRIVATE_SAMPLE -> FeedRelation.FOLLOW_PENDING
      else -> FeedRelation.FOLLOW_APPROVED
    }
    if (state == FeedRelation.FOLLOW_NONE) follows -= profileId else follows[profileId] = state
    state
  }

  override suspend fun setBlocked(profileId: String, blocked: Boolean): Unit = synchronized(this) {
    if (blocked) {
      this.blocked += profileId
      follows -= profileId
    } else {
      this.blocked -= profileId
    }
  }

  /** 想关注我的人（样例里两条）。 */
  private val incomingRequests = mutableListOf("s-2", "s-5")

  private fun summaryOf(profileId: String): FeedPersonSummary? {
    val author = authors.firstOrNull { it.profileId == profileId } ?: return null
    return FeedPersonSummary(id = author.profileId, handle = author.handle, displayName = author.displayName, avatarKey = author.avatarKey)
  }

  override suspend fun followRequests(): List<FeedFollowRequest> = synchronized(this) {
    incomingRequests.filter { it !in blocked }.mapNotNull { id -> summaryOf(id)?.let { FeedFollowRequest(profile = it) } }
  }

  override suspend fun answerFollowRequest(profileId: String, approve: Boolean): Unit = synchronized(this) {
    incomingRequests -= profileId
  }

  override suspend fun blocks(): List<FeedBlockEntry> = synchronized(this) {
    blocked.map { FeedBlockEntry(type = FeedApi.TARGET_AUTHOR, id = it, profile = summaryOf(it)) }
  }

  override suspend fun unblock(entry: FeedBlockEntry): Unit = synchronized(this) {
    blocked -= entry.id
  }

  private val notifications: MutableList<FeedNotification> by lazy {
    val now = Instant.now()
    fun at(minutesAgo: Long) = now.minus(minutesAgo, ChronoUnit.MINUTES).toString()
    fun detail(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
    mutableListOf(
      FeedNotification(id = "n-1", kind = FeedNotification.KIND_LIKE, actorId = "s-1", contentId = "sample-0", createdAt = at(5)),
      FeedNotification(id = "n-2", kind = FeedNotification.KIND_LIKE, actorId = "s-3", contentId = "sample-0", createdAt = at(40)),
      FeedNotification(id = "n-3", kind = FeedNotification.KIND_LIKE, actorId = "s-5", contentId = "sample-0", createdAt = at(90), read = true),
      FeedNotification(id = "n-4", kind = FeedNotification.KIND_COMMENT, actorId = "s-2", contentId = "sample-0", subjectId = "sample-0-c0", createdAt = at(120)),
      FeedNotification(
        id = "n-5",
        kind = FeedNotification.KIND_REPLY,
        actorId = "s-4",
        contentId = "sample-0",
        subjectId = "sample-0-c9",
        detail = detail("parent_id" to "sample-0-c0", "held" to true),
        createdAt = at(60 * 30),
        read = true
      ),
      FeedNotification(id = "n-6", kind = FeedNotification.KIND_FOLLOW, actorId = "s-1", createdAt = at(30)),
      FeedNotification(id = "n-7", kind = FeedNotification.KIND_FOLLOW_REQUEST, actorId = "s-2", createdAt = at(60 * 50)),
      FeedNotification(id = "n-8", kind = FeedNotification.KIND_SYSTEM, contentId = "s-mine-rejected", detail = detail("type" to FeedNotification.SYSTEM_REJECTED), createdAt = at(60 * 24 * 9))
    )
  }

  private fun tabOf(kind: String): String = when (kind) {
    FeedNotification.KIND_FOLLOW, FeedNotification.KIND_FOLLOW_REQUEST -> FeedApi.NOTIFICATIONS_FOLLOWS
    FeedNotification.KIND_SYSTEM -> FeedApi.NOTIFICATIONS_SYSTEM
    else -> FeedApi.NOTIFICATIONS_INTERACTIONS
  }

  override suspend fun notifications(tab: String, before: String?): FeedNotificationsResponse = synchronized(this) {
    val visible = notifications.filter { it.actorId !in blocked }
    fun unread(t: String) = visible.count { tabOf(it.kind) == t && !it.read }
    FeedNotificationsResponse(
      notifications = if (before == null) visible.filter { tabOf(it.kind) == tab } else emptyList(),
      unread = FeedUnreadCounts(
        interactions = unread(FeedApi.NOTIFICATIONS_INTERACTIONS),
        follows = unread(FeedApi.NOTIFICATIONS_FOLLOWS),
        system = unread(FeedApi.NOTIFICATIONS_SYSTEM)
      )
    )
  }

  override suspend fun markNotificationsRead(tab: String): Unit = synchronized(this) {
    notifications.replaceAll { if (tabOf(it.kind) == tab) it.copy(read = true) else it }
  }

  override suspend fun deleteNotification(id: String): Unit = synchronized(this) {
    notifications.removeAll { it.id == id }
  }

  override suspend fun search(query: String, scope: String, cursor: String?): FeedSearchPage = synchronized(this) {
    val q = query.trim()
    if (q.isEmpty() || cursor != null) return FeedSearchPage()
    // 和引擎一样：只搜设了 ID 的人，ID 前缀或名字包含
    val users = if (scope == FeedApi.SEARCH_POSTS) {
      emptyList()
    } else {
      authors.filter { a -> a.handle != null && a.profileId !in blocked && (a.handle.startsWith(q, ignoreCase = true) || a.displayName.contains(q, ignoreCase = true)) }
        .map { FeedSearchUser(id = it.profileId, handle = it.handle, displayName = it.displayName) }
        .take(if (scope == FeedApi.SEARCH_ALL) 5 else 20)
    }
    val posts = if (scope == FeedApi.SEARCH_USERS) {
      emptyList()
    } else {
      (mine + recommend).filter { it.id !in hidden && ((it.text?.contains(q, ignoreCase = true) == true) || (it.title?.contains(q, ignoreCase = true) == true)) }.take(10)
    }
    FeedSearchPage(users = users, posts = posts)
  }

  private var notificationSettings = FeedNotificationSettings()

  override suspend fun notificationSettings(): FeedNotificationSettings = synchronized(this) { notificationSettings }

  override suspend fun saveNotificationSettings(settings: FeedNotificationSettings): FeedNotificationSettings = synchronized(this) {
    notificationSettings = settings
    settings
  }

  /** 样例里没有真实的好友关系；好友同步不会对样例调这个。 */
  override suspend fun reportFriend(assertion: String): Boolean = false

  override suspend fun muteNotifications(contentId: String, likesOnly: Boolean): Unit = synchronized(this) {
    notifications.removeAll { it.contentId == contentId && (!likesOnly || it.kind == FeedNotification.KIND_LIKE) }
  }

  private val commentTexts = listOf(
    "看着就舒服，周末也想去走走。",
    "这个我也遇到过，后来发现是水放多了。",
    "哈哈，同款心情。",
    "请问是在哪里拍的？",
    "收藏了，下次试试。"
  )

  private fun commentsFor(postId: String): MutableList<FeedCard> {
    return comments.getOrPut(postId) {
      val postAuthor = (mine + recommend).firstOrNull { it.id == postId }?.author ?: authors.first()
      val start = Instant.now().minus(3, ChronoUnit.HOURS)
      fun comment(n: Int, author: FeedAuthor, text: String, parent: String? = null) = FeedCard(
        id = "$postId-c$n",
        kind = FeedCard.KIND_COMMENT,
        publishedAt = start.plus(n * 11L, ChronoUnit.MINUTES).toString(),
        author = author,
        text = text,
        rootId = postId,
        parentId = parent,
        ipRegionLabel = ipLabels[n % ipLabels.size],
        stats = FeedStats(likes = (n * 5) % 9)
      )
      mutableListOf(
        comment(0, authors[1], commentTexts[0]),
        comment(1, authors[2], commentTexts[3]),
        comment(2, postAuthor, "在城西的山里，开车一个小时。", parent = "$postId-c1"),
        comment(3, authors[3], commentTexts[1]),
        comment(4, authors[2], "谢谢！", parent = "$postId-c2"),
        comment(5, authors[4], commentTexts[2])
      )
    }
  }

  private fun sampleCard(index: Int): FeedCard {
    val author = authors[index % authors.size]
    val imageCount = when (index % 4) {
      1 -> 1
      3 -> 3
      else -> 0
    }

    return FeedCard(
      id = "sample-$index",
      kind = FeedCard.KIND_POST,
      displayMode = if (imageCount > 0) FeedCard.DISPLAY_TEXT_IMAGES else FeedCard.DISPLAY_TEXT,
      publishedAt = Instant.now().minus((index * 37L + 5), ChronoUnit.MINUTES).toString(),
      edited = index == 5,
      author = author,
      text = texts[index % texts.size],
      media = (0 until imageCount).map { FeedMedia(key = "sample/$index/$it", w = 1080, h = if (it % 2 == 0) 1440 else 1080, alt = "") },
      topics = listOf(topics[index % topics.size]),
      ipRegionLabel = ipLabels[index % ipLabels.size],
      stats = FeedStats(likes = (index * 7) % 50, comments = (index * 3) % 12, reposts = index % 4),
      viewer = FeedViewer()
    )
  }
}
