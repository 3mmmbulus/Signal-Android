/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedPoll
import org.thoughtcrime.securesms.feed.net.FeedPollState
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 帖子里的投票：单选点了就投，多选勾完点「投票」，投过或截止了看结果。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedPollViewTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val now = Instant.parse("2026-10-13T08:00:00Z")
  private val single = FeedPoll(options = listOf("Hiking", "The park", "Sleeping in"), closesAt = now.plus(2, ChronoUnit.DAYS).toString())
  private val multi = FeedPoll(options = listOf("Buns", "Noodles", "Congee"), closesAt = now.plus(3, ChronoUnit.HOURS).toString(), multi = true)

  private val votes = mutableListOf<List<Int>>()

  private fun show(poll: FeedPoll, state: FeedPollState?) {
    composeTestRule.setContent {
      SignalTheme {
        FeedPollView(poll = poll, state = state, onVote = { votes += it }, modifier = Modifier.padding(16.dp), nowMillis = now.toEpochMilli())
      }
    }
  }

  @Test
  fun `single choice votes on tap and shows time left`() {
    show(single, FeedPollState(counts = listOf(3, 1, 0)))

    composeTestRule.onNodeWithText("4 votes · 2 days left").assertExists()
    composeTestRule.onNodeWithTag(FeedPollTestTags.SUBMIT).assertDoesNotExist()
    composeTestRule.onNodeWithTag(FeedPollTestTags.OPTION + 1).performClick()
    assertThat(votes).containsExactly(listOf(1))
  }

  @Test
  fun `multi choice votes only after submit`() {
    show(multi, FeedPollState(counts = listOf(0, 0, 0)))

    composeTestRule.onNodeWithTag(FeedPollTestTags.OPTION + 0).performClick()
    composeTestRule.onNodeWithTag(FeedPollTestTags.OPTION + 2).performClick()
    assertThat(votes).isEmpty()
    composeTestRule.onNodeWithText("0 votes · Multiple choice · 3 hours left").assertExists()

    composeTestRule.onNodeWithTag(FeedPollTestTags.SUBMIT).performClick()
    assertThat(votes).containsExactly(listOf(0, 2))
  }

  @Test
  fun `after voting the results show and taps do nothing`() {
    show(single, FeedPollState(counts = listOf(3, 1, 0), voted = listOf(0)))

    composeTestRule.onNodeWithText("75%").assertExists()
    composeTestRule.onNodeWithText("25%").assertExists()
    composeTestRule.onNodeWithTag(FeedPollTestTags.OPTION + 1).performClick()
    assertThat(votes).isEmpty()
  }

  @Test
  fun `a closed poll shows final results`() {
    show(multi.copy(closesAt = now.minus(1, ChronoUnit.HOURS).toString()), FeedPollState(counts = listOf(2, 2, 1)))

    composeTestRule.onNodeWithText("20%").assertExists()
    composeTestRule.onNodeWithText("5 votes · Multiple choice · Final results").assertExists()
  }

  @Test
  fun `poll states side by side`() {
    composeTestRule.setContent {
      SignalTheme {
        Column {
          FeedPollView(single, FeedPollState(counts = listOf(3, 1, 0)), onVote = {}, modifier = Modifier.padding(16.dp), nowMillis = now.toEpochMilli())
          HorizontalDivider()
          FeedPollView(single, FeedPollState(counts = listOf(3, 1, 0), voted = listOf(0)), onVote = {}, modifier = Modifier.padding(16.dp), nowMillis = now.toEpochMilli())
          HorizontalDivider()
          FeedPollView(multi, FeedPollState(counts = listOf(0, 0, 0)), onVote = {}, modifier = Modifier.padding(16.dp), nowMillis = now.toEpochMilli())
          HorizontalDivider()
          FeedPollView(multi.copy(closesAt = now.minus(1, ChronoUnit.HOURS).toString()), FeedPollState(counts = listOf(2, 2, 1)), onVote = {}, modifier = Modifier.padding(16.dp), nowMillis = now.toEpochMilli())
        }
      }
    }

    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, "feed_poll.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun `percent rounds and survives no votes`() {
    assertThat(percent(1, 3)).isEqualTo(33)
    assertThat(percent(2, 3)).isEqualTo(67)
    assertThat(percent(0, 0)).isEqualTo(0)
  }

  @Test
  fun `sample source counts one vote per person`() = runBlocking {
    val card = SampleFeedSource.timeline(FeedTab.RECOMMEND, cursor = null).entries.map { it.card }.first { it.poll?.multi == false }
    val before = card.pollState!!.counts

    val after = SampleFeedSource.vote(card.id, listOf(0))

    assertThat(after.hasVoted).isTrue()
    assertThat(after.counts[0]).isEqualTo(before[0] + 1)
    assertFailure { SampleFeedSource.vote(card.id, listOf(1)) }
      .isInstanceOf(FeedApiException::class).prop(FeedApiException::code).isEqualTo("already_voted")
  }
}
