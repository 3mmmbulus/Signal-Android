/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import android.app.Application
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.banner.banners.UpdateAvailableBanner

/**
 * Tellomi（tellomi/tellomi#1491 第二刀，需求 app-update-and-version-policy.md §3.4「推荐更新」、判据 3）：首页卡片和会话列表横幅。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdateRecommendedUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the card names the version and size, and its buttons do what they say`() {
    val events = mutableListOf<String>()
    composeTestRule.setContent {
      SignalTheme {
        UpdateRecommendedContent(
          version = "0.1.5",
          sizeBytes = 120_000_000,
          notesUrl = "https://www.tellomi.app/changelog/#android",
          onUpdateNow = { events += "update" },
          onLater = { events += "later" },
          onLearnMore = { events += "notes:$it" }
        )
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateAvailable__sheet_title, "0.1.5")).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateAvailable__sheet_body_with_size, Formatter.formatShortFileSize(context, 120_000_000))).assertIsDisplayed()

    composeTestRule.onNodeWithTag(TellomiUpdateRecommendedTestTags.LEARN_MORE).performClick()
    composeTestRule.onNodeWithTag(TellomiUpdateRecommendedTestTags.UPDATE_NOW).performClick()
    composeTestRule.onNodeWithTag(TellomiUpdateRecommendedTestTags.LATER).performClick()

    assertThat(events).containsExactly("notes:https://www.tellomi.app/changelog/#android", "update", "later")
  }

  @Test
  fun `without a size or notes the card leaves them out`() {
    composeTestRule.setContent {
      SignalTheme {
        UpdateRecommendedContent(version = "0.1.5", sizeBytes = null, notesUrl = null)
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateAvailable__sheet_body)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiUpdateRecommendedTestTags.LEARN_MORE).assertDoesNotExist()
  }

  @Test
  fun `the banner offers the update, then the install once it is downloaded`() {
    val clicks = mutableListOf<String>()
    composeTestRule.setContent {
      SignalTheme {
        UpdateAvailableBanner(context) { clicks += "update" }.DisplayBanner(UpdateAvailableBanner.Model(version = "0.1.5", downloaded = false), PaddingValues(0.dp))
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateAvailable__banner, "0.1.5")).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateAvailable__update)).performClick()
    assertThat(clicks).containsExactly("update")
  }

  @Test
  fun `a downloaded update says so and offers to install`() {
    composeTestRule.setContent {
      SignalTheme {
        UpdateAvailableBanner(context) { }.DisplayBanner(UpdateAvailableBanner.Model(version = "0.1.5", downloaded = true), PaddingValues(0.dp))
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateAvailable__banner_downloaded, "0.1.5")).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.TellomiUpdateAvailable__install)).assertIsDisplayed()
  }
}
