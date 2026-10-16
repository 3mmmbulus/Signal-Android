/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.feed.compose.FeedComposeViewModel
import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedDraftKind
import org.thoughtcrime.securesms.feed.compose.InMemoryFeedArticleDrafts
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailViewModel
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedKeywordRequest
import org.thoughtcrime.securesms.feed.net.FeedMyPost
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate
import org.thoughtcrime.securesms.feed.profile.FeedProfileViewModel

/**
 * 输入限制接进四个输入框的状态层（评论 500、长文标题 100、昵称 32、简介 200）：到上限就拦、显示剩余、粘贴按码点截、
 * 输入法组字中放行（暂时超限，发送 / 保存 / 发布不可用）、组字结束再截一次。和 iOS 的 FeedPostDetailStore / FeedComposeStore / FeedProfileEditStore 同一套行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedInputLimitViewModelsTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" // 7 个码点
  private val grin = "\uD83D\uDE00"

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  // ---------- 对得上已有的上限 ----------

  @Test
  fun `named limits equal the constants the rest of the feed already uses`() {
    assertThat(FeedInputLimit.PROFILE_NAME).isEqualTo(FeedMyProfile.MAX_NAME_CHARS)
    assertThat(FeedInputLimit.PROFILE_BIO).isEqualTo(FeedMyProfile.MAX_BIO_CHARS)
    assertThat(FeedInputLimit.APPEAL_REASON).isEqualTo(FeedMyPost.MAX_APPEAL_CHARS)
    assertThat(FeedInputLimit.KEYWORD).isEqualTo(FeedKeywordRequest.MAX_CHARS)
    assertThat(FeedInputLimit.POLL_OPTION).isEqualTo(org.thoughtcrime.securesms.feed.compose.FeedDraftPoll.OPTION_MAX_CHARS)
    assertThat(FeedInputLimit.POST_TEXT).isEqualTo(org.thoughtcrime.securesms.feed.net.FeedLimits().postTextChars)
    assertThat(FeedInputLimit.ARTICLE_TEXT).isEqualTo(org.thoughtcrime.securesms.feed.net.FeedLimits().articleChars)
  }

  // ---------- 昵称 / 简介（FeedProfileViewModel） ----------

  private class ProfileSource(val profile: FeedMyProfile?) : FeedSource by EmptyFeedSource {
    val saves = mutableListOf<FeedProfileUpdate>()
    override suspend fun myProfile(): FeedMyProfile? = profile
    override suspend fun saveProfile(update: FeedProfileUpdate): FeedMyProfile {
      saves += update
      return FeedMyProfile(id = "x", displayName = update.displayName ?: "", bio = update.bio)
    }
  }

  private fun profileModel(source: ProfileSource = ProfileSource(null), suggested: String = "") = FeedProfileViewModel(suggested, source, dispatcher)

  @Test
  fun `profile name blocks at 32 code points and shows the remaining count`() {
    val viewModel = profileModel()
    viewModel.onNameChanged("a".repeat(31))
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(1)
    assertThat(viewModel.state.value.nameLimitHit).isFalse()

    viewModel.onNameChanged("a".repeat(32))
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(0)

    viewModel.onNameChanged("a".repeat(33)) // 再敲一个字
    assertThat(viewModel.state.value.name).isEqualTo("a".repeat(32))
    assertThat(viewModel.state.value.nameLimitHit).isTrue()
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(0)

    viewModel.onNameChanged("a".repeat(31)) // 删一个字，正常输入，提示清掉
    assertThat(viewModel.state.value.nameLimitHit).isFalse()
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(1)
  }

  @Test
  fun `profile name typed in the middle at the limit is blocked and the end is kept`() {
    val viewModel = profileModel()
    viewModel.onNameChanged("a".repeat(16) + "b".repeat(16))
    viewModel.onNameChanged("a".repeat(16) + "X" + "b".repeat(16))
    assertThat(viewModel.state.value.name).isEqualTo("a".repeat(16) + "b".repeat(16))
  }

  @Test
  fun `profile name paste is clipped by code point and never splits an emoji`() {
    val viewModel = profileModel()
    viewModel.onNameChanged(family.repeat(5)) // 35 个码点，上限 32：放 4 个（28），第 5 个放不下整个去掉
    assertThat(viewModel.state.value.name).isEqualTo(family.repeat(4))
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(4)
    assertThat(viewModel.state.value.nameLimitHit).isTrue()

    val emoji = profileModel()
    emoji.onNameChanged(grin.repeat(40)) // 😀 UTF-16 占 2 个单元，按码点只截 32 个
    assertThat(emoji.state.value.name).isEqualTo(grin.repeat(32))
  }

  @Test
  fun `profile bio blocks at 200 and clips a paste`() {
    val viewModel = profileModel()
    viewModel.onBioChanged("b".repeat(300))
    assertThat(viewModel.state.value.bio).isEqualTo("b".repeat(200))
    assertThat(viewModel.state.value.bioRemaining).isEqualTo(0)
    assertThat(viewModel.state.value.bioLimitHit).isTrue()
    assertThat(viewModel.state.value.nameLimitHit).isFalse()

    viewModel.onBioChanged("b".repeat(200) + "c")
    assertThat(viewModel.state.value.bio).isEqualTo("b".repeat(200))
  }

  @Test
  fun `profile name while composing passes through, save is disabled, and the text is cut once when composition ends`() {
    val source = ProfileSource(null)
    val viewModel = profileModel(source)
    viewModel.onNameChanged("a".repeat(32))

    viewModel.onNameChanged("a".repeat(32) + "nihao", composing = true)
    assertThat(viewModel.state.value.name).isEqualTo("a".repeat(32) + "nihao")
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(-5)
    assertThat(viewModel.state.value.canSave).isFalse()
    viewModel.save()
    assertThat(source.saves).isEmpty()

    viewModel.onNameChanged("a".repeat(32) + "nihao", composing = false) // 组字结束
    assertThat(viewModel.state.value.name).isEqualTo("a".repeat(32))
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(0)
    assertThat(viewModel.state.value.canSave).isTrue()
    viewModel.save()
    assertThat(source.saves).containsExactly(FeedProfileUpdate(displayName = "a".repeat(32), bio = ""))
  }

  @Test
  fun `profile bio over the limit while composing also disables save`() {
    val viewModel = profileModel()
    viewModel.onNameChanged("小满")
    assertThat(viewModel.state.value.canSave).isTrue()
    viewModel.onBioChanged("b".repeat(200) + "x", composing = true)
    assertThat(viewModel.state.value.bioRemaining).isEqualTo(-1)
    assertThat(viewModel.state.value.canSave).isFalse()
  }

  @Test
  fun `profile remaining counts code points not utf16 units`() {
    val viewModel = profileModel()
    viewModel.onNameChanged(grin + family)
    assertThat(viewModel.state.value.nameRemaining).isEqualTo(32 - 8)
  }

  // ---------- 评论（FeedPostDetailViewModel） ----------

  private val post = FeedCard(id = "p", kind = FeedCard.KIND_POST, text = "old")

  private class CommentSource : FeedSource by EmptyFeedSource {
    val posted = mutableListOf<String>()
    override suspend fun postComment(postId: String, text: String, parentId: String?): String {
      posted += text
      return FeedCreatePostResponse.STATE_PENDING
    }
  }

  @Test
  fun `comment blocks at 500 code points and shows the remaining count`() {
    val viewModel = FeedPostDetailViewModel(post, CommentSource(), dispatcher)
    viewModel.onDraftChanged("a".repeat(499))
    assertThat(viewModel.state.value.draftRemaining).isEqualTo(1)
    viewModel.onDraftChanged("a".repeat(500))
    assertThat(viewModel.state.value.draftRemaining).isEqualTo(0)
    assertThat(viewModel.state.value.draftLimitHit).isFalse()

    viewModel.onDraftChanged("a".repeat(501))
    assertThat(viewModel.state.value.draft).isEqualTo("a".repeat(500))
    assertThat(viewModel.state.value.draftLimitHit).isTrue()
    assertThat(viewModel.state.value.canSend).isTrue()

    viewModel.onDraftChanged("a".repeat(499))
    assertThat(viewModel.state.value.draftLimitHit).isFalse()
  }

  @Test
  fun `comment of 500 emoji is accepted whole, though it is 1000 utf16 units, and is sent as typed`() {
    val source = CommentSource()
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)
    viewModel.onDraftChanged(grin.repeat(500))
    assertThat(viewModel.state.value.draft).isEqualTo(grin.repeat(500))
    assertThat(viewModel.state.value.draftRemaining).isEqualTo(0)
    viewModel.send()
    assertThat(source.posted).containsExactly(grin.repeat(500))
  }

  @Test
  fun `comment paste is clipped by code point and never splits an emoji`() {
    val viewModel = FeedPostDetailViewModel(post, CommentSource(), dispatcher)
    viewModel.onDraftChanged("x".repeat(495))
    viewModel.onDraftChanged("x".repeat(495) + family) // 剩 5 个位置，来了个 7 码点的：一个字都放不下 → 拦下
    assertThat(viewModel.state.value.draft).isEqualTo("x".repeat(495))
    assertThat(viewModel.state.value.draftLimitHit).isTrue()

    viewModel.onDraftChanged("x".repeat(495) + grin.repeat(20)) // 放得下 5 个
    assertThat(viewModel.state.value.draft).isEqualTo("x".repeat(495) + grin.repeat(5))
    assertThat(viewModel.state.value.draftRemaining).isEqualTo(0)
  }

  @Test
  fun `comment while composing passes through, send is disabled, and the text is cut once when composition ends`() {
    val source = CommentSource()
    val viewModel = FeedPostDetailViewModel(post, source, dispatcher)
    viewModel.onDraftChanged("a".repeat(500))

    viewModel.onDraftChanged("a".repeat(500) + "nihao", composing = true)
    assertThat(viewModel.state.value.draftRemaining).isEqualTo(-5)
    assertThat(viewModel.state.value.canSend).isFalse()
    viewModel.send()
    assertThat(source.posted).isEmpty()

    viewModel.onDraftChanged("a".repeat(500) + "nihao", composing = false)
    assertThat(viewModel.state.value.draft).isEqualTo("a".repeat(500))
    assertThat(viewModel.state.value.canSend).isTrue()
    viewModel.send()
    assertThat(source.posted).containsExactly("a".repeat(500))
  }

  // ---------- 长文标题（FeedComposeViewModel / FeedDraft） ----------

  private class ComposeSource : FeedSource by EmptyFeedSource {
    val published = mutableListOf<FeedDraft>()
    override suspend fun config(): FeedConfig = FeedConfig(enabled = true)
    override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse {
      published += draft
      return FeedCreatePostResponse(id = "new", state = FeedCreatePostResponse.STATE_PENDING)
    }
  }

  private fun articleModel(source: ComposeSource = ComposeSource()): FeedComposeViewModel {
    val viewModel = FeedComposeViewModel(source = source, ioDispatcher = dispatcher, articleDrafts = InMemoryFeedArticleDrafts())
    viewModel.onKindChanged(FeedDraftKind.ARTICLE)
    viewModel.onTextChanged("正文")
    return viewModel
  }

  @Test
  fun `article title blocks at 100 code points and shows the remaining count`() {
    val viewModel = articleModel()
    viewModel.onTitleChanged("t".repeat(99))
    assertThat(viewModel.state.value.titleRemaining).isEqualTo(1)
    viewModel.onTitleChanged("t".repeat(100))
    assertThat(viewModel.state.value.titleRemaining).isEqualTo(0)
    assertThat(viewModel.state.value.titleLimitHit).isFalse()

    viewModel.onTitleChanged("t".repeat(101))
    assertThat(viewModel.state.value.draft.title).isEqualTo("t".repeat(100))
    assertThat(viewModel.state.value.titleLimitHit).isTrue()
    assertThat(viewModel.state.value.problem).isNull()

    viewModel.onTitleChanged("t".repeat(99))
    assertThat(viewModel.state.value.titleLimitHit).isFalse()
  }

  @Test
  fun `article title paste is clipped by code point and never splits an emoji`() {
    val viewModel = articleModel()
    viewModel.onTitleChanged(family.repeat(15)) // 105 个码点
    assertThat(viewModel.state.value.draft.title).isEqualTo(family.repeat(14)) // 98，第 15 个放不下
    assertThat(viewModel.state.value.titleRemaining).isEqualTo(2)

    val emoji = articleModel()
    emoji.onTitleChanged(grin.repeat(150))
    assertThat(emoji.state.value.draft.title).isEqualTo(grin.repeat(100))
  }

  @Test
  fun `article title while composing passes through, publish is blocked as too long, and the title is cut once when composition ends`() {
    val source = ComposeSource()
    val viewModel = articleModel(source)
    viewModel.onTitleChanged("t".repeat(100))

    viewModel.onTitleChanged("t".repeat(100) + "nihao", composing = true)
    assertThat(viewModel.state.value.titleRemaining).isEqualTo(-5)
    assertThat(viewModel.state.value.problem).isEqualTo(FeedDraft.Problem.TOO_LONG)
    viewModel.publish()
    assertThat(source.published).isEmpty()

    viewModel.onTitleChanged("t".repeat(100) + "nihao", composing = false)
    assertThat(viewModel.state.value.draft.title).isEqualTo("t".repeat(100))
    assertThat(viewModel.state.value.problem).isNull()
    viewModel.publish()
    assertThat(source.published.map { it.title }).containsExactly("t".repeat(100))
  }

  @Test
  fun `draft problem checks the title only for an article`() {
    val limits = org.thoughtcrime.securesms.feed.net.FeedLimits()
    val article = FeedDraft(kind = FeedDraftKind.ARTICLE, title = "t".repeat(100), text = "x")
    assertThat(article.problem(limits, false)).isNull()
    assertThat(article.copy(title = "t".repeat(101)).problem(limits, false)).isEqualTo(FeedDraft.Problem.TOO_LONG)
    assertThat(article.copy(title = grin.repeat(100)).problem(limits, false)).isNull() // 200 个 UTF-16 单元，100 个码点
    assertThat(article.copy(title = grin.repeat(101)).problem(limits, false)).isEqualTo(FeedDraft.Problem.TOO_LONG)
    assertThat(FeedDraft(kind = FeedDraftKind.POST, title = "t".repeat(101), text = "x").problem(limits, false)).isNull()
  }
}
