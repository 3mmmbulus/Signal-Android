/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import org.thoughtcrime.securesms.feed.FeedEntries
import org.thoughtcrime.securesms.feed.FeedOwnPostAction
import org.thoughtcrime.securesms.feed.FeedPostCardTestTags
import org.thoughtcrime.securesms.feed.LocalFeedEntries
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedMedia
import org.thoughtcrime.securesms.feed.net.FeedStats
import org.thoughtcrime.securesms.feed.net.FeedViewer
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
  private val moderated = mutableListOf<String>()

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
          onNoticeShown = {},
          onApproveComment = { moderated += "approve ${it.id}" },
          onDeclineComment = { moderated += "decline ${it.id}" },
          onOwnPostAction = { moderated += "own ${it.name}" },
          onDeletePost = { moderated += "delete" },
          onReportPost = { moderated += "report" },
          onReportComment = { moderated += "report comment ${it.id}" }
        )
      }
    }
  }

  @Test
  fun `long press someone else's comment reports it as content`() {
    show(FeedPostDetailState(post = post, comments = comments, loading = false, loadedOnce = true))
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.COMMENT + "a").performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.REPORT_COMMENT).performClick()
    assertThat(moderated).isEqualTo(listOf("report comment a"))
  }

  @Test
  fun `comment report is there even when nothing is deployed`() {
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(LocalFeedEntries provides FeedEntries.NONE) {
          FeedPostDetailScreen(
            state = FeedPostDetailState(post = post, comments = comments, loading = false, loadedOnce = true),
            onBack = {},
            onLikeClick = {},
            onRetryComments = {},
            onLoadMoreComments = {},
            onReplyClick = {},
            onDraftChanged = {},
            onSend = {},
            onNoticeShown = {},
            onReportComment = { moderated += "report comment ${it.id}" }
          )
        }
      }
    }
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.COMMENT + "a").performTouchInput { longClick() }
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.REPORT_COMMENT).performClick()
    assertThat(moderated).isEqualTo(listOf("report comment a"))
  }

  @Test
  fun `own and held comments have no report`() {
    val mine = comment("m", other, "我的").copy(viewer = FeedViewer(isAuthor = true))
    val held = comment("h", third, "等批准").copy(held = true)
    assertThat(FeedCommentMenu.canReport(mine)).isEqualTo(false)
    assertThat(FeedCommentMenu.canReport(held)).isEqualTo(false)
    assertThat(FeedCommentMenu.canReport(comment("o", third, "别人的"))).isEqualTo(true)
    assertThat(FeedCommentMenu.canReport(post)).isEqualTo(false)
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
    show(FeedPostDetailState(post = post, loading = false, loadedOnce = true, voiceAllowed = false))

    composeTestRule.onNodeWithText("No comments yet").assertExists()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.SEND).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.MIC).assertDoesNotExist()
  }

  @Test
  fun `an empty box shows the mic, typing turns it into send`() {
    show(FeedPostDetailState(post = post, loading = false, loadedOnce = true))
    composeTestRule.onNodeWithContentDescription("Hold to record a voice comment").assertExists()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.SEND).assertDoesNotExist()
  }

  @Test
  fun `typed text shows send instead of the mic`() {
    show(FeedPostDetailState(post = post, loading = false, loadedOnce = true, draft = "好看"))
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.SEND).assertExists()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.MIC).assertDoesNotExist()
  }

  @Test
  fun `recording bar shows the time and slide to cancel`() {
    composeTestRule.setContent {
      SignalTheme {
        FeedVoiceRecordingBar(elapsedMs = 52_300, maxMs = 60_000, slide = 0.3f)
      }
    }
    composeTestRule.onNodeWithText("0:52").assertExists()
    composeTestRule.onNodeWithText("Slide to cancel").assertExists()

    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_voice_recording.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  @Test
  fun `the post author approves or declines a held reply`() {
    val held = comment("h", third, "这张照片是在哪儿拍的？").copy(held = true)
    show(FeedPostDetailState(post = post.copy(viewer = post.viewer.copy(isAuthor = true)), comments = listOf(comments.last(), held), loading = false, loadedOnce = true))

    composeTestRule.onNodeWithText("Waiting for you").assertExists()
    val dir = System.getenv("FEED_SCREENSHOT_DIR")
    if (dir != null) {
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "post_detail_held.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.APPROVE + "h").performClick()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.DECLINE + "h").performClick()
    composeTestRule.onNodeWithText("Decline this reply?").assertExists()
    composeTestRule.onAllNodesWithText("Decline").onLast().performClick()
    assertThat(moderated).isEqualTo(listOf("approve h", "decline h"))
  }

  @Test
  fun `a voice comment shows its length, and the transcript only when there is one`() {
    val voice = FeedMedia(key = "m/v1", type = FeedMedia.TYPE_AUDIO, durationMs = 4_200)
    val withTranscript = comment("v", third, "").copy(media = listOf(voice), transcript = "早上的鸟叫真好听")
    val withoutTranscript = comment("w", other, "").copy(media = listOf(voice.copy(key = "m/v2", durationMs = 61_000)))
    show(FeedPostDetailState(post = post, comments = listOf(withTranscript, withoutTranscript), loading = false, loadedOnce = true))

    // 别人的评论气泡可以长按举报（combinedClickable 会合并子节点的语义），找行内的 tag 要看未合并的树
    composeTestRule.onAllNodesWithTag(FeedVoiceTestTags.ROW, useUnmergedTree = true).assertCountEquals(2)
    composeTestRule.onNodeWithText("0:04").assertExists()
    composeTestRule.onNodeWithText("1:01").assertExists()
    composeTestRule.onAllNodesWithTag(FeedVoiceTestTags.TRANSCRIPT, useUnmergedTree = true).assertCountEquals(1)
    composeTestRule.onNodeWithText("早上的鸟叫真好听").assertExists()

    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_voice_comments.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  @Test
  fun `voice row toggles, shows progress time while playing and a failure line`() {
    val voice = FeedMedia(key = "m/v1", type = FeedMedia.TYPE_AUDIO, durationMs = 4_200)
    var playback by mutableStateOf(FeedVoicePlayback())
    val toggled = mutableListOf<String>()
    composeTestRule.setContent {
      SignalTheme {
        FeedVoiceCommentRow(media = voice, transcript = "", playback = playback, onToggle = { toggled += it.key })
      }
    }
    composeTestRule.onNodeWithContentDescription("Play voice comment").performClick()
    assertThat(toggled).isEqualTo(listOf("m/v1"))

    playback = FeedVoicePlayback(key = "m/v1", playing = true, positionMs = 2_500)
    composeTestRule.onNodeWithContentDescription("Pause voice comment").assertExists()
    composeTestRule.onNodeWithText("0:02").assertExists()

    playback = FeedVoicePlayback(failedKey = "m/v1")
    composeTestRule.onNodeWithText("Couldn't play this voice comment.").assertExists()
    composeTestRule.onNodeWithText("0:04").assertExists()
  }

  @Test
  fun `voice duration format`() {
    assertThat(formatVoiceDuration(0)).isEqualTo("0:00")
    assertThat(formatVoiceDuration(999)).isEqualTo("0:00")
    assertThat(formatVoiceDuration(60_000)).isEqualTo("1:00")
    assertThat(formatVoiceDuration(-5)).isEqualTo("0:00")
  }

  @Test
  fun `the writer of a held reply sees it is waiting`() {
    val held = comment("h", third, "这张照片是在哪儿拍的？").copy(held = true)
    show(FeedPostDetailState(post = post, comments = listOf(held), loading = false, loadedOnce = true))

    composeTestRule.onNodeWithText("Visible after the author approves").assertExists()
    composeTestRule.onNodeWithTag(FeedPostDetailTestTags.APPROVE + "h").assertDoesNotExist()
  }

  @Test
  fun `my post has its own menu on the detail page, delete asks first`() {
    show(FeedPostDetailState(post = post.copy(viewer = post.viewer.copy(isAuthor = true)), loading = false, loadedOnce = true))

    composeTestRule.onNodeWithContentDescription("More").performClick()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.ownAction(FeedOwnPostAction.PIN)).performClick()
    composeTestRule.onNodeWithContentDescription("More").performClick()
    composeTestRule.onNodeWithText("Delete").performClick()
    composeTestRule.onNodeWithText("Delete this post?").assertExists()
    composeTestRule.onAllNodesWithText("Delete").onLast().performClick()

    assertThat(moderated).isEqualTo(listOf("own PIN", "delete"))
  }

  @Test
  fun `someone else's post can be reported from the detail page`() {
    show(FeedPostDetailState(post = post, loading = false, loadedOnce = true))

    composeTestRule.onNodeWithContentDescription("More").performClick()
    composeTestRule.onNodeWithText("Report").performClick()

    assertThat(moderated).isEqualTo(listOf("report"))
  }
}
