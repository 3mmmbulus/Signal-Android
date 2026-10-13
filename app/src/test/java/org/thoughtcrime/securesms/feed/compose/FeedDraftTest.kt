/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.net.Uri
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.thoughtcrime.securesms.feed.FeedTab
import org.thoughtcrime.securesms.feed.SampleFeedSource
import org.thoughtcrime.securesms.feed.net.FeedCreatePostRequest
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.feed.net.FeedLimits
import org.thoughtcrime.securesms.feed.toRequest

class FeedDraftTest {

  private val limits = FeedLimits(postTextChars = 5, postImages = 2, articleChars = 8)

  @Test
  fun `empty draft cannot be posted`() {
    assertThat(FeedDraft().problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.EMPTY)
    assertThat(FeedDraft(text = "   ").problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.EMPTY)
  }

  @Test
  fun `images alone are enough for a post`() {
    assertThat(FeedDraft(images = listOf(mockk<Uri>())).problem(limits, minorMode = false)).isNull()
  }

  @Test
  fun `text limit counts code points and follows the server limit`() {
    assertThat(FeedDraft(text = "一二三四五").problem(limits, minorMode = false)).isNull()
    assertThat(FeedDraft(text = "一二三四五六").problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.TOO_LONG)
    // 一个 emoji 是 2 个 UTF-16 单元、1 个码点
    assertThat(FeedDraft(text = "😀😀😀😀😀").problem(limits, minorMode = false)).isNull()
  }

  @Test
  fun `article needs a title and has its own limit`() {
    assertThat(FeedDraft(kind = FeedDraftKind.ARTICLE, text = "正文").problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.MISSING_TITLE)
    assertThat(FeedDraft(kind = FeedDraftKind.ARTICLE, title = "标题", text = "一二三四五六七").problem(limits, minorMode = false)).isNull()
    assertThat(FeedDraft(kind = FeedDraftKind.ARTICLE, title = "标题", text = "一二三四五六七八九").problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.TOO_LONG)
  }

  @Test
  fun `article does not carry images`() {
    val draft = FeedDraft(kind = FeedDraftKind.ARTICLE, title = "标题", images = listOf(mockk<Uri>()))

    assertThat(draft.attachedImages).isEmpty()
    assertThat(draft.problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.EMPTY)
  }

  @Test
  fun `too many images`() {
    val draft = FeedDraft(text = "a", images = List(3) { mockk<Uri>() })

    assertThat(draft.problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.TOO_MANY_IMAGES)
  }

  @Test
  fun `minors only get friends and only me`() {
    assertThat(FeedVisibility.allowed(minorMode = true)).containsExactly(FeedVisibility.FRIENDS, FeedVisibility.ONLY_ME)
    assertThat(FeedVisibility.default(minorMode = true)).isEqualTo(FeedVisibility.FRIENDS)
    assertThat(FeedDraft(text = "a").problem(limits, minorMode = true)).isEqualTo(FeedDraft.Problem.VISIBILITY_NOT_ALLOWED)
  }

  @Test
  fun `include and exclude cannot be posted without a list`() {
    assertThat(FeedDraft(text = "a", visibility = FeedVisibility.INCLUDE).problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.MISSING_AUDIENCE)
    assertThat(FeedDraft(text = "a", visibility = FeedVisibility.EXCLUDE).problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.MISSING_AUDIENCE)
  }

  @Test
  fun `request has only the fields the engine accepts`() {
    val request = FeedDraft(text = "  hi  ", visibility = FeedVisibility.ONLY_ME).toRequest()

    assertThat(FeedJson.encodeToString(FeedCreatePostRequest.serializer(), request))
      .isEqualTo("""{"kind":"post","text":"hi","visibility":"only_me","comments_closed":false}""")
  }

  @Test
  fun `article request carries the title`() {
    val request = FeedDraft(kind = FeedDraftKind.ARTICLE, title = " 标题 ", text = "正文").toRequest()

    assertThat(request.kind).isEqualTo("article")
    assertThat(request.title).isEqualTo("标题")
  }

  @Test
  fun `sample source puts my post on top and dedupes retries`() = runBlocking {
    val nonce = "test-${System.nanoTime()}"
    val first = SampleFeedSource.publish(FeedDraft(text = "第一次发"), nonce)
    val retry = SampleFeedSource.publish(FeedDraft(text = "第一次发"), nonce)

    assertThat(retry).isEqualTo(first)
    val top = SampleFeedSource.timeline(FeedTab.FOLLOWING, cursor = null).entries.first().card
    assertThat(top.id).isEqualTo(first.id)
    assertThat(top.text).isEqualTo("第一次发")
  }

  @Test
  fun `include and exclude need between one and 500 people`() {
    val one = listOf(FeedAudienceMember(1, "aci-1"))
    assertThat(FeedDraft(text = "a", visibility = FeedVisibility.INCLUDE, audience = one).problem(limits, minorMode = false)).isNull()
    val tooMany = (1..FeedDraft.MAX_AUDIENCE + 1).map { FeedAudienceMember(it.toLong(), "aci-$it") }
    assertThat(FeedDraft(text = "a", visibility = FeedVisibility.EXCLUDE, audience = tooMany).problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.AUDIENCE_TOO_LARGE)
    // 别的档位不看名单
    assertThat(FeedDraft(text = "a", visibility = FeedVisibility.PUBLIC, audience = tooMany).problem(limits, minorMode = false)).isNull()
  }
}
