/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.people

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
import org.thoughtcrime.securesms.feed.net.FeedBlockEntry
import org.thoughtcrime.securesms.feed.net.FeedFollowRequest
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedPeopleViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(
    val requests: List<FeedFollowRequest> = emptyList(),
    val blocks: List<FeedBlockEntry> = emptyList(),
    var error: Exception? = null,
    var loadError: Exception? = null
  ) : FeedSource by EmptyFeedSource {
    val answers = mutableListOf<Pair<String, Boolean>>()
    val unblocked = mutableListOf<FeedBlockEntry>()

    override suspend fun followRequests(): List<FeedFollowRequest> {
      loadError?.let { throw it }
      return requests
    }

    override suspend fun blocks(): List<FeedBlockEntry> = blocks

    override suspend fun answerFollowRequest(profileId: String, approve: Boolean) {
      answers += profileId to approve
      error?.let { throw it }
    }

    override suspend fun unblock(entry: FeedBlockEntry) {
      unblocked += entry
      error?.let { throw it }
    }
  }

  private fun person(id: String) = FeedPersonSummary(id = id, displayName = "P$id")

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `approving removes the request and says so`() {
    val source = FakeSource(requests = listOf(FeedFollowRequest(person("a")), FeedFollowRequest(person("b"))))
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.FOLLOW_REQUESTS, source, dispatcher)

    viewModel.answer(viewModel.state.value.rows.first(), approve = true)

    assertThat(source.answers).containsExactly("a" to true)
    assertThat(viewModel.state.value.rows.map { it.key }).containsExactly("b")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPeopleState.Notice.APPROVED)
  }

  @Test
  fun `a failed answer puts the row back in place`() {
    val source = FakeSource(requests = listOf(FeedFollowRequest(person("a")), FeedFollowRequest(person("b"))), error = IOException())
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.FOLLOW_REQUESTS, source, dispatcher)

    viewModel.answer(viewModel.state.value.rows.first(), approve = false)

    assertThat(viewModel.state.value.rows.map { it.key }).containsExactly("a", "b")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPeopleState.Notice.FAILED)
  }

  @Test
  fun `a request the other side already withdrew counts as handled`() {
    val source = FakeSource(requests = listOf(FeedFollowRequest(person("a"))), error = FeedApiException(404, "not_found"))
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.FOLLOW_REQUESTS, source, dispatcher)

    viewModel.answer(viewModel.state.value.rows.first(), approve = false)

    assertThat(viewModel.state.value.rows).isEmpty()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPeopleState.Notice.DECLINED)
  }

  @Test
  fun `blocked list unblocks topics as well as people`() {
    val topic = FeedBlockEntry(type = "topic", id = "t-1")
    val source = FakeSource(blocks = listOf(FeedBlockEntry(type = "author", id = "a", profile = person("a")), topic))
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.BLOCKED, source, dispatcher)

    viewModel.unblock(viewModel.state.value.rows[1])

    assertThat(source.unblocked).containsExactly(topic)
    assertThat(viewModel.state.value.rows.map { it.key }).containsExactly("author/a")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPeopleState.Notice.UNBLOCKED)
  }

  @Test
  fun `load failure shows retry`() {
    val source = FakeSource(loadError = IOException())
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.FOLLOW_REQUESTS, source, dispatcher)

    assertThat(viewModel.state.value.failed).isTrue()
  }
}
