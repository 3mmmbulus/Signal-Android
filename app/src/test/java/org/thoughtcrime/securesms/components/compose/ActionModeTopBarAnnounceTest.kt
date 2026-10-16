/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.compose

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme

/**
 * Tellomi（交互审计 A-15「读屏公告」）：会话页多选的标题「已选 N 条」是礼貌模式的实时区域，数字一变读屏就念；别处用这个顶栏照旧不念。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActionModeTopBarAnnounceTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  fun `the selected count is a polite live region when asked`() {
    composeTestRule.setContent {
      SignalTheme {
        ActionModeTopBar(title = "3 selected", onCloseClick = {}, announceTitleChanges = true)
      }
    }

    val liveRegion = composeTestRule.onNodeWithText("3 selected").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
    assertThat(liveRegion).isEqualTo(LiveRegionMode.Polite)
  }

  @Test
  fun `other users of the bar are not announced`() {
    composeTestRule.setContent {
      SignalTheme {
        ActionModeTopBar(title = "3 selected", onCloseClick = {})
      }
    }

    val liveRegion = composeTestRule.onNodeWithText("3 selected").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
    assertThat(liveRegion).isNull()
  }
}
