/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.mine

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
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedMyPost
import java.io.File

/**
 * 「我的动态」的界面：三种状态、未通过原因、删除要确认。设了 `FEED_SCREENSHOT_DIR` 时截图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedMyPostsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val deleted = mutableListOf<String>()

  private val posts = listOf(
    FeedMyPost(id = "p", state = FeedMyPost.STATE_PENDING, text = "周末的市集有一家卖手工皂的，味道很好闻。", createdAt = "2026-10-13T08:00:00Z"),
    FeedMyPost(id = "r", state = FeedMyPost.STATE_REJECTED, text = "加我领福利，名额有限。", createdAt = "2026-10-12T08:00:00Z", rejectionReasons = listOf("prohibited_words"), appealable = true),
    FeedMyPost(id = "a", kind = "article", state = FeedMyPost.STATE_PUBLISHED, title = "周末去山里住了一晚", text = "早上被鸟叫醒，比闹钟好听多了。".repeat(10))
  )

  private fun show(state: FeedMyPostsState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedMyPostsScreen(
          state = state,
          onBack = {},
          onRefresh = {},
          onLoadMore = {},
          onDelete = { deleted += it.id },
          onDeleteFailureShown = {}
        )
      }
    }
  }

  @Test
  fun `states and reasons are shown`() {
    show(FeedMyPostsState(posts = posts, loading = false, loadedOnce = true))

    composeTestRule.onNodeWithText("In review").assertExists()
    composeTestRule.onNodeWithText("Only you can see this until it's reviewed.").assertExists()
    composeTestRule.onNodeWithText("Not approved").assertExists()
    composeTestRule.onNodeWithText("Not approved: contains words that can't be posted publicly. Only you can see this.").assertExists()
    composeTestRule.onNodeWithText("Posted").assertExists()

    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, "my_posts.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun `delete asks first`() {
    show(FeedMyPostsState(posts = posts, loading = false, loadedOnce = true))

    composeTestRule.onAllNodesWithTag(FeedMyPostsTestTags.MORE)[1].performClick()
    composeTestRule.onNodeWithText("Delete").performClick()
    composeTestRule.onNodeWithText("Delete this post?").assertExists()
    assertThat(deleted).containsExactly()

    composeTestRule.onNodeWithText("Delete").performClick()
    assertThat(deleted).containsExactly("r")
  }

  @Test
  fun `rejected post offers an appeal`() {
    show(FeedMyPostsState(posts = posts, loading = false, loadedOnce = true))

    composeTestRule.onAllNodesWithTag(FeedMyPostsTestTags.MORE)[1].performClick()
    composeTestRule.onNodeWithText("Appeal").assertExists()
  }

  @Test
  fun `appeal in review is shown and not offered again`() {
    show(FeedMyPostsState(posts = listOf(posts[1].copy(appealable = false, appealState = FeedMyPost.APPEAL_RECEIVED)), loading = false, loadedOnce = true))

    composeTestRule.onNodeWithText("Appeal received. A person is reviewing it.").assertExists()
    composeTestRule.onAllNodesWithTag(FeedMyPostsTestTags.MORE)[0].performClick()
    composeTestRule.onNodeWithText("Appeal").assertDoesNotExist()
  }

  @Test
  fun `empty list says so`() {
    show(FeedMyPostsState(loading = false, loadedOnce = true))

    composeTestRule.onNodeWithText("You haven't posted anything yet").assertExists()
  }
}
