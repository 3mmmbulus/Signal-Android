/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailState
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailViewModel
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage
import org.thoughtcrime.securesms.feed.net.FeedViewer

/** 收藏 = 存进「我的收藏」+ 以假名上报（第三节第 27 条）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedSaveTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()

  private val card = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "周末去爬山，\n山顶的风很大", author = FeedAuthor(profileId = "p1", displayName = "山风"))

  private class FakeMessages : FeedSavedMessages {
    val calls = mutableListOf<String>()
    var error: Exception? = null
    override suspend fun add(card: FeedCard) {
      error?.let { throw it }
      calls += "add ${card.id}"
    }
    override suspend fun remove(contentId: String) {
      error?.let { throw it }
      calls += "remove $contentId"
    }
  }

  private class FakeSource(val card: FeedCard) : FeedSource by EmptyFeedSource {
    val reports = mutableListOf<String>()
    var reportError: Exception? = null
    override suspend fun timeline(tab: FeedTab, cursor: String?) = FeedTimelinePage(entries = listOf(FeedEntry(id = card.id, card = card)))
    override suspend fun setSaved(contentId: String, saved: Boolean) {
      reportError?.let { throw it }
      reports += "$contentId $saved"
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

  private fun FeedViewModel.card() = state.value.tab(FeedTab.FOLLOWING).cards.single()

  @Test
  fun `saving puts it in saved messages, then reports, unsaving takes it out`() {
    val messages = FakeMessages()
    val source = FakeSource(card)
    val viewModel = FeedViewModel(source, dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords(), savedMessages = messages).also { it.onTabShown(FeedTab.FOLLOWING) }

    viewModel.toggleSave(viewModel.card())
    assertThat(viewModel.card().viewer.saved).isTrue()
    assertThat(viewModel.state.value.savedNotice).isTrue()
    viewModel.toggleSave(viewModel.card())

    assertThat(viewModel.card().viewer.saved).isFalse()
    assertThat(messages.calls).containsExactly("add c1", "remove c1")
    assertThat(source.reports).containsExactly("c1 true", "c1 false")
  }

  @Test
  fun `if saved messages fails it turns back, a failed report does not`() {
    val messages = FakeMessages().apply { error = IllegalStateException("db") }
    val source = FakeSource(card)
    val viewModel = FeedViewModel(source, dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords(), savedMessages = messages).also { it.onTabShown(FeedTab.FOLLOWING) }

    viewModel.toggleSave(viewModel.card())
    assertThat(viewModel.card().viewer.saved).isFalse()
    assertThat(viewModel.state.value.transientError).isEqualTo(FeedState.TransientError.ACTION_FAILED)
    assertThat(source.reports).containsExactly()

    messages.error = null
    source.reportError = IllegalStateException("offline")
    viewModel.toggleSave(viewModel.card())
    assertThat(viewModel.card().viewer.saved).isTrue()
  }

  @Test
  fun `the detail page saves the same way`() {
    val messages = FakeMessages()
    val source = FakeSource(card)
    val viewModel = FeedPostDetailViewModel(card, source, dispatcher, savedMessages = messages)

    viewModel.toggleSave()

    assertThat(viewModel.state.value.post.viewer.saved).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.SAVED)
    assertThat(messages.calls).containsExactly("add c1")
  }

  @Test
  fun `the saved message reads as author, a snippet and the link`() {
    assertThat(card.savedMessageBody("https://tell.cc/m#c1")).isEqualTo("山风：周末去爬山， 山顶的风很大\nhttps://tell.cc/m#c1")
    val long = card.copy(text = "字".repeat(80))
    assertThat(long.savedMessageBody("L").substringBefore("\n")).isEqualTo("山风：" + "字".repeat(60) + "…")
  }

  @Test
  fun `the bookmark shows only where saving is wired and says its state`() {
    var saved: FeedCard? = null
    composeTestRule.setContent {
      SignalTheme {
        FeedPostCard(card = card.copy(viewer = FeedViewer(saved = true)), showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {}, onSaveClick = { saved = it })
      }
    }
    composeTestRule.onNodeWithContentDescription("Remove from saved").assertExists()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SAVE).performClick()
    assertThat(saved?.id).isEqualTo("c1")
  }
}
