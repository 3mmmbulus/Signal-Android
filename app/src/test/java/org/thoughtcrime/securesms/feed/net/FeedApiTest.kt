/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 内容引擎接口（`tellomi/content-engine` `internal/engine/api`）的请求形状与错误处理。全部在本机 MockWebServer 上。
 */
class FeedApiTest {

  private lateinit var server: MockWebServer
  private lateinit var tokens: CountingTokens
  private lateinit var api: FeedApi

  private class CountingTokens : FeedTokenSource {
    var issued = 0
    var refreshes = 0
    override suspend fun token(forceRefresh: Boolean): String {
      if (forceRefresh) refreshes++
      issued++
      return "t$issued"
    }
  }

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    tokens = CountingTokens()
    api = FeedApi(baseUrl = server.url("/"), client = OkHttpClient(), tokens = tokens)
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `following sends cursor and bearer token`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","reason":"following"}],"cursor":"c2"}"""))

    val page = api.feed("following", cursor = "abc")

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/feed/following")
    assertThat(request.requestUrl!!.queryParameter("cursor")).isEqualTo("abc")
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer t1")
    assertThat(page.items.map { it.id }).containsExactly("a")
    assertThat(page.cursor).isEqualTo("c2")
  }

  @Test
  fun `recommend has no cursor and can turn personalization off`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[]}"""))
    server.enqueue(MockResponse().setBody("""{"items":[]}"""))

    api.feed("recommend", cursor = null)
    api.feed("recommend", cursor = null, personalized = false)

    val first = server.takeRequest().requestUrl!!
    assertThat(first.encodedPath).isEqualTo("/v1/feed/recommend")
    assertThat(first.queryParameter("cursor")).isNull()
    assertThat(first.queryParameter("personalized")).isNull()
    assertThat(server.takeRequest().requestUrl!!.queryParameter("personalized")).isEqualTo("false")
  }

  @Test
  fun `unknown tab is refused`() {
    assertFailure { runBlocking { api.feed("hot", cursor = null) } }.isInstanceOf(IllegalArgumentException::class)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `401 refreshes the token and retries exactly once`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setBody("""{"items":[]}"""))

    api.feed("recommend", cursor = null)

    assertThat(server.requestCount).isEqualTo(2)
    assertThat(tokens.refreshes).isEqualTo(1)
    server.takeRequest()
    assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer t2")
  }

  @Test
  fun `second 401 is an error, not a third request`() {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(401))

    assertFailure { runBlocking { api.feed("recommend", cursor = null) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::status).isEqualTo(401)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `404 feed_hidden is recognised`() {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"feed_hidden"}"""))

    assertFailure { runBlocking { api.status() } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::isFeedDisabled).isTrue()
  }

  @Test
  fun `429 carries Retry-After`() {
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

    assertFailure { runBlocking { api.events(emptyList()) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::retryAfterSeconds).isEqualTo(30L)
  }

  @Test
  fun `like is PUT and unlike is DELETE on the reaction path`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))

    api.setReaction("c-1", FeedApi.REACTION_LIKE, on = true)
    api.setReaction("c-1", FeedApi.REACTION_LIKE, on = false)

    val put = server.takeRequest()
    assertThat(put.method).isEqualTo("PUT")
    assertThat(put.requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts/c-1/like")
    assertThat(server.takeRequest().method).isEqualTo("DELETE")
  }

  @Test
  fun `content id is a path segment, not a path`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))

    api.setReaction("../../admin", FeedApi.REACTION_SAVE, on = true)

    assertThat(server.takeRequest().requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts/..%2F..%2Fadmin/save")
  }

  @Test
  fun `cards posts ids and reads ok and unavailable alike`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","version":2,"card":{"kind":"post","text":"hi"},"stats":{"likes":3,"comments":1,"reposts":0},"me":{"liked":true,"saved":false}},{"id":"b","state":"unavailable"},{"id":"c","state":"something_new"}]}"""
      )
    )

    val response = api.cards(listOf("a", "b", "c"))

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.body.readUtf8()).isEqualTo("""{"ids":["a","b","c"]}""")
    assertThat(response.cards.map { it.isAvailable }).containsExactly(true, false, false)
    val card = response.cards.first().toCard()!!
    assertThat(card.id).isEqualTo("a")
    assertThat(card.version).isEqualTo(2)
    assertThat(card.stats.likes).isEqualTo(3)
    assertThat(card.viewer.liked).isTrue()
    assertThat(response.cards[1].toCard()).isNull()
  }

  @Test
  fun `not interested posts target and undo deletes it`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))

    api.notInterested("c-9", reason = null)
    api.undoNotInterested("c-9")

    val post = server.takeRequest()
    assertThat(post.requestUrl!!.encodedPath).isEqualTo("/v1/feed/not-interested")
    assertThat(post.body.readUtf8()).isEqualTo("""{"content_id":"c-9"}""")
    val delete = server.takeRequest()
    assertThat(delete.method).isEqualTo("DELETE")
    assertThat(delete.requestUrl!!.encodedPath).isEqualTo("/v1/feed/not-interested/c-9")
  }

  @Test
  fun `too many card ids is refused before sending`() {
    assertFailure { runBlocking { api.cards((0..FeedApi.MAX_CARD_IDS).map { it.toString() }) } }.isInstanceOf(IllegalArgumentException::class)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `unknown reaction is refused`() {
    assertFailure { runBlocking { api.setReaction("c", "dislike", on = true) } }.isInstanceOf(IllegalArgumentException::class)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `error without a json body still reports the status`() {
    server.enqueue(MockResponse().setResponseCode(503).setBody("<html>busy</html>"))

    assertFailure { runBlocking { api.status() } }
      .isInstanceOf(FeedApiException::class)
      .given { e ->
        assertThat(e.status).isEqualTo(503)
        assertThat(e.code).isNull()
        assertThat(e.isFeedDisabled).isFalse()
      }
  }

  @Test
  fun `create post returns the review state`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"p1","state":"pending","version":1}"""))

    val response = api.createPost(
      FeedCreatePostRequest(kind = "post", text = "hi", visibility = "friends", commentsClosed = false)
    )

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts")
    // 引擎 DisallowUnknownFields：只能有它认的字段
    assertThat(request.body.readUtf8()).isEqualTo("""{"kind":"post","text":"hi","visibility":"friends","comments_closed":false}""")
    assertThat(response.state).isEqualTo(FeedCreatePostResponse.STATE_PENDING)
  }

  @Test
  fun `my posts pages with before and reads review state`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"posts":[{"id":"p1","kind":"post","state":"rejected","version":1,"text":"x","visibility":"public",""" +
          """"created_at":"2026-10-13T08:00:00Z","rejection_reasons":["lexicon:ad"]}],"next_before":"p1"}"""
      )
    )
    server.enqueue(MockResponse().setBody("""{"posts":[]}"""))

    val first = api.myPosts(before = null)
    val second = api.myPosts(before = "p1")

    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/posts")
    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/posts?before=p1")
    assertThat(first.posts.single().state).isEqualTo(FeedMyPost.STATE_REJECTED)
    assertThat(first.posts.single().rejectionReasons).isEqualTo(listOf("lexicon:ad"))
    assertThat(first.nextBefore).isEqualTo("p1")
    assertThat(second.nextBefore).isEqualTo(null)
  }

  @Test
  fun `delete post is a DELETE on the post`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))

    api.deletePost("p 1")

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("DELETE")
    assertThat(request.path).isEqualTo("/v1/feed/posts/p%201")
  }

  @Test
  fun `edit post is a PATCH with only text for a post`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"id":"p1","state":"pending","version":2}"""))

    val response = api.editPost("p1", FeedEditPostRequest(text = "new"))

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("PATCH")
    assertThat(request.path).isEqualTo("/v1/feed/posts/p1")
    assertThat(request.body.readUtf8()).isEqualTo("""{"text":"new"}""")
    assertThat(response.version).isEqualTo(2)
  }

  @Test
  fun `closed edit window is a 409 with its code`() {
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"edit_window_closed"}"""))

    assertFailure { runBlocking { api.editPost("p1", FeedEditPostRequest(text = "new")) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::code).isEqualTo("edit_window_closed")
  }

  @Test
  fun `comments page with cursor and keeps root and parent ids`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"comments":[{"id":"c1","state":"ok","version":1,"card":{"kind":"comment","text":"hi","root_id":"p1"}},{"id":"c2","state":"ok","version":1,"card":{"kind":"comment","text":"yo","root_id":"p1","parent_id":"c1"}}],"cursor":"n2"}"""
      )
    )

    val response = api.comments("p1", cursor = "n1")

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts/p1/comments")
    assertThat(request.requestUrl!!.queryParameter("cursor")).isEqualTo("n1")
    val cards = response.comments.mapNotNull { it.toCard() }
    assertThat(cards.map { it.id }).containsExactly("c1", "c2")
    assertThat(cards[1].rootId).isEqualTo("p1")
    assertThat(cards[1].parentId).isEqualTo("c1")
    assertThat(response.cursor).isEqualTo("n2")
  }

  @Test
  fun `create comment posts text and parent`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"c9","state":"pending"}"""))

    val response = api.createComment("p1", FeedCreateCommentRequest(text = "nice", parentId = "c1"))

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.path).isEqualTo("/v1/feed/posts/p1/comments")
    assertThat(request.body.readUtf8()).isEqualTo("""{"text":"nice","parent_id":"c1"}""")
    assertThat(response.state).isEqualTo("pending")
  }

  @Test
  fun `closed comments are a 403 with its code`() {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"comments_closed"}"""))

    assertFailure { runBlocking { api.createComment("p1", FeedCreateCommentRequest(text = "nice")) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::code).isEqualTo("comments_closed")
  }

  @Test
  fun `no profile yet reads as null`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"profile_required"}"""))

    assertThat(api.myProfile()).isNull()
    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/profile")
  }

  @Test
  fun `save profile puts only the given fields`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"id":"x","tellomi_number":"1234567890","handle":null,"display_name":"山风","bio":"hi","shown_interests":null,"is_private":false,"is_minor":false,"region":"cn"}"""))

    val profile = api.saveProfile(FeedProfileUpdate(displayName = "山风"))

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("PUT")
    assertThat(request.body.readUtf8()).isEqualTo("""{"display_name":"山风"}""")
    assertThat(profile.tellomiNumber).isEqualTo("1234567890")
    assertThat(profile.shownInterests).isEqualTo(emptyList<String>())
  }

  @Test
  fun `other profile errors are not swallowed`() {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"feed_hidden"}"""))

    assertFailure { runBlocking { api.myProfile() } }.isInstanceOf(FeedApiException::class)
  }

  @Test
  fun `appeal posts the reason and reads the appeal fields`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(202))

    api.appeal("p1", "只是分享")

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.path).isEqualTo("/v1/feed/posts/p1/appeal")
    assertThat(request.body.readUtf8()).isEqualTo("""{"text":"只是分享"}""")

    val post = FeedJson.decodeFromString(FeedMyPost.serializer(), """{"id":"p","state":"rejected","rejection_reasons":["prohibited_words"],"appealable":false,"appeal_state":"received"}""")
    assertThat(post.appealState).isEqualTo(FeedMyPost.APPEAL_RECEIVED)
  }

  @Test
  fun `report posts the target and reason, leaving out empty fields`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(202))

    api.report(FeedReportRequest(targetType = "content", targetId = "p1", group = "illegal", reason = "fraud", evidenceKeys = listOf("m/a")))

    val request = server.takeRequest()
    assertThat(request.path).isEqualTo("/v1/feed/reports")
    assertThat(request.body.readUtf8()).isEqualTo("""{"target_type":"content","target_id":"p1","group":"illegal","reason":"fraud","evidence_keys":["m/a"]}""")
  }

  @Test
  fun `config reads the report reasons`() {
    val config = FeedJson.decodeFromString(FeedConfig.serializer(), """{"enabled":true,"report_reasons":{"illegal":["fraud","drugs"]}}""")

    assertThat(config.reportReasons).isEqualTo(mapOf("illegal" to listOf("fraud", "drugs")))
  }

  @Test
  fun `open content attribution is an object and does not break the page`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","card":{"kind":"post","text":"hi","attribution":{"name":"某站","url":"https://example.com"},"rights":{"basis":"license","license":"CC-BY-4.0"}}},{"id":"b","state":"ok","card":{"kind":"post","text":"yo","attribution":"plain"}}]}"""
      )
    )

    val cards = api.cards(listOf("a", "b")).cards.mapNotNull { it.toCard() }

    assertThat(cards.map { it.id }).containsExactly("a", "b")
    assertThat(cards.first().attribution.toString().contains("某站")).isTrue()
  }

  @Test
  fun `profile page reads the relation and hidden profiles are null`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"id":"p1","handle":null,"tellomi_number":"3820145967","display_name":"小满","avatar_key":null,"bio":"慢慢来","shown_interests":["读书"],"badge":"official","is_private":true,"ip_region_label":"四川","followers":12,"following":3,"region_unavailable":false,"me":{"self":false,"follow":"pending","followed_by":true,"friend":false,"blocked":false},"close_people":[{"id":"p2","display_name":"山风"}]}"""
      )
    )
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))

    val page = api.profile("p1")!!
    val hidden = api.profile("p9")

    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/profiles/p1")
    assertThat(page.tellomiNumber).isEqualTo("3820145967")
    assertThat(page.me.follow).isEqualTo(FeedRelation.FOLLOW_PENDING)
    assertThat(page.me.followedBy).isTrue()
    assertThat(page.canSeePosts).isFalse()
    assertThat(page.closePeople.map { it.displayName }).containsExactly("山风")
    assertThat(hidden).isNull()
  }

  @Test
  fun `profile posts pass the cursor and keep only available cards`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"posts":[{"id":"a","state":"ok","card":{"kind":"post","text":"hi"}},{"id":"b","state":"unavailable"}],"cursor":"c2"}"""
      )
    )

    val response = api.profilePosts("p1", cursor = "c1")

    val url = server.takeRequest().requestUrl!!
    assertThat(url.encodedPath).isEqualTo("/v1/feed/profiles/p1/posts")
    assertThat(url.queryParameter("cursor")).isEqualTo("c1")
    assertThat(response.posts.mapNotNull { it.toCard() }.map { it.id }).containsExactly("a")
    assertThat(response.cursor).isEqualTo("c2")
  }

  @Test
  fun `follow and unfollow return the new state, block has no body`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"state":"pending"}"""))
    server.enqueue(MockResponse().setBody("""{"state":"none"}"""))
    server.enqueue(MockResponse().setResponseCode(204))

    assertThat(api.follow("author", "p1", on = true)).isEqualTo("pending")
    assertThat(api.follow("author", "p1", on = false)).isEqualTo("none")
    api.block("author", "p1", on = true)

    val put = server.takeRequest()
    assertThat(put.method).isEqualTo("PUT")
    assertThat(put.path).isEqualTo("/v1/feed/follow/author/p1")
    assertThat(server.takeRequest().method).isEqualTo("DELETE")
    val block = server.takeRequest()
    assertThat(block.method).isEqualTo("PUT")
    assertThat(block.path).isEqualTo("/v1/feed/blocks/author/p1")
  }

  @Test
  fun `following someone without a public profile is a conflict`() {
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"profile_required"}"""))

    assertFailure { runBlocking { api.follow("author", "p1", on = true) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::code).isEqualTo("profile_required")
  }

  @Test
  fun `follow requests are listed and answered, blocks list keeps non-author entries`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"requests":[{"profile":{"id":"p2","handle":"xm","display_name":"Xiaoman","avatar_key":null},"requested_at":"2026-10-13T10:00:00Z"}]}"""
      )
    )
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(
      MockResponse().setBody(
        """{"blocks":[{"type":"author","id":"p3","created_at":"2026-10-12T00:00:00Z","profile":{"id":"p3","display_name":"Ahe"}},{"type":"topic","id":"t-cooking"}]}"""
      )
    )

    val requests = api.followRequests().requests
    assertThat(requests.single().profile.handle).isEqualTo("xm")
    api.answerFollowRequest("p2", approve = false)
    val blocks = api.blocks().blocks
    assertThat(blocks.map { it.type }).containsExactly("author", "topic")
    assertThat(blocks[0].profile?.displayName).isEqualTo("Ahe")
    assertThat(blocks[1].profile).isNull()

    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/follow-requests")
    val answer = server.takeRequest()
    assertThat(answer.method).isEqualTo("POST")
    assertThat(answer.path).isEqualTo("/v1/feed/me/follow-requests/p2")
    assertThat(answer.body.readUtf8()).isEqualTo("""{"approve":false}""")
    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/blocks")
  }

  @Test
  fun `notifications page, read, delete and mute paths`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"notifications":[{"id":"n1","kind":"reply","actor_id":"p1","content_id":"c1","subject_id":"m2","detail":{"parent_id":"m1","held":true},"created_at":"2026-10-13T10:00:00Z","read":false},{"id":"n2","kind":"system","detail":{"type":"rejected"},"created_at":"2026-10-12T10:00:00Z","read":true}],"unread":{"interactions":1,"follows":0,"system":2}}"""
      )
    )
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))

    val page = api.notifications("interactions", before = "2026-10-14T00:00:00Z")
    assertThat(page.notifications.map { it.kind }).containsExactly("reply", "system")
    assertThat(page.notifications[1].actorId).isNull()
    assertThat(page.unread.system).isEqualTo(2)
    api.markNotificationsRead("interactions")
    api.deleteNotification("n1")
    api.muteNotifications("c1", likesOnly = true)

    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/notifications?tab=interactions&before=2026-10-14T00%3A00%3A00Z")
    val read = server.takeRequest()
    assertThat(read.path).isEqualTo("/v1/feed/me/notifications/read")
    assertThat(read.body.readUtf8()).isEqualTo("""{"tab":"interactions"}""")
    val delete = server.takeRequest()
    assertThat(delete.method).isEqualTo("DELETE")
    assertThat(delete.path).isEqualTo("/v1/feed/me/notifications/n1")
    val mute = server.takeRequest()
    assertThat(mute.method).isEqualTo("PUT")
    assertThat(mute.path).isEqualTo("/v1/feed/me/notification-mutes/c1")
    assertThat(mute.body.readUtf8()).isEqualTo("""{"likes_only":true}""")
  }

  @Test
  fun `search sends query and scope and reads people and post ids`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"users":[{"id":"p1","handle":"shanfeng","display_name":"Shanfeng","avatar_key":null,"badge":null}],"items":[{"id":"c1","reason":"search"}],"cursor":"n2"}"""
      )
    )

    val response = api.search("云 & rain", FeedApi.SEARCH_ALL, cursor = "n1")

    assertThat(response.users.single().handle).isEqualTo("shanfeng")
    assertThat(response.items.single().id).isEqualTo("c1")
    assertThat(response.cursor).isEqualTo("n2")
    val request = server.takeRequest()
    assertThat(request.requestUrl?.queryParameter("q")).isEqualTo("云 & rain")
    assertThat(request.requestUrl?.queryParameter("scope")).isEqualTo("all")
    assertThat(request.requestUrl?.queryParameter("cursor")).isEqualTo("n1")
  }

  @Test
  fun `reportFriend posts the assertion as is`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"confirmed":true}"""))

    val response = api.reportFriend("a.b.c")

    assertThat(response.confirmed).isEqualTo(true)
    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.path).isEqualTo("/v1/feed/friends")
    assertThat(request.body.readUtf8()).isEqualTo("""{"assertion":"a.b.c"}""")
  }
}
