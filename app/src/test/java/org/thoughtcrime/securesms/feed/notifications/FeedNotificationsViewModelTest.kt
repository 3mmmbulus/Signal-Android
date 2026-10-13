/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.notifications

import assertk.assertThat
import assertk.assertions.containsExactly
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
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedNotification
import org.thoughtcrime.securesms.feed.net.FeedNotificationsResponse
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedRelation
import org.thoughtcrime.securesms.feed.net.FeedUnreadCounts
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedNotificationsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(
    val pages: Map<String, List<FeedNotification>>,
    val unread: FeedUnreadCounts = FeedUnreadCounts(),
    var deleteError: Exception? = null
  ) : FeedSource by EmptyFeedSource {
    val readTabs = mutableListOf<String>()
    val profileLookups = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val mutes = mutableListOf<Pair<String, Boolean>>()
    var calls = 0

    override suspend fun notifications(tab: String, before: String?): FeedNotificationsResponse {
      calls++
      return FeedNotificationsResponse(notifications = if (before == null) pages[tab].orEmpty() else emptyList(), unread = unread)
    }

    override suspend fun markNotificationsRead(tab: String) {
      readTabs += tab
    }

    override suspend fun profile(profileId: String): FeedProfilePage {
      profileLookups += profileId
      return FeedProfilePage(id = profileId, displayName = "Name $profileId", me = FeedRelation())
    }

    override suspend fun deleteNotification(id: String) {
      deleteError?.let { throw it }
      deleted += id
    }

    override suspend fun muteNotifications(contentId: String, likesOnly: Boolean) {
      mutes += contentId to likesOnly
    }
  }

  private fun like(id: String, actor: String, content: String = "post") = FeedNotification(id = id, kind = FeedNotification.KIND_LIKE, actorId = actor, contentId = content, createdAt = "2026-10-13T1$id:00:00Z")

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `first load marks the tab read and looks up each actor once`() {
    val withActor = FeedNotification(
      id = "9",
      kind = FeedNotification.KIND_COMMENT,
      actorId = "c",
      contentId = "post",
      createdAt = "2026-10-13T09:00:00Z",
      actor = FeedPersonSummary(id = "c", displayName = "Carried")
    )
    val source = FakeSource(
      pages = mapOf(FeedApi.NOTIFICATIONS_INTERACTIONS to listOf(like("1", "a"), like("2", "b"), like("3", "a", "other"), withActor)),
      unread = FeedUnreadCounts(interactions = 3, system = 1)
    )
    val viewModel = FeedNotificationsViewModel(source = source, ioDispatcher = dispatcher)

    assertThat(source.readTabs).containsExactly(FeedApi.NOTIFICATIONS_INTERACTIONS)
    assertThat(viewModel.state.value.unread).isEqualTo(FeedUnreadCounts(interactions = 0, system = 1))
    assertThat(source.profileLookups).containsExactly("a", "b")
    assertThat(viewModel.state.value.people["a"]?.displayName).isEqualTo("Name a")
    assertThat(viewModel.state.value.people["c"]?.displayName).isEqualTo("Carried")
  }

  @Test
  fun `switching tabs loads each tab once`() {
    val source = FakeSource(pages = emptyMap())
    val viewModel = FeedNotificationsViewModel(source = source, ioDispatcher = dispatcher)

    viewModel.selectTab(FeedApi.NOTIFICATIONS_SYSTEM)
    viewModel.selectTab(FeedApi.NOTIFICATIONS_INTERACTIONS)
    viewModel.selectTab(FeedApi.NOTIFICATIONS_SYSTEM)

    assertThat(source.calls).isEqualTo(2)
    assertThat(source.readTabs).containsExactly()
  }

  @Test
  fun `deleting a merged like row deletes every notification in it`() {
    val source = FakeSource(pages = mapOf(FeedApi.NOTIFICATIONS_INTERACTIONS to listOf(like("1", "a"), like("2", "b"))))
    val viewModel = FeedNotificationsViewModel(source = source, ioDispatcher = dispatcher)
    val row = groupNotifications(viewModel.state.value.tab(FeedApi.NOTIFICATIONS_INTERACTIONS).items, java.time.Instant.parse("2026-10-13T20:00:00Z"), java.time.ZoneOffset.UTC).single().rows.single()

    viewModel.delete(row)

    assertThat(source.deleted).containsExactly("1", "2")
    assertThat(viewModel.state.value.tab(FeedApi.NOTIFICATIONS_INTERACTIONS).items.size).isEqualTo(0)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedNotificationsState.Notice.DELETED)
  }

  @Test
  fun `failed delete restores the rows`() {
    val source = FakeSource(pages = mapOf(FeedApi.NOTIFICATIONS_INTERACTIONS to listOf(like("1", "a"))), deleteError = IOException())
    val viewModel = FeedNotificationsViewModel(source = source, ioDispatcher = dispatcher)
    val row = groupNotifications(viewModel.state.value.tab(FeedApi.NOTIFICATIONS_INTERACTIONS).items, java.time.Instant.parse("2026-10-13T20:00:00Z"), java.time.ZoneOffset.UTC).single().rows.single()

    viewModel.delete(row)

    assertThat(viewModel.state.value.tab(FeedApi.NOTIFICATIONS_INTERACTIONS).items.map { it.id }).containsExactly("1")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedNotificationsState.Notice.FAILED)
  }

  @Test
  fun `muting likes only keeps the comments on that post`() {
    val comment = FeedNotification(id = "5", kind = FeedNotification.KIND_COMMENT, actorId = "b", contentId = "post", createdAt = "2026-10-13T05:00:00Z")
    val source = FakeSource(pages = mapOf(FeedApi.NOTIFICATIONS_INTERACTIONS to listOf(like("1", "a"), comment, like("2", "c", "other"))))
    val viewModel = FeedNotificationsViewModel(source = source, ioDispatcher = dispatcher)
    val row = groupNotifications(viewModel.state.value.tab(FeedApi.NOTIFICATIONS_INTERACTIONS).items, java.time.Instant.parse("2026-10-13T20:00:00Z"), java.time.ZoneOffset.UTC).single().rows.first()

    viewModel.mute(row, likesOnly = true)

    assertThat(source.mutes).containsExactly("post" to true)
    assertThat(viewModel.state.value.tab(FeedApi.NOTIFICATIONS_INTERACTIONS).items.map { it.id }).containsExactly("5", "2")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedNotificationsState.Notice.MUTED_LIKES)
    assertThat(viewModel.state.value.tab(FeedApi.NOTIFICATIONS_INTERACTIONS).loadedOnce).isTrue()
  }
}
