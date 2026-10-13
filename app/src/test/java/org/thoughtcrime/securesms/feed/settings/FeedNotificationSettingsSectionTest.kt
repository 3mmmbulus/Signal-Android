/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedNotificationSettings
import java.io.File

/** 通知页里的「动态」一段。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedNotificationSettingsSectionTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private var likes: Boolean? = null
  private var fromWhom: String? = null
  private var retried = false

  private val actions = FeedNotificationSettingsActions(
    onFromWhom = { fromWhom = it },
    onLikes = { likes = it },
    onComments = {},
    onFollows = {},
    onRetry = { retried = true }
  )

  private fun show(state: FeedNotificationSettingsState) {
    composeTestRule.setContent {
      SignalTheme {
        LazyColumn {
          feedNotificationSettingsItems(state, actions)
        }
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
  fun `shows source and three switches`() {
    show(FeedNotificationSettingsState(settings = FeedNotificationSettings()))
    screenshot("notification_settings.png")

    composeTestRule.onNodeWithText("Feed").assertExists()
    composeTestRule.onNodeWithText("Comments and replies").assertExists()
    composeTestRule.onNodeWithText("Likes").performClick()
    assertThat(likes).isEqualTo(false)

    composeTestRule.onNodeWithText("Notify me about").performClick()
    composeTestRule.onNodeWithText("Friends").performClick()
    assertThat(fromWhom).isEqualTo(FeedNotificationSettings.FROM_FRIENDS)
  }

  @Test
  fun `no one greys out the switches`() {
    show(FeedNotificationSettingsState(settings = FeedNotificationSettings(fromWhom = FeedNotificationSettings.FROM_NONE)))
    composeTestRule.onNodeWithText("Likes").assertIsNotEnabled()
  }

  @Test
  fun `failed load offers retry`() {
    show(FeedNotificationSettingsState(loadFailed = true))
    composeTestRule.onNodeWithText("Tap to try again").performClick()
    assertThat(retried).isTrue()
  }
}
