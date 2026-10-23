/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1355）：底栏标签和 iOS `HomeTabBarController.tabsToShow` 一致。
 */
class TellomiTabsToShowTest {

  @Test
  fun `friends and me on gives feed chats calls friends me, no stories`() {
    assertEquals(
      listOf(MainListRoute.Feed, MainListRoute.Chats, MainListRoute.Calls, MainListRoute.Friends, MainListRoute.Me),
      MainNavigationBarState.tabsToShow(areStoriesEnabled = true, isFeedEnabled = true, showsFriendsAndMe = true)
    )
  }

  @Test
  fun `friends and me off keeps the old bar`() {
    assertEquals(
      listOf(MainListRoute.Feed, MainListRoute.Chats, MainListRoute.Calls, MainListRoute.Stories),
      MainNavigationBarState.tabsToShow(areStoriesEnabled = true, isFeedEnabled = true, showsFriendsAndMe = false)
    )
    assertEquals(
      listOf(MainListRoute.Chats, MainListRoute.Calls),
      MainNavigationBarState.tabsToShow(areStoriesEnabled = false, isFeedEnabled = false, showsFriendsAndMe = false)
    )
  }

  @Test
  fun `archive is never a tab and chats is always there`() {
    for (stories in listOf(true, false)) {
      for (feed in listOf(true, false)) {
        for (friends in listOf(true, false)) {
          val tabs = MainNavigationBarState.tabsToShow(stories, feed, friends)
          assertEquals(false, MainListRoute.Archive in tabs)
          assertEquals(true, MainListRoute.Chats in tabs)
        }
      }
    }
  }

  @Test
  fun `default tab is chats`() {
    assertEquals(MainListRoute.Chats, MainNavigationBarState().currentListLocation)
  }
}
