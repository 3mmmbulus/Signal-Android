/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import java.io.File

/** 举报页：先分流「只是不想看到」、四组原因、紧急提示、截图必填。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedReportScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val groups = FeedReportReasons.groups(emptyMap())
  private fun reason(code: String) = groups.flatMap { it.reasons }.first { it.code == code }

  private var hidden = false
  private var picked: FeedReportReason? = null

  private fun show(state: FeedReportState, canHideInstead: Boolean = true) {
    composeTestRule.setContent {
      SignalTheme {
        FeedReportScreen(
          state = state,
          canHideInstead = canHideInstead,
          onBack = {},
          onNotInterested = { hidden = true },
          onPick = { picked = it },
          onBackToReasons = {},
          onDetailChanged = {},
          onRelationshipChanged = {},
          onEvidenceAdded = {},
          onEvidenceRemoved = {},
          onSubmit = {},
          onOpenHotline = {},
          onErrorShown = {}
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

  @Test
  fun `reason list starts with just hide, then the groups`() {
    show(FeedReportState(groups = groups, region = "cn"))

    composeTestRule.onNodeWithText("Harm to others").assertExists()
    composeTestRule.onNodeWithText("Harassment or bullying").assertExists()
    screenshot("report_reasons.png")

    composeTestRule.onNodeWithTag(FeedReportTestTags.NOT_INTERESTED).performClick()
    assertThat(hidden).isTrue()
    composeTestRule.onNodeWithText("Hate").performClick()
    assertThat(picked?.code).isEqualTo("hate")
  }

  @Test
  fun `reporting an author has no hide option`() {
    show(FeedReportState(groups = groups), canHideInstead = false)

    composeTestRule.onNodeWithTag(FeedReportTestTags.NOT_INTERESTED).assertDoesNotExist()
  }

  @Test
  fun `emergency reasons say to call 110 first in the mainland`() {
    show(FeedReportState(groups = groups, region = "cn", reason = reason("self_harm")))

    composeTestRule.onNodeWithText("call 110 first", substring = true).assertExists()
    composeTestRule.onNodeWithText("12377.cn", substring = true).assertExists()
  }

  @Test
  fun `fraud cannot be sent without screenshots`() {
    show(FeedReportState(groups = groups, region = "global", reason = reason("fraud")))

    composeTestRule.onNodeWithText("Screenshots (required)").assertExists()
    composeTestRule.onNodeWithTag(FeedReportTestTags.SUBMIT).assertIsNotEnabled()
    composeTestRule.onNodeWithText("12377.cn", substring = true).assertDoesNotExist()
    screenshot("report_fraud.png")
  }
}
