/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.people

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.thoughtcrime.securesms.feed.net.FeedBlockEntry
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import java.io.File

/** 关注请求 / 屏蔽列表。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedPeopleScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private var approved: String? = null
  private var declined: String? = null
  private var unblocked: String? = null
  private var opened: String? = null

  private fun show(state: FeedPeopleState, mode: FeedPeopleMode) {
    composeTestRule.setContent {
      SignalTheme {
        FeedPeopleScreen(
          state = state,
          mode = mode,
          onBack = {},
          onRetry = {},
          onApprove = { approved = it.key },
          onDecline = { declined = it.key },
          onUnblock = { unblocked = it.key },
          onOpenProfile = { opened = it.id },
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

  private fun row(id: String, name: String, handle: String? = null) = FeedPeopleRow(key = id, person = FeedPersonSummary(id = id, displayName = name, handle = handle))

  @Test
  fun `requests can be approved, declined and opened`() {
    show(FeedPeopleState(loading = false, rows = listOf(row("a", "Xiaoman", "xm"), row("b", "Nightcat"))), FeedPeopleMode.FOLLOW_REQUESTS)

    composeTestRule.onNodeWithText("Follow requests").assertExists()
    composeTestRule.onNodeWithText("@xm").assertExists()
    screenshot("people_requests.png")

    composeTestRule.onAllNodesWithTag(FeedPeopleTestTags.APPROVE)[0].performClick()
    composeTestRule.onAllNodesWithTag(FeedPeopleTestTags.DECLINE)[1].performClick()
    composeTestRule.onNodeWithText("Nightcat", useUnmergedTree = true).performClick()
    assertThat(approved).isEqualTo("a")
    assertThat(declined).isEqualTo("b")
    assertThat(opened).isEqualTo("b")
  }

  @Test
  fun `empty requests explain where they come from`() {
    show(FeedPeopleState(loading = false), FeedPeopleMode.FOLLOW_REQUESTS)

    composeTestRule.onNodeWithText("No follow requests", substring = true).assertExists()
  }

  @Test
  fun `blocked list says it is feed only and shows topics`() {
    val topic = FeedPeopleRow(key = "topic/cooking", block = FeedBlockEntry(type = "topic", id = "cooking"))
    show(FeedPeopleState(loading = false, rows = listOf(row("author/a", "Ahe"), topic)), FeedPeopleMode.BLOCKED)

    composeTestRule.onNodeWithText("only affects Feed", substring = true).assertExists()
    composeTestRule.onNodeWithText("Topic: cooking").assertExists()
    screenshot("people_blocked.png")

    composeTestRule.onAllNodesWithTag(FeedPeopleTestTags.UNBLOCK)[1].performClick()
    assertThat(unblocked).isEqualTo("topic/cooking")
  }
}
