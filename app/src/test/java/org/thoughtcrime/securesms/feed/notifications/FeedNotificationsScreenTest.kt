/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.notifications

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedNotification
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.feed.net.FeedUnreadCounts
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** 通知页：三栏、分组、赞合并、行菜单。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedNotificationsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val now = Instant.parse("2026-10-13T12:00:00Z")
  private var opened: FeedNotificationRow? = null
  private var muted: Pair<FeedNotificationRow, Boolean>? = null
  private var selected: String? = null

  private val people = mapOf(
    "a" to FeedPersonSummary(id = "a", displayName = "Shanfeng"),
    "b" to FeedPersonSummary(id = "b", displayName = "Xiaoman"),
    "c" to FeedPersonSummary(id = "c", displayName = "Ahe")
  )

  private fun show(state: FeedNotificationsState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedNotificationsScreen(
          state = state,
          onBack = {},
          onSelectTab = { selected = it },
          onRetry = {},
          onLoadMore = {},
          onOpen = { opened = it },
          onDelete = {},
          onMute = { row, likesOnly -> muted = row to likesOnly },
          onNoticeShown = {},
          now = { now },
          zone = ZoneOffset.UTC
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

  private fun n(id: String, kind: String, actor: String?, at: String, read: Boolean = true, content: String? = "post", detail: JsonObject? = null) = FeedNotification(id = id, kind = kind, actorId = actor, contentId = content, createdAt = at, read = read, detail = detail)

  private fun state(tab: String, items: List<FeedNotification>) = FeedNotificationsState(
    selectedTab = tab,
    tabs = mapOf(tab to FeedNotificationsTab(items = items, loadedOnce = true)),
    unread = FeedUnreadCounts(interactions = 0, follows = 2, system = 1),
    people = people
  )

  @Test
  fun `interactions merge likes and group by day`() {
    show(
      state(
        FeedApi.NOTIFICATIONS_INTERACTIONS,
        listOf(
          n("1", FeedNotification.KIND_LIKE, "a", "2026-10-13T11:00:00Z", read = false),
          n("2", FeedNotification.KIND_LIKE, "b", "2026-10-13T10:00:00Z"),
          n("3", FeedNotification.KIND_LIKE, "c", "2026-10-13T09:00:00Z"),
          n("4", FeedNotification.KIND_COMMENT, "b", "2026-10-13T08:00:00Z"),
          n("5", FeedNotification.KIND_REPLY, "c", "2026-10-10T08:00:00Z", detail = JsonObject(mapOf("held" to JsonPrimitive(true))))
        )
      )
    )

    composeTestRule.onNodeWithText("Today").assertExists()
    composeTestRule.onNodeWithText("This week").assertExists()
    composeTestRule.onNodeWithText("Shanfeng and 2 others liked your post").assertExists()
    composeTestRule.onNodeWithText("Xiaoman commented on your post").assertExists()
    composeTestRule.onNodeWithText("waiting for your approval", substring = true).assertExists()
    screenshot("notifications_interactions.png")

    composeTestRule.onNodeWithText("Xiaoman commented on your post", useUnmergedTree = true).performClick()
    assertThat(opened?.key).isEqualTo("4")

    composeTestRule.onAllNodesWithTag(FeedNotificationsTestTags.ROW_MORE)[0].performClick()
    composeTestRule.onNodeWithText("Turn off like notifications for this post").performClick()
    assertThat(muted?.first?.key).isEqualTo("1")
    assertThat(muted?.second).isEqualTo(true)
  }

  @Test
  fun `system notices have no mute option and tabs show unread counts`() {
    show(
      state(
        FeedApi.NOTIFICATIONS_SYSTEM,
        listOf(n("1", FeedNotification.KIND_SYSTEM, null, "2026-10-01T08:00:00Z", detail = JsonObject(mapOf("type" to JsonPrimitive("rejected")))))
      )
    )

    composeTestRule.onNodeWithText("Your post wasn't approved. Tap to see why.").assertExists()
    composeTestRule.onNodeWithText("Earlier").assertExists()
    composeTestRule.onNodeWithText("2").assertExists()
    screenshot("notifications_system.png")

    composeTestRule.onAllNodesWithTag(FeedNotificationsTestTags.ROW_MORE)[0].performClick()
    composeTestRule.onNodeWithText("Turn off notifications for this post").assertDoesNotExist()
    composeTestRule.onNodeWithText("Delete").assertExists()

    composeTestRule.onNodeWithText("Followers", useUnmergedTree = true).performClick()
    assertThat(selected).isEqualTo(FeedApi.NOTIFICATIONS_FOLLOWS)
  }

  @Test
  fun `empty follows tab explains itself`() {
    show(state(FeedApi.NOTIFICATIONS_FOLLOWS, emptyList()))

    composeTestRule.onNodeWithText("New followers and follow requests will show up here.").assertExists()
  }
}
