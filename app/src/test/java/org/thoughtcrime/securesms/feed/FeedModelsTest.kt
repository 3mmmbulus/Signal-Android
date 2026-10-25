/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCardResult
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreateCommentRequest
import org.thoughtcrime.securesms.feed.net.FeedIdsPage
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.feed.net.FeedMedia
import org.thoughtcrime.securesms.feed.net.FeedPostSettings
import org.thoughtcrime.securesms.feed.net.FeedPostSettingsUpdate
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedStats
import org.thoughtcrime.securesms.feed.net.FeedTopic
import org.thoughtcrime.securesms.feed.net.FeedViewer

class FeedModelsTest {

  @Test
  fun `card result ignores unknown fields and fills missing ones`() {
    val result = FeedJson.decodeFromString(
      FeedCardResult.serializer(),
      """{"id":"x","state":"ok","version":1,"future_field":1,"card":{"kind":"post","display_mode":"text","source_url":null,"ai_generated":false,"edited":true,"text":"hi","brand_new":{"a":1},"author":{"id":"p1","display_name":"山风","native":true}},"stats":{"likes":3,"comments":0,"reposts":0}}"""
    )

    val card = result.toCard()!!
    assertThat(card.id).isEqualTo("x")
    assertThat(card.text).isEqualTo("hi")
    assertThat(card.edited).isTrue()
    assertThat(card.stats.likes).isEqualTo(3)
    assertThat(card.viewer.liked).isFalse()
    assertThat(card.viewer.canComment).isTrue()
    assertThat(card.author?.profileId).isEqualTo("p1")
    assertThat(card.author?.isNative).isEqualTo(true)
  }

  @Test
  fun `voice comment card carries audio duration and transcript, settings default voice on`() {
    val card = FeedJson.decodeFromString(
      FeedCard.serializer(),
      """{"id":"c","kind":"comment","text":"","transcript":"","media":[{"type":"audio","key":"m/v1","duration_ms":4200}]}"""
    )
    assertThat(card.media.single().isAudio).isTrue()
    assertThat(card.media.single().durationMs).isEqualTo(4200L)
    assertThat(card.transcript).isEqualTo("")
    assertThat(FeedJson.decodeFromString(FeedMedia.serializer(), """{"key":"m/i"}""").isAudio).isFalse()

    assertThat(FeedJson.decodeFromString(FeedPostSettings.serializer(), "{}").voiceComments).isTrue()
    assertThat(FeedJson.decodeFromString(FeedPostSettings.serializer(), """{"voice_comments":false}""").voiceComments).isFalse()
    assertThat(FeedJson.encodeToString(FeedPostSettingsUpdate.serializer(), FeedPostSettingsUpdate(voiceComments = false))).isEqualTo("""{"voice_comments":false}""")
    assertThat(FeedJson.decodeFromString(FeedConfig.serializer(), "{}").limits.voiceCommentMaxSeconds).isEqualTo(60)
  }

  @Test
  fun `a voice comment request carries media keys, a text one leaves them out`() {
    assertThat(FeedJson.encodeToString(FeedCreateCommentRequest.serializer(), FeedCreateCommentRequest(text = "", parentId = "a", mediaKeys = listOf("m/v"))))
      .isEqualTo("""{"text":"","parent_id":"a","media_keys":["m/v"]}""")
    assertThat(FeedJson.encodeToString(FeedCreateCommentRequest.serializer(), FeedCreateCommentRequest(text = "hi"))).isEqualTo("""{"text":"hi"}""")
  }

  @Test
  fun `feed page is ids only`() {
    val page = FeedJson.decodeFromString(FeedIdsPage.serializer(), """{"items":[{"id":"a","reason":"editorial"},{"id":"b"}]}""")

    assertThat(page.items.map { it.id }).containsExactly("a", "b")
    assertThat(page.cursor).isNull()
  }

  @Test
  fun `unknown kind parses but is not rendered`() {
    val card = FeedJson.decodeFromString(FeedCard.serializer(), """{"id":"x","kind":"live_room"}""")

    assertThat(card.isRenderable).isFalse()
  }

