/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

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
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import java.io.File

/** 公开主页设置页：第一次建时说明是公开的；没有名字不能建。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedProfileScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private fun show(state: FeedProfileState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedProfileScreen(state = state, onBack = {}, onNameChanged = {}, onBioChanged = {}, onSave = {}, onRetry = {}, onErrorShown = {})
      }
    }
  }

  @Test
  fun `new profile explains it is public`() {
    show(FeedProfileState(loading = false, name = "小满"))

    composeTestRule.onNodeWithText("Set up your public profile").assertExists()
    composeTestRule.onNodeWithText("Your chat profile is separate", substring = true).assertExists()
    composeTestRule.onNodeWithTag(FeedProfileTestTags.SAVE).assertIsEnabled()

    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, "profile_setup.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun `existing profile shows the tellomi number and needs a name`() {
    show(FeedProfileState(loading = false, profile = FeedMyProfile(id = "x", displayName = "山风", tellomiNumber = "1234567890"), name = ""))

    composeTestRule.onNodeWithText("1234567890", substring = true).assertExists()
    composeTestRule.onNodeWithTag(FeedProfileTestTags.SAVE).assertIsNotEnabled()
  }
}
