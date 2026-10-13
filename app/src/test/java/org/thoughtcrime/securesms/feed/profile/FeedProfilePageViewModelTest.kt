/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CompletableDeferred
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
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedProfilePostsPage
import org.thoughtcrime.securesms.feed.net.FeedRelation

@OptIn(ExperimentalCoroutinesApi::class)
class FeedProfilePageViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(
    var page: FeedProfilePage?,
    val posts: List<FeedCard> = listOf(FeedCard(id = "a", kind = FeedCard.KIND_POST, text = "hi")),
    var followResult: String? = null,
    var followError: Exception? = null
  ) : FeedSource by EmptyFeedSource {
    val followCalls = mutableListOf<Boolean>()
    val blockCalls = mutableListOf<Boolean>()
    var postsCalls = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun profile(profileId: String): FeedProfilePage? = page

    override suspend fun profilePosts(profileId: String, cursor: String?): FeedProfilePostsPage {
      postsCalls++
      return FeedProfilePostsPage(posts = posts)
    }

    override suspend fun setFollowing(profileId: String, following: Boolean): String {
      followCalls += following
      gate?.await()
      followError?.let { throw it }
      return followResult ?: if (following) FeedRelation.FOLLOW_APPROVED else FeedRelation.FOLLOW_NONE
    }

    override suspend fun setBlocked(profileId: String, blocked: Boolean) {
      blockCalls += blocked
    }
  }

  private fun page(private: Boolean = false, follow: String = FeedRelation.FOLLOW_NONE, followers: Int = 10, friend: Boolean = false) = FeedProfilePage(id = "p1", displayName = "山风", isPrivate = private, followers = followers, me = FeedRelation(follow = follow, friend = friend))

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `a public profile loads its posts`() {
    val viewModel = FeedProfilePageViewModel("p1", FakeSource(page()), dispatcher)

    assertThat(viewModel.state.value.profile?.displayName).isEqualTo("山风")
    assertThat(viewModel.state.value.posts.map { it.id }).containsExactly("a")
  }

  @Test
  fun `a private profile does not ask for posts until the follow is approved`() {
    val source = FakeSource(page(private = true), followResult = FeedRelation.FOLLOW_PENDING)
    val viewModel = FeedProfilePageViewModel("p1", source, dispatcher)

    assertThat(source.postsCalls).isEqualTo(0)
    viewModel.follow()

    assertThat(viewModel.state.value.profile?.me?.follow).isEqualTo(FeedRelation.FOLLOW_PENDING)
    assertThat(viewModel.state.value.profile?.followers).isEqualTo(10)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedProfilePageState.Notice.REQUESTED)
    assertThat(source.postsCalls).isEqualTo(0)
  }

  @Test
  fun `follow shows right away and counts one more follower`() {
    val source = FakeSource(page()).apply { gate = CompletableDeferred() }
    val viewModel = FeedProfilePageViewModel("p1", source, dispatcher)

    viewModel.follow()
    assertThat(viewModel.state.value.profile?.me?.follow).isEqualTo(FeedRelation.FOLLOW_APPROVED)
    assertThat(viewModel.state.value.profile?.followers).isEqualTo(11)
    assertThat(viewModel.state.value.followBusy).isTrue()

    source.gate!!.complete(Unit)
    assertThat(viewModel.state.value.followBusy).isFalse()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedProfilePageState.Notice.FOLLOWED)
  }

  @Test
  fun `following without a public profile rolls back and asks for one`() {
    val source = FakeSource(page(), followError = FeedApiException(409, "profile_required"))
    val viewModel = FeedProfilePageViewModel("p1", source, dispatcher)

    viewModel.follow()

    assertThat(viewModel.state.value.profile?.me?.follow).isEqualTo(FeedRelation.FOLLOW_NONE)
    assertThat(viewModel.state.value.profile?.followers).isEqualTo(10)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedProfilePageState.Notice.PROFILE_REQUIRED)
  }

  @Test
  fun `unfollowing a private account hides its posts again`() {
    val source = FakeSource(page(private = true, follow = FeedRelation.FOLLOW_APPROVED, followers = 5))
    val viewModel = FeedProfilePageViewModel("p1", source, dispatcher)
    assertThat(viewModel.state.value.posts.map { it.id }).containsExactly("a")

    viewModel.unfollow()

    assertThat(source.followCalls).containsExactly(false)
    assertThat(viewModel.state.value.profile?.followers).isEqualTo(4)
    assertThat(viewModel.state.value.posts).isEmpty()
  }

  @Test
  fun `a failed unfollow puts the follow back`() {
    val source = FakeSource(page(follow = FeedRelation.FOLLOW_APPROVED), followError = RuntimeException("offline"))
    val viewModel = FeedProfilePageViewModel("p1", source, dispatcher)

    viewModel.unfollow()

    assertThat(viewModel.state.value.profile?.me?.follow).isEqualTo(FeedRelation.FOLLOW_APPROVED)
    assertThat(viewModel.state.value.notice).isEqualTo(FeedProfilePageState.Notice.FAILED)
  }

  @Test
  fun `blocking drops the follow and the posts`() {
    val source = FakeSource(page(follow = FeedRelation.FOLLOW_APPROVED, followers = 3))
    val viewModel = FeedProfilePageViewModel("p1", source, dispatcher)

    viewModel.setBlocked(true)

    val profile = viewModel.state.value.profile!!
    assertThat(source.blockCalls).containsExactly(true)
    assertThat(profile.me.blocked).isTrue()
    assertThat(profile.me.follow).isEqualTo(FeedRelation.FOLLOW_NONE)
    assertThat(profile.followers).isEqualTo(2)
    assertThat(viewModel.state.value.posts).isEmpty()
  }

  @Test
  fun `a profile that cannot be seen shows as gone`() {
    val viewModel = FeedProfilePageViewModel("p1", FakeSource(page = null), dispatcher)

    assertThat(viewModel.state.value.gone).isTrue()
    assertThat(viewModel.state.value.profile).isNull()
  }

  @Test
  fun `another region's author shows no posts`() {
    val source = FakeSource(page().copy(regionUnavailable = true))
    FeedProfilePageViewModel("p1", source, dispatcher)

    assertThat(source.postsCalls).isEqualTo(0)
  }
}