  @Test
  fun `config defaults to editorial when mode is missing`() {
    val config = FeedJson.decodeFromString(FeedConfig.serializer(), """{"enabled":true,"tabs":["recommend","following"]}""")

    assertThat(config.recommendMode).isEqualTo(FeedConfig.RECOMMEND_MODE_EDITORIAL)
    assertThat(config.limits.postTextChars).isEqualTo(500)
    assertThat(FeedState(config = config).isEditorialRecommend).isTrue()
    assertThat(FeedState(config = config.copy(recommendMode = FeedConfig.RECOMMEND_MODE_SCORED)).isEditorialRecommend).isFalse()
  }

  @Test
  fun `quote is an id and poll counts sit outside the card`() {
    val result = FeedJson.decodeFromString(
      FeedCardResult.serializer(),
      """{"id":"r","state":"ok","card":{"kind":"post","quote_of":"q","poll":{"options":["A","B"],"closes_at":null,"multi":false}},"poll":{"counts":[2,5],"voted":[1]}}"""
    )

    val card = result.toCard()!!
    assertThat(card.quoteOf).isEqualTo("q")
    assertThat(card.poll?.options).isEqualTo(listOf("A", "B"))
    assertThat(card.pollState?.counts).isEqualTo(listOf(2, 5))
    assertThat(card.pollState?.voted).isEqualTo(listOf(1))
  }

  @Test
  fun `held sits outside the card`() {
    val result = FeedJson.decodeFromString(FeedCardResult.serializer(), """{"id":"c","state":"ok","card":{"kind":"comment","text":"hi"},"held":true}""")

    assertThat(result.toCard()!!.held).isEqualTo(true)
  }

  @Test
  fun `shareable sits outside the card and is off when missing`() {
    val shared = FeedJson.decodeFromString(FeedCardResult.serializer(), """{"id":"p","state":"ok","card":{"kind":"post","text":"hi"},"shareable":true}""")
    val plain = FeedJson.decodeFromString(FeedCardResult.serializer(), """{"id":"p","state":"ok","card":{"kind":"post","text":"hi"}}""")

    assertThat(shared.toCard()!!.shareable).isEqualTo(true)
    assertThat(plain.toCard()!!.shareable).isEqualTo(false)
  }

  @Test
  fun `pinned and hidden likes come from the outer card and stats`() {
    val result = FeedJson.decodeFromString(
      FeedCardResult.serializer(),
      """{"id":"p","state":"ok","card":{"kind":"post"},"stats":{"likes":0,"likes_hidden":true,"comments":1,"reposts":0},"pinned":true}"""
    )

    val card = result.toCard()!!
    assertThat(card.pinned).isEqualTo(true)
    assertThat(card.stats.likeCountHidden).isEqualTo(true)
  }

  @Test
  fun `author comes from the outer me and is off when missing`() {
    val mine = FeedJson.decodeFromString(FeedCardResult.serializer(), """{"id":"p","state":"ok","card":{"kind":"post"},"me":{"liked":false,"saved":false,"author":true}}""")
    val theirs = FeedJson.decodeFromString(FeedCardResult.serializer(), """{"id":"p","state":"ok","card":{"kind":"post"},"me":{"liked":true}}""")

    assertThat(mine.toCard()!!.viewer.isAuthor).isTrue()
    assertThat(theirs.toCard()!!.viewer.isAuthor).isFalse()
  }

  @Test
  fun `profile says whether I muted or hid from them`() {
    val profile = FeedJson.decodeFromString(
      FeedProfilePage.serializer(),
      """{"id":"p1","display_name":"山风","me":{"self":false,"follow":"none","muted":true,"hidden_from":true}}"""
    )

    assertThat(profile.me.muted).isTrue()
    assertThat(profile.me.hiddenFrom).isTrue()
  }

  @Test
  fun `refresh puts new cards on top without duplicates`() {
    val merged = mergeRefresh(incoming = listOf(card("c"), card("a")), existing = listOf(card("a"), card("b")))

    assertThat(merged.map { it.id }).containsExactly("c", "a", "b")
  }

  @Test
  fun `next page appends only unseen cards`() {
    val merged = appendPage(existing = listOf(card("a"), card("b")), incoming = listOf(card("b"), card("c")))

    assertThat(merged.map { it.id }).containsExactly("a", "b", "c")
  }

