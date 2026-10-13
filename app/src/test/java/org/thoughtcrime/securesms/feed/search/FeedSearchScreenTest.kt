/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.search

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import java.io.File

/** 搜索页：空的时候是最近看过的人和最近搜索；有词时按范围出结果。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedSearchScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private var recent: String? = null
  private var scope: String? = null
  private var person: String? = null
  private var cancelled = false

  private fun show(state: FeedSearchState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedSearchScreen(
          state = state,
          onQueryChange = {},
          onSubmit = {},
          onCancel = { cancelled = true },
          onSelectScope = { scope = it },
          onRecentQuery = { recent = it },
          onRemoveRecentQuery = {},
          onClearRecentQueries = {},
          onClearRecentPeople = {},
          onOpenPerson = { person = it.id },
          onOpenPost = {},
          onLikeClick = {},
          onLoadMore = {},
          onRetry = {},
          autoFocus = false
        )
      }
    }
  }

  private fun screenshot(name: String) {
    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun `empty query shows recent people and searches`() {
    show(
      FeedSearchState(
        recentPeople = listOf(FeedPersonSummary(id = "p1", displayName = "Shanfeng"), FeedPersonSummary(id = "p2", displayName = "Ahe")),
        recentQueries = listOf("sourdough", "night run")
      )
    )

    composeTestRule.onNodeWithText("Recently viewed").assertExists()
    composeTestRule.onNodeWithText("Recent searches").assertExists()
    screenshot("search_empty.png")

    composeTestRule.onNodeWithText("night run").performClick()
    assertThat(recent).isEqualTo("night run")
    composeTestRule.onNodeWithText("Ahe").performClick()
    assertThat(person).isEqualTo("p2")
    composeTestRule.onNodeWithTag(FeedSearchTestTags.CANCEL).performClick()
    assertThat(cancelled).isTrue()
  }

  @Test
  fun `all scope shows people then posts`() {
    val author = FeedAuthor(profileId = "p1", displayName = "Shanfeng", handle = "shanfeng")
    show(
      FeedSearchState(
        query = "cloud",
        results = FeedSearchResults(
          query = "cloud",
          scope = FeedApi.SEARCH_ALL,
          users = listOf(FeedSearchUser(id = "p1", handle = "shanfeng", displayName = "Shanfeng")),
          posts = listOf(FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "The clouds were low today.", author = author))
        )
      )
    )

    composeTestRule.onAllNodesWithText("@shanfeng").assertCountEquals(2)
    composeTestRule.onNodeWithText("The clouds were low today.").assertExists()
    screenshot("search_results.png")

    composeTestRule.onNodeWithText("See all").performClick()
    assertThat(scope).isEqualTo(FeedApi.SEARCH_USERS)
  }

  @Test
  fun `no results names the query`() {
    show(FeedSearchState(query = "zzz", results = FeedSearchResults(query = "zzz", scope = FeedApi.SEARCH_ALL)))

    composeTestRule.onNodeWithText("No results for “zzz”").assertExists()
  }
}
