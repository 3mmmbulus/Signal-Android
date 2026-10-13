/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import java.io.File

/**
 * 发布页的界面行为。设了环境变量 `FEED_SCREENSHOT_DIR` 时顺便把每个状态截成 PNG（云端没有模拟器时用来看样子）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedComposeScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private var published = 0
  private var visibility: FeedVisibility? = null

  private fun show(state: FeedComposeState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedComposeScreen(
          state = state,
          onClose = {},
          onKindChanged = {},
          onTitleChanged = {},
          onTextChanged = {},
          onImagesPicked = {},
          onImageRemoved = {},
          onVisibilityChanged = { visibility = it },
          onPublish = { published++ },
          onFailureShown = {}
        )
      }
    }
  }

  private fun screenshot(name: String) {
    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun `empty post cannot be published`() {
    show(FeedComposeState())

    composeTestRule.onNodeWithText("What's on your mind?").assertExists()
    publishButton().assertIsNotEnabled()
    screenshot("compose_empty")
  }

  @Test
  fun `written post can be published`() {
    show(FeedComposeState(draft = FeedDraft(text = "今天的云很低，像是伸手就能摸到。")))

    publishButton().assertIsEnabled().performClick()
    assertThat(published).isEqualTo(1)
    screenshot("compose_text")
  }

  @Test
  fun `counter shows near the limit and blocks publishing over it`() {
    show(FeedComposeState(draft = FeedDraft(text = "字".repeat(505))))

    composeTestRule.onNodeWithText("-5").assertExists()
    publishButton().assertIsNotEnabled()
    screenshot("compose_over_limit")
  }

  @Test
  fun `article shows a title field`() {
    show(FeedComposeState(draft = FeedDraft(kind = FeedDraftKind.ARTICLE)))

    composeTestRule.onNodeWithText("Title").assertExists()
    composeTestRule.onNodeWithText("Write your article").assertExists()
    screenshot("compose_article")
  }

  @Test
  fun `visibility sheet lists five choices and picking people is not offered yet`() {
    show(FeedComposeState(draft = FeedDraft(text = "a")))

    composeTestRule.onNodeWithText("Public").performClick()
    composeTestRule.onNodeWithText("Who can see this").assertExists()
    composeTestRule.onNodeWithText("Only some friends").assertExists()
    composeTestRule.onNodeWithText("Hide from").assertExists()
    screenshot("compose_visibility")

    composeTestRule.onNodeWithText("Only some friends").performClick()
    assertThat(visibility).isEqualTo(null)

    composeTestRule.onNodeWithText("Only me").performClick()
    assertThat(visibility).isEqualTo(FeedVisibility.ONLY_ME)
  }

  @Test
  fun `minor mode only offers friends and only me`() {
    show(FeedComposeState(draft = FeedDraft(text = "a", visibility = FeedVisibility.FRIENDS), minorMode = true))

    composeTestRule.onNodeWithText("Friends").performClick()
    composeTestRule.onNodeWithText("Only me").assertExists()
    composeTestRule.onNodeWithText("Public").assertDoesNotExist()
    composeTestRule.onNodeWithText("Hide from").assertDoesNotExist()
  }

  private fun publishButton() = composeTestRule.onNodeWithTag(FeedComposeTestTags.PUBLISH)
}
