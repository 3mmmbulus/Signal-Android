/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.sharing.v2.ShareActivity
import java.io.File

/**
 * 帖子卡片用样例数据画出来：IP 属地、已编辑、话题、长文标题。设了 `FEED_SCREENSHOT_DIR` 时截图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1600dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedPostCardTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  fun `sample cards render with region label and topics`() {
    val cards = runBlocking { SampleFeedSource.timeline(FeedTab.RECOMMEND, cursor = null) }.entries.map { it.card }.take(4)
    val article = FeedCard(
      id = "article",
      kind = FeedCard.KIND_ARTICLE,
      author = cards.first().author,
      title = "周末去山里住了一晚",
      text = "早上被鸟叫醒，比闹钟好听多了。".repeat(20)
    )

    composeTestRule.setContent {
      SignalTheme {
        Column {
          (listOf(article) + cards).forEach { card ->
            FeedPostCard(card = card, showIpRegion = true, onLikeClick = {}, onNotInterestedClick = {})
            HorizontalDivider()
          }
        }
      }
    }

    composeTestRule.onNodeWithText("周末去山里住了一晚").assertExists()
    composeTestRule.onNodeWithText(cards.first().text!!).assertExists()

    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, "feed_cards.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun `someone else's post offers block in the menu`() {
    val card = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "x", author = FeedAuthor(profileId = "p1", displayName = "Anan"))
    var blocked: FeedCard? = null
    composeTestRule.setContent {
      SignalTheme {
        FeedPostCard(card = card, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {}, onBlockAuthorClick = { blocked = it })
      }
    }
    composeTestRule.onNodeWithContentDescription("More").performClick()
    composeTestRule.onNodeWithText("Block").performClick()
    assertThat(blocked).isEqualTo(card)
  }

  @Test
  fun `share shows only on shareable cards and copies the post link`() {
    val id = "0f8e2c1a-3b4d-4e5f-8a9b-0c1d2e3f4a5b"
    val shareable = FeedCard(id = id, kind = FeedCard.KIND_POST, text = "公开的", shareable = true)
    val private = FeedCard(id = "x", kind = FeedCard.KIND_POST, text = "不公开的")
    composeTestRule.setContent {
      SignalTheme {
        Column {
          FeedPostCard(card = private, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
          FeedPostCard(card = shareable, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
        }
      }
    }
    composeTestRule.onAllNodesWithTag(FeedPostCardTestTags.SHARE).assertCountEquals(1)
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SHARE).performClick()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.COPY_LINK).performClick()

    val clipboard = ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)
    assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("https://tell.cc/m#$id")
  }

  @Test
  fun `send to chats opens the forward picker with the post's text and link`() {
    val id = "0f8e2c1a-3b4d-4e5f-8a9b-0c1d2e3f4a5b"
    val card = FeedCard(id = id, kind = FeedCard.KIND_POST, text = "公开的", author = FeedAuthor(profileId = "p1", displayName = "山风"), shareable = true)
    composeTestRule.setContent {
      SignalTheme {
        FeedPostCard(card = card, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
      }
    }
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SHARE).performClick()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SEND_TO_CHATS).performClick()

    val started = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
    assertThat(started.component?.className).isEqualTo(ShareActivity::class.java.name)
    assertThat(started.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("山风：公开的\nhttps://tell.cc/m#$id")
  }
}
