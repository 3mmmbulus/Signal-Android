/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedHideLists
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedProfilePostsPage
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage
import org.thoughtcrime.securesms.feed.people.FeedPeopleMode
import org.thoughtcrime.securesms.feed.people.FeedPeopleScreen
import org.thoughtcrime.securesms.feed.people.FeedPeopleState
import org.thoughtcrime.securesms.feed.people.FeedPeopleTestTags
import org.thoughtcrime.securesms.feed.people.FeedPeopleViewModel
import org.thoughtcrime.securesms.feed.profile.FeedHideState
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageState
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageViewModel
import java.io.File

/** 「不看 TA 的动态」「不让 TA 看我的动态」（第三节第 26 条；ADR-0071「设置：隐私与通知」）。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedHideListsTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()

  private val shanfeng = FeedAuthor(profileId = "p1", displayName = "山风", handle = "shanfeng")
  private val xiaoman = FeedAuthor(profileId = "p2", displayName = "小满")

  private class FakeSource(
    val cards: List<FeedCard> = emptyList(),
    var lists: FeedHideLists = FeedHideLists()
  ) : FeedSource by EmptyFeedSource {
    val calls = mutableListOf<String>()
    var error: Exception? = null
    override suspend fun timeline(tab: FeedTab, cursor: String?) = FeedTimelinePage(entries = cards.map { FeedEntry(id = it.id, card = it) })
    override suspend fun hideLists(): FeedHideLists = lists
    override suspend fun setHideList(list: String, profileId: String, on: Boolean) {
      error?.let { throw it }
      calls += "$list $profileId $on"
    }
    override suspend fun profile(profileId: String) = FeedProfilePage(id = profileId, displayName = "山风")
    override suspend fun profilePosts(profileId: String, cursor: String?) = FeedProfilePostsPage()
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun cards() = listOf(
    FeedCard(id = "a", kind = FeedCard.KIND_POST, text = "1", author = shanfeng),
    FeedCard(id = "b", kind = FeedCard.KIND_POST, text = "2", author = xiaoman),
    FeedCard(id = "c", kind = FeedCard.KIND_POST, text = "3", author = shanfeng)
  )

  @Test
  fun `muting takes all their posts out and offers undo`() {
    val source = FakeSource(cards())
    val viewModel = FeedViewModel(source, dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords())
    viewModel.onTabShown(FeedTab.FOLLOWING)

    viewModel.muteAuthor(cards().first())
    assertThat(viewModel.state.value.tab(FeedTab.FOLLOWING).cards.map { it.id }).containsExactly("b")
    assertThat(viewModel.state.value.mutedAuthor).isEqualTo(shanfeng)

    viewModel.onMutedNoticeShown()
    viewModel.unmuteAuthor(shanfeng)
    assertThat(source.calls).containsExactly("mute p1 true", "mute p1 false")
    assertThat(viewModel.state.value.tab(FeedTab.FOLLOWING).cards.map { it.id }).containsExactly("a", "b", "c")
  }

  @Test
  fun `muting without a profile says to set one up and puts the posts back`() {
    val source = FakeSource(cards()).apply { error = FeedApiException(409, code = "profile_required") }
    val viewModel = FeedViewModel(source, dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords())
    viewModel.onTabShown(FeedTab.FOLLOWING)

    viewModel.muteAuthor(cards().first())

    assertThat(viewModel.state.value.transientError).isEqualTo(FeedState.TransientError.PROFILE_REQUIRED)
    assertThat(viewModel.state.value.tab(FeedTab.FOLLOWING).cards.map { it.id }).containsExactly("a", "b", "c")
    assertThat(viewModel.state.value.mutedAuthor).isNull()
  }

  @Test
  fun `the two lists load and remove like the other people lists`() {
    val person = FeedPersonSummary(id = "p1", handle = "shanfeng", displayName = "山风")
    val source = FakeSource(lists = FeedHideLists(mute = listOf(person), hideFrom = listOf(person.copy(id = "p2", displayName = "小满"))))

    val muted = FeedPeopleViewModel(FeedPeopleMode.MUTED, source, dispatcher)
    val hiddenFrom = FeedPeopleViewModel(FeedPeopleMode.HIDDEN_FROM, source, dispatcher)
    assertThat(muted.state.value.rows.map { it.person?.displayName }).containsExactly("山风")
    assertThat(hiddenFrom.state.value.rows.map { it.person?.displayName }).containsExactly("小满")

    hiddenFrom.remove(hiddenFrom.state.value.rows.single())
    assertThat(hiddenFrom.state.value.rows).isEqualTo(emptyList())
    assertThat(hiddenFrom.state.value.notice).isEqualTo(FeedPeopleState.Notice.REMOVED)
    assertThat(source.calls).containsExactly("hide_from p2 false")
  }

  @Test
  fun `a profile knows whether I muted them, and a failed change turns back`() {
    val source = FakeSource(lists = FeedHideLists(mute = listOf(FeedPersonSummary(id = "p1", displayName = "山风"))))
    val viewModel = FeedProfilePageViewModel("p1", source, dispatcher)
    assertThat(viewModel.state.value.hideState).isEqualTo(FeedHideState(muted = true, hiddenFrom = false))

    viewModel.setHidden(FeedApi.HIDE_FROM, on = true)
    assertThat(viewModel.state.value.hideState).isEqualTo(FeedHideState(muted = true, hiddenFrom = true))
    assertThat(viewModel.state.value.notice).isEqualTo(FeedProfilePageState.Notice.HIDDEN_FROM)

    source.error = IllegalStateException("offline")
    viewModel.setHidden(FeedApi.HIDE_MUTE, on = false)
    assertThat(viewModel.state.value.hideState).isEqualTo(FeedHideState(muted = true, hiddenFrom = true))
    assertThat(viewModel.state.value.notice).isEqualTo(FeedProfilePageState.Notice.FAILED)
    assertThat(source.calls).containsExactly("hide_from p1 true")
  }

  @Test
  fun `someone else's post offers mute, an outside author does not`() {
    var muted: FeedCard? = null
    val native = cards().first()
    val external = FeedCard(id = "x", kind = FeedCard.KIND_POST, text = "外部", author = FeedAuthor(profileId = "", displayName = "外部作者", isNative = false))
    composeTestRule.setContent {
      SignalTheme {
        Column {
          FeedPostCard(card = native, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {}, onMuteAuthorClick = { muted = it })
          FeedPostCard(card = external, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {}, onMuteAuthorClick = { muted = it })
        }
      }
    }
    composeTestRule.onAllNodesWithContentDescription("More")[1].performClick()
    composeTestRule.onAllNodesWithTag(FeedPostCardTestTags.MUTE_AUTHOR).assertCountEquals(0)
    composeTestRule.onAllNodesWithContentDescription("More")[0].performClick()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.MUTE_AUTHOR).performClick()
    assertThat(muted).isEqualTo(native)
  }

  @Test
  fun `the hide-from list explains itself`() {
    val person = FeedPersonSummary(id = "p2", displayName = "小满", handle = "xiaoman")
    val source = FakeSource(lists = FeedHideLists(hideFrom = listOf(person)))
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.HIDDEN_FROM, source, dispatcher)
    composeTestRule.setContent {
      SignalTheme {
        FeedPeopleScreen(
          state = viewModel.state.value,
          mode = FeedPeopleMode.HIDDEN_FROM,
          onBack = {},
          onRetry = {},
          onApprove = {},
          onDecline = {},
          onUnblock = {},
          onOpenProfile = {},
          onNoticeShown = {},
          onRemove = viewModel::remove
        )
      }
    }
    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_hidden_from.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    composeTestRule.onNodeWithTag(FeedPeopleTestTags.REMOVE).performClick()
    assertThat(source.calls).containsExactly("hide_from p2 false")
  }
}
