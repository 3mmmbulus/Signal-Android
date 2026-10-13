/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.closepeople

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedClosePersonEntry
import org.thoughtcrime.securesms.feed.net.FeedSearchUser
import java.io.File

/** 「关心的人」页。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedClosePeopleScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private var added: FeedSearchUser? = null
  private var answered: Pair<String, Boolean>? = null

  private fun show(state: FeedClosePeopleState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedClosePeopleScreen(
          state = state,
          onBack = {},
          onRetry = {},
          onQueryChanged = {},
          onAdd = { added = it },
          onRemove = {},
          onAnswer = { entry, accept -> answered = entry.id to accept },
          onOpenProfile = {},
          onNoticeShown = {}
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
  fun `shows mine with state, search results and requests`() {
    show(
      FeedClosePeopleState(
        loaded = true,
        mine = listOf(FeedClosePersonEntry(id = "p1", displayName = "Anan", state = FeedClosePersonEntry.STATE_ACCEPTED), FeedClosePersonEntry(id = "p2", displayName = "Bo")),
        query = "jiu",
        results = listOf(FeedSearchUser(id = "p9", displayName = "Jiu", handle = "jiu")),
        requests = listOf(FeedClosePersonEntry(id = "r1", displayName = "Ruo"))
      )
    )
    screenshot("close_people.png")

    composeTestRule.onNodeWithText("Your close people (2/3)").assertExists()
    composeTestRule.onNodeWithText("Agreed").assertExists()
    composeTestRule.onNodeWithText("Waiting for them to agree").assertExists()
    composeTestRule.onNodeWithTag(FeedClosePeopleTestTags.ADD).performClick()
    assertThat(added?.id).isEqualTo("p9")
    composeTestRule.onNodeWithTag(FeedClosePeopleTestTags.ACCEPT).performClick()
    assertThat(answered).isEqualTo("r1" to true)
  }

  @Test
  fun `full list hides the search`() {
    show(
      FeedClosePeopleState(
        loaded = true,
        mine = (1..3).map { FeedClosePersonEntry(id = "p$it", displayName = "P$it") }
      )
    )
    composeTestRule.onNodeWithTag(FeedClosePeopleTestTags.SEARCH).assertDoesNotExist()
    composeTestRule.onNodeWithText("No one yet.").assertExists()
  }
}
