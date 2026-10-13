/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedStats
import java.io.File

/**
 * 详情页：帖子、楼中楼、折叠、作者气泡、评论框。设了 `FEED_SCREENSHOT_DIR` 时截图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedPostDetailScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val author = FeedAuthor(profileId = "u0", displayName = "山野", handle = "shanye")
  private val other = FeedAuthor(profileId = "u1", displayName = "小林")
  private val third = FeedAuthor(profileId = "u2", displayName = "阿青")

  private val post = FeedCard(
    id = "p",
    kind = FeedCard.KIND_POST,
    author = author,
    text = "周末去山里住了一晚，早上被鸟叫醒。",
    publishedAt = "2026-10-13T08:00:00Z",
    ipRegionLabel = "四川",
    stats = FeedStats(likes = 12, comments = 7)
  )

  private fun comment(id: String, by: FeedAuthor, text: String, parent: String? = null) = FeedCard(
    id = id,
    kind = FeedCard.KIND_COMMENT,
    author = by,
    text = text,
    rootId = "p",
    parentId = parent,
    publishedAt = "2026-10-13T09:00:00Z",
    ipRegionLabel = "重庆"
  )

  private val comments = listOf(
    comment("a", other, "请问是在哪里拍的？"),
    comment("a1", author, "在城西的山里，开车一个小时。", parent = "a"),
    comment("a2", third, "我也想去", parent = "a1"),
    comment("a3", other, "谢谢！", parent = "a1"),
    comment("a4", third, "收藏了", parent = "a"),
    comment("b", third, "看着就舒服。")
  )

  private var replyTarget: FeedCard? = null

  private fun show(state: FeedPostDetailState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedPostDetailScreen(
          state = state,
          onBack = {},
          onLikeClick = {},
          onRetryComments = {},
          onLoadMoreComments = {},
          onReplyClick = { replyTarget = it },
          onDraftChanged = {},
          onSend = {},
          onNoticeShown = {}
        )
      }
    }
  }

  @Test
  fun `threads fold after three replies and name who a reply answers`() {
    show(FeedPostDetailState(post = post, comments = comments, loading = false, loadedOnce = true, showIpRegion = true))

    composeTestRule.onNodeWithText("周末去山里住了一晚，早上被鸟叫醒。").assertExists()
    composeTestRule.onNodeWithText("@山野 我也想去").assertExists()
    composeTestRule.onNodeWithText("收藏了").assertDoesNotExist()
    composeTestRule.onNodeWithText("Author", substring = true).assertExists()

    val dir = System.getenv("FEED_SCREENSHOT_DIR")
    if (dir != null) {
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "post_detail.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    composeTestRule.onNodeWithText("View 1 more reply").performClick()
    composeTestRule.onNodeWithText("收藏了").assertExists()
  }

  @Test
  fun `reply picks the comment`() {
    show(FeedPostDetailState(post = post, comments = comments, loading = false, loadedOnce = true))

    composeTestRule.onAllNodesWithText("Reply")[0].performClick()

    assertThat(replyTarget?.id).isEqualTo("a")
  }

  @Test
  fun `empty and closed states`() {
    show(FeedPostDetailState(post = post.copy(stats = FeedStats()), loading = false, loadedOnce = true, canComment = false))

    composeTestRule.onAllNodesWithText("Comments are turned off")[0].assertExists()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.SEND).assertDoesNotExist()
  }

  @Test
  fun `send is off until there is text`() {
    show(FeedPostDetailState(post = post, loading = false, loadedOnce = true))

    composeTestRule.onNodeWithText("No comments yet").assertExists()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.SEND).assertIsNotEnabled()
  }
}
