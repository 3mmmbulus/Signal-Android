/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedCard
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
}
