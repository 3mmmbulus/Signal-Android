/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.search

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedSearchPage
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedSearchViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(var error: Exception? = null) : FeedSource by EmptyFeedSource {
    val calls = mutableListOf<Triple<String, String, String?>>()

    override suspend fun search(query: String, scope: String, cursor: String?): FeedSearchPage {
      calls += Triple(query, scope, cursor)
      error?.let { throw it }
      return if (cursor == null) {
        FeedSearchPage(
          users = listOf(FeedSearchUser(id = "p1", handle = "yun", displayName = "Yun")),
          posts = listOf(FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "clouds")),
          cursor = "next"
        )
      } else {
        FeedSearchPage(posts = listOf(FeedCard(id = "c1", kind = FeedCard.KIND_POST), FeedCard(id = "c2", kind = FeedCard.KIND_POST)))
      }
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

  private fun viewModel(source: FeedSource, history: FeedSearchHistory = InMemoryFeedSearchHistory()) = FeedSearchViewModel(source = source, history = history, ioDispatcher = dispatcher, debounceMillis = 0)

  @Test
  fun `typing searches without saving history, submit saves it`() {
    val source = FakeSource()
    val history = InMemoryFeedSearchHistory()
    val vm = viewModel(source, history)

    vm.onQueryChange(" yun ")
    assertThat(source.calls).containsExactly(Triple("yun", FeedApi.SEARCH_ALL, null))
    assertThat(vm.state.value.results?.users?.single()?.id).isEqualTo("p1")
    assertThat(history.queries()).isEmpty()

    vm.submit()
    assertThat(history.queries()).containsExactly("yun")
    assertThat(vm.state.value.recentQueries).containsExactly("yun")
  }

  @Test
  fun `clearing the query drops the results`() {
    val vm = viewModel(FakeSource())

    vm.onQueryChange("yun")
    vm.onQueryChange("")

    assertThat(vm.state.value.results).isNull()
  }

  @Test
  fun `changing scope searches again and paging skips duplicates`() {
    val source = FakeSource()
    val vm = viewModel(source)
    vm.onQueryChange("yun")

    vm.selectScope(FeedApi.SEARCH_POSTS)
    vm.loadMore()

    assertThat(source.calls.map { it.second to it.third }).containsExactly(
      FeedApi.SEARCH_ALL to null,
      FeedApi.SEARCH_POSTS to null,
      FeedApi.SEARCH_POSTS to "next"
    )
    assertThat(vm.state.value.results?.posts?.map { it.id }).isEqualTo(listOf("c1", "c2"))
    assertThat(vm.state.value.results?.cursor).isNull()
  }

  @Test
  fun `opening a person remembers them and the query`() {
    val history = InMemoryFeedSearchHistory()
    val vm = viewModel(FakeSource(), history)
    vm.onQueryChange("yun")

    vm.onPersonOpened(FeedPersonSummary(id = "p1", displayName = "Yun"))

    assertThat(history.people().map { it.id }).containsExactly("p1")
    assertThat(vm.state.value.recentPeople.map { it.id }).containsExactly("p1")
    assertThat(history.queries()).containsExactly("yun")
  }

  @Test
  fun `history keeps the newest query first without duplicates`() {
    val history = InMemoryFeedSearchHistory()
    history.addQuery("a")
    history.addQuery("b")
    history.addQuery("A")

    assertThat(history.queries()).containsExactly("A", "b")
  }

  @Test
  fun `failure shows retry`() {
    val vm = viewModel(FakeSource(error = IOException()))

    vm.onQueryChange("yun")

    assertThat(vm.state.value.failed).isTrue()
  }

  @Test
  fun `the query is capped at 50 code points and an emoji is never cut in half`() {
    val grin = "\uD83D\uDE00"
    val viewModel = viewModel(FakeSource())
    viewModel.onQueryChange(grin.repeat(51))

    assertThat(viewModel.state.value.query).isEqualTo(grin.repeat(50))
  }
}