  @Test
  fun `like changes count by one and never below zero`() {
    val liked = card("a", likes = 4).withLiked(true)
    assertThat(liked.viewer.liked).isTrue()
    assertThat(liked.stats.likes).isEqualTo(5)

    val unliked = card("a", likes = 0, liked = true).withLiked(false)
    assertThat(unliked.stats.likes).isEqualTo(0)
  }

  @Test
  fun `like to the same state is a no-op`() {
    val original = card("a", likes = 2, liked = true)

    assertThat(original.withLiked(true)).isSameInstanceAs(original)
  }

  @Test
  fun `image ratio is clamped between 3 to 4 and 16 to 9`() {
    assertThat(clampedRatio(FeedMedia(key = "k", w = 100, h = 1000))).isEqualTo(3f / 4f)
    assertThat(clampedRatio(FeedMedia(key = "k", w = 4000, h = 100))).isEqualTo(16f / 9f)
    assertThat(clampedRatio(FeedMedia(key = "k", w = 1000, h = 1000))).isEqualTo(1f)
    assertThat(clampedRatio(FeedMedia(key = "k", w = 0, h = 0))).isEqualTo(1f)
  }

  @Test
  fun `bad timestamps do not throw`() {
    assertThat(parseInstantMillis("2026-10-13T08:00:00Z")).isEqualTo(1_791_878_400_000L)
    assertThat(parseInstantMillis("yesterday")).isNull()
  }

  private fun card(id: String, likes: Int = 0, liked: Boolean = false): FeedCard {
    return FeedCard(id = id, kind = FeedCard.KIND_POST, stats = FeedStats(likes = likes), viewer = FeedViewer(liked = liked))
  }

  // 内容引擎 main 66ce9cb 的 moderation/worker.go card()：topics 是 slug 字符串数组，没有话题时不出现。

  @Test
  fun `card topics decode from engine slug strings`() {
    val card = FeedJson.decodeFromString(FeedCard.serializer(), """{"kind":"post","text":"hi","topics":["food","no-such-topic",""]}""")

    assertThat(card.text).isEqualTo("hi")
    assertThat(card.topics).containsExactly(FeedTopic("food"), FeedTopic("no-such-topic"))
  }

  @Test
  fun `card topics accept objects and drop junk items without failing the card`() {
    val card = FeedJson.decodeFromString(
      FeedCard.serializer(),
      """{"kind":"post","text":"hi","topics":[{"slug":"hiking","label":"徒步"},{"slug":"food"},7,{"label":"x"},null,["a"],"news"]}"""
    )

    assertThat(card.text).isEqualTo("hi")
    assertThat(card.topics).containsExactly(FeedTopic("hiking", "徒步"), FeedTopic("food"), FeedTopic("news"))
  }

  @Test
  fun `card topics missing or not an array means no topics`() {
    assertThat(FeedJson.decodeFromString(FeedCard.serializer(), """{"kind":"post"}""").topics).isEqualTo(emptyList())
    assertThat(FeedJson.decodeFromString(FeedCard.serializer(), """{"kind":"post","topics":"food"}""").topics).isEqualTo(emptyList())
    assertThat(FeedJson.decodeFromString(FeedCard.serializer(), """{"kind":"post","topics":null}""").topics).isEqualTo(emptyList())
  }

  @Test
  fun `card result with engine string topics still decodes the whole batch`() {
    val result = FeedJson.decodeFromString(
      FeedCardResult.serializer(),
      """{"id":"x","state":"ok","card":{"kind":"post","text":"hi","topics":["food"]}}"""
    )

    val card = result.toCard()!!
    assertThat(card.id).isEqualTo("x")
    assertThat(card.topics).containsExactly(FeedTopic("food"))
  }

  @Test
  fun `card topics round trip through encode for the detail intent`() {
    val card = FeedCard(kind = FeedCard.KIND_POST, topics = listOf(FeedTopic("hiking", "徒步"), FeedTopic("food")))
    val json = FeedJson.encodeToString(FeedCard.serializer(), card)

    assertThat(FeedJson.decodeFromString(FeedCard.serializer(), json).topics).isEqualTo(card.topics)
  }
}
