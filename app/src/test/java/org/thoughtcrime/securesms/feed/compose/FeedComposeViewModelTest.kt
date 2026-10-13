/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import assertk.assertThat
import assertk.assertions.containsExactly
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
import org.thoughtcrime.securesms.feed.EmptyFeedSource
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedConfig
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FeedComposeViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(val editError: Exception? = null) : FeedSource by EmptyFeedSource {
    val edits = mutableListOf<Pair<String, FeedDraft>>()
    override suspend fun config(): FeedConfig = FeedConfig(enabled = true, minorMode = true)
    override suspend fun editPost(contentId: String, draft: FeedDraft): FeedCreatePostResponse {
      editError?.let { throw it }
      edits += contentId to draft
      return FeedCreatePostResponse(id = contentId, state = FeedCreatePostResponse.STATE_PENDING, version = 2)
    }
  }

  private val target = FeedEditTarget(id = "p1", kind = "article", title = "标题", text = "正文", publishedAt = null)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `edit starts from the post and needs a change`() {
    val viewModel = FeedComposeViewModel(editTarget = target, source = FakeSource(), ioDispatcher = dispatcher)

    val state = viewModel.state.value
    assertThat(state.draft.kind).isEqualTo(FeedDraftKind.ARTICLE)
    assertThat(state.draft.title).isEqualTo("标题")
    assertThat(state.problem).isEqualTo(FeedDraft.Problem.UNCHANGED)
    assertThat(state.hasUnsavedChanges).isFalse()

    viewModel.onKindChanged(FeedDraftKind.POST)
    assertThat(viewModel.state.value.draft.kind).isEqualTo(FeedDraftKind.ARTICLE)
  }

  @Test
  fun `edit ignores visibility rules, even in minor mode`() {
    val source = FakeSource()
    val viewModel = FeedComposeViewModel(editTarget = target, source = source, ioDispatcher = dispatcher)

    viewModel.onTextChanged("新的正文")
    assertThat(viewModel.state.value.problem).isNull()
    viewModel.publish()

    assertThat(source.edits.map { it.first }).containsExactly("p1")
    assertThat(viewModel.state.value.publishedState).isEqualTo(FeedCreatePostResponse.STATE_PENDING)
  }

  @Test
  fun `closed window is its own message, not a generic failure`() {
    val viewModel = FeedComposeViewModel(editTarget = target, source = FakeSource(FeedApiException(409, "edit_window_closed")), ioDispatcher = dispatcher)

    viewModel.onTextChanged("新的正文")
    viewModel.publish()

    assertThat(viewModel.state.value.editWindowClosed).isTrue()
    assertThat(viewModel.state.value.failed).isFalse()
  }

  @Test
  fun `missing profile asks to set one up, not a generic failure`() {
    val viewModel = FeedComposeViewModel(editTarget = target, source = FakeSource(FeedApiException(409, "profile_required")), ioDispatcher = dispatcher)

    viewModel.onTextChanged("新的正文")
    viewModel.publish()

    assertThat(viewModel.state.value.profileRequired).isTrue()
    assertThat(viewModel.state.value.failed).isFalse()
    viewModel.onProfileRequiredShown()
    assertThat(viewModel.state.value.profileRequired).isFalse()
  }

  @Test
  fun `window check uses the publish time`() {
    val published = Instant.parse("2026-10-13T12:00:00Z")
    val card = target.copy(publishedAt = published.toString())

    assertThat(card.isWithinWindow(published.plusSeconds(900).toEpochMilli(), 900)).isTrue()
    assertThat(card.isWithinWindow(published.plusSeconds(901).toEpochMilli(), 900)).isFalse()
    assertThat(target.isWithinWindow(0, 900)).isTrue()
  }
}
