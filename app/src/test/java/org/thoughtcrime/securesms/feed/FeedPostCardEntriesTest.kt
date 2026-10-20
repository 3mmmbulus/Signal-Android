/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard

/** 能力检查（契约 3.0）：没部署点赞就不画点赞按钮。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1600dp-xxhdpi")
class FeedPostCardEntriesTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val card = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "x", author = FeedAuthor(profileId = "p1", displayName = "Anan"))

  @Test
  fun `like hidden when reactions are not deployed`() {
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(LocalFeedEntries provides FeedEntries.NONE.copy(feed = true)) {
          FeedPostCard(card = card, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
        }
      }
    }
    composeTestRule.onAllNodesWithContentDescription("Like").assertCountEquals(0)
  }

  @Test
  fun `like shown when reactions are deployed`() {
    composeTestRule.setContent {
      SignalTheme {
        CompositionLocalProvider(LocalFeedEntries provides FeedEntries.ALL) {
          FeedPostCard(card = card, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
        }
      }
    }
    composeTestRule.onAllNodesWithContentDescription("Like").assertCountEquals(1)
  }
}
