/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.link

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
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
import org.thoughtcrime.securesms.feed.net.FeedCard

@OptIn(ExperimentalCoroutinesApi::class)
class FeedPostLinkViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val id = "0f8e2c1a-3b4d-4e5f-8a9b-0c1d2e3f4a5b"

  private class FakeSource(var card: FeedCard? = null, var fail: Boolean = false) : FeedSource by EmptyFeedSource {
    val asked = mutableListOf<String>()
    override suspend fun card(contentId: String): FeedCard? {
      asked += contentId
      if (fail) error("offline")
      return card
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
  fun `a visible post opens`() {
    val card = FeedCard(id = id, kind = FeedCard.KIND_POST, text = "hi")
    val source = FakeSource(card)
    val viewModel = FeedPostLinkViewModel(id, feedEnabled = true, source = { source }, ioDispatcher = dispatcher)

    assertThat(viewModel.state.value).isEqualTo(FeedPostLinkViewModel.State.Ready(card))
    assertThat(source.asked).containsExactly(id)
  }

  @Test
  fun `unavailable, unknown kinds, bad links and a closed feed all read as gone without asking twice`() {
    val unavailable = FeedPostLinkViewModel(id, feedEnabled = true, source = { FakeSource(null) }, ioDispatcher = dispatcher)
    val story = FeedPostLinkViewModel(id, feedEnabled = true, source = { FakeSource(FeedCard(id = id, kind = "story")) }, ioDispatcher = dispatcher)
    val closedSource = FakeSource(FeedCard(id = id, kind = FeedCard.KIND_POST))
    val closed = FeedPostLinkViewModel(id, feedEnabled = false, source = { closedSource }, ioDispatcher = dispatcher)
    val noId = FeedPostLinkViewModel(null, feedEnabled = true, source = { closedSource }, ioDispatcher = dispatcher)

    listOf(unavailable, story, closed, noId).forEach { assertThat(it.state.value).isEqualTo(FeedPostLinkViewModel.State.Gone) }
    assertThat(closedSource.asked).isEqualTo(emptyList())
  }

  @Test
  fun `a network failure can be retried`() {
    val card = FeedCard(id = id, kind = FeedCard.KIND_POST, text = "hi")
    val source = FakeSource(card, fail = true)
    val viewModel = FeedPostLinkViewModel(id, feedEnabled = true, source = { source }, ioDispatcher = dispatcher)
    assertThat(viewModel.state.value).isEqualTo(FeedPostLinkViewModel.State.Failed)

    source.fail = false
    viewModel.load()
    assertThat(viewModel.state.value).isEqualTo(FeedPostLinkViewModel.State.Ready(card))
  }
}
