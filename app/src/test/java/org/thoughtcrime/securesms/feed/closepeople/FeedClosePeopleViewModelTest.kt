/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.closepeople

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
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
import org.thoughtcrime.securesms.feed.net.FeedClosePeopleResponse
import org.thoughtcrime.securesms.feed.net.FeedClosePersonEntry
import org.thoughtcrime.securesms.feed.net.FeedSearchPage
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedClosePeopleViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private val anan = FeedClosePersonEntry(id = "p1", displayName = "Anan", state = FeedClosePersonEntry.STATE_ACCEPTED)
  private val bo = FeedClosePersonEntry(id = "p2", displayName = "Bo")
  private val requester = FeedClosePersonEntry(id = "r1", displayName = "Ruo")

  private inner class FakeSource : FeedSource by EmptyFeedSource {
    var addError: Exception? = null
    var removeError: Exception? = null
    var answerError: Exception? = null
    val added = mutableListOf<String>()
    val answers = mutableListOf<Pair<String, Boolean>>()
    var users = listOf(FeedSearchUser(id = "p1", displayName = "Anan"), FeedSearchUser(id = "p9", displayName = "Jiu", handle = "jiu"))

    override suspend fun closePeople() = FeedClosePeopleResponse(mine = listOf(anan, bo), requests = listOf(requester))
    override suspend fun search(query: String, scope: String, cursor: String?) = FeedSearchPage(users = users)
    override suspend fun addClosePerson(profileId: String): String {
      addError?.let { throw it }
      added += profileId
      return FeedClosePersonEntry.STATE_PENDING
    }
    override suspend fun removeClosePerson(profileId: String) {
      removeError?.let { throw it }
    }
    override suspend fun answerClosePerson(ownerProfileId: String, accept: Boolean) {
      answerError?.let { throw it }
      answers += ownerProfileId to accept
    }
  }

  private val source = FakeSource()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = FeedClosePeopleViewModel(source = source, ioDispatcher = dispatcher, debounceMillis = 0)

  @Test
  fun `search hides people already picked and adding clears the search`() {
    val viewModel = viewModel()
    viewModel.onQueryChanged("a")
    assertThat(viewModel.state.value.addableResults.map { it.id }).containsExactly("p9")

    viewModel.add(viewModel.state.value.addableResults.single())

    assertThat(source.added).containsExactly("p9")
    assertThat(viewModel.state.value.mine.map { it.id }).containsExactly("p1", "p2", "p9")
    assertThat(viewModel.state.value.query).isEqualTo("")
    assertThat(viewModel.state.value.canAdd).isFalse()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedClosePeopleState.Notice.ADDED)
  }

  @Test
  fun `full and not allowed are explained`() {
    val viewModel = viewModel()
    source.addError = FeedApiException(409, code = "close_people_full")
    viewModel.add(FeedSearchUser(id = "p9", displayName = "Jiu"))
    assertThat(viewModel.state.value.notice).isEqualTo(FeedClosePeopleState.Notice.FULL)

    source.addError = FeedApiException(404, code = "not_found")
    viewModel.add(FeedSearchUser(id = "p8", displayName = "Ba"))
    assertThat(viewModel.state.value.notice).isEqualTo(FeedClosePeopleState.Notice.NOT_ALLOWED)
    assertThat(viewModel.state.value.mine.size).isEqualTo(2)
  }

  @Test
  fun `remove puts the person back when it fails`() {
    val viewModel = viewModel()
    source.removeError = IOException("offline")
    viewModel.remove(anan)
    assertThat(viewModel.state.value.mine).containsExactly(anan, bo)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedClosePeopleState.Notice.FAILED)
  }

  @Test
  fun `accepting marks the request, declining removes it`() {
    val viewModel = viewModel()
    viewModel.answer(requester, accept = true)
    assertThat(viewModel.state.value.requests.single().accepted).isEqualTo(true)

    viewModel.answer(viewModel.state.value.requests.single(), accept = false)
    assertThat(viewModel.state.value.requests).isEmpty()
    assertThat(source.answers).containsExactly("r1" to true, "r1" to false)
  }

  @Test
  fun `failed answer is rolled back`() {
    val viewModel = viewModel()
    source.answerError = IOException("offline")
    viewModel.answer(requester, accept = false)
    assertThat(viewModel.state.value.requests).containsExactly(requester)
  }
}
