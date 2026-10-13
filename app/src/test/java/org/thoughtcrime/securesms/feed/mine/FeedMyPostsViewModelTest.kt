/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.mine

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.EmptyFeedSource
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.net.FeedMyPost
import org.thoughtcrime.securesms.feed.net.FeedMyPostsPage
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedMyPostsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private fun post(id: String, state: String = FeedMyPost.STATE_PUBLISHED) = FeedMyPost(id = id, state = state, text = id)

  private class FakeSource(
    val pages: Map<String?, FeedMyPostsPage>,
    var failDelete: Boolean = false,
    var failAppeal: Boolean = false
  ) : FeedSource by EmptyFeedSource {
    val deleted = mutableListOf<String>()
    val appeals = mutableListOf<Pair<String, String>>()
    override suspend fun appeal(contentId: String, text: String) {
      if (failAppeal) throw IOException("offline")
      appeals += contentId to text
    }
    override suspend fun myPosts(before: String?): FeedMyPostsPage = pages[before] ?: throw IOException("no page")
    override suspend fun deletePost(contentId: String) {
      if (failDelete) throw IOException("offline")
      deleted += contentId
    }
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `loads the first page and pages with before`() {
    val source = FakeSource(
      mapOf(
        null to FeedMyPostsPage(posts = listOf(post("a"), post("b")), nextBefore = "b"),
        "b" to FeedMyPostsPage(posts = listOf(post("b"), post("c")))
      )
    )
    val viewModel = FeedMyPostsViewModel(source, dispatcher)

    viewModel.loadMore()

    assertThat(viewModel.state.value.posts.map { it.id }).containsExactly("a", "b", "c")
    assertThat(viewModel.state.value.nextBefore).isEqualTo(null)
    assertThat(viewModel.state.value.loading).isFalse()
  }

  @Test
  fun `failed load shows the error state`() {
    val viewModel = FeedMyPostsViewModel(FakeSource(emptyMap()), dispatcher)

    assertThat(viewModel.state.value.failed).isTrue()
    assertThat(viewModel.state.value.loadedOnce).isTrue()
  }

  @Test
  fun `delete removes the post`() {
    val source = FakeSource(mapOf(null to FeedMyPostsPage(posts = listOf(post("a"), post("b")))))
    val viewModel = FeedMyPostsViewModel(source, dispatcher)

    viewModel.delete(viewModel.state.value.posts.first())

    assertThat(source.deleted).containsExactly("a")
    assertThat(viewModel.state.value.posts.map { it.id }).containsExactly("b")
  }

  @Test
  fun `failed delete puts the post back where it was`() {
    val source = FakeSource(mapOf(null to FeedMyPostsPage(posts = listOf(post("a"), post("b"), post("c")))), failDelete = true)
    val viewModel = FeedMyPostsViewModel(source, dispatcher)

    viewModel.delete(viewModel.state.value.posts[1])

    assertThat(viewModel.state.value.posts.map { it.id }).containsExactly("a", "b", "c")
    assertThat(viewModel.state.value.deleteFailed).isTrue()
    viewModel.onDeleteFailureShown()
    assertThat(viewModel.state.value.deleteFailed).isFalse()
  }

  @Test
  fun `rule codes become user facing reasons`() {
    assertThat(rejectionReasonLabel("lexicon:gambling")).isEqualTo(R.string.TellomiFeedMine__reason_wording)
    assertThat(rejectionReasonLabel("lexicon")).isEqualTo(R.string.TellomiFeedMine__reason_wording)
    assertThat(rejectionReasonLabel("missing:[vendor]")).isEqualTo(R.string.TellomiFeedMine__reason_guidelines)
    assertThat(rejectionReasonLabel("")).isEqualTo(R.string.TellomiFeedMine__reason_guidelines)
  }

  @Test
  fun `engine reason codes map to their own wording, unknown ones to the guidelines`() {
    assertThat(rejectionReasonLabel("prohibited_words")).isEqualTo(R.string.TellomiFeedMine__reason_wording)
    assertThat(rejectionReasonLabel("review_unavailable")).isEqualTo(R.string.TellomiFeedMine__reason_review_unavailable)
    assertThat(rejectionReasonLabel("account_restricted")).isEqualTo(R.string.TellomiFeedMine__reason_account)
    assertThat(rejectionReasonLabel("community_guidelines")).isEqualTo(R.string.TellomiFeedMine__reason_guidelines)
    assertThat(rejectionReasonLabel("something_new")).isEqualTo(R.string.TellomiFeedMine__reason_guidelines)
  }

  @Test
  fun `appeal marks the post as under review`() {
    val rejected = post("r", FeedMyPost.STATE_REJECTED).copy(appealable = true)
    val source = FakeSource(mapOf(null to FeedMyPostsPage(posts = listOf(rejected))))
    val viewModel = FeedMyPostsViewModel(source, dispatcher)

    viewModel.appeal(rejected, "  只是分享  ")

    assertThat(source.appeals).containsExactly("r" to "只是分享")
    val after = viewModel.state.value.posts.single()
    assertThat(after.appealable).isFalse()
    assertThat(after.appealState).isEqualTo(FeedMyPost.APPEAL_RECEIVED)
    assertThat(viewModel.state.value.appealNotice).isEqualTo(AppealResult.SENT)
  }

  @Test
  fun `a failed appeal leaves the post appealable`() {
    val rejected = post("r", FeedMyPost.STATE_REJECTED).copy(appealable = true)
    val source = FakeSource(mapOf(null to FeedMyPostsPage(posts = listOf(rejected))), failAppeal = true)
    val viewModel = FeedMyPostsViewModel(source, dispatcher)

    viewModel.appeal(rejected, "只是分享")

    assertThat(viewModel.state.value.posts.single().appealable).isTrue()
    assertThat(viewModel.state.value.appealNotice).isEqualTo(AppealResult.FAILED)
  }
}
