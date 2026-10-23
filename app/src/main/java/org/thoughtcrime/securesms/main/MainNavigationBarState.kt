/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

/**
 * State of the main navigation bar or rail.
 *
 * @param destinations the tabs to display, in order. Whoever builds this state decides which ones the user
 *   gets; the bar and the rail display exactly what they are handed.
 */
data class MainNavigationBarState(
  val chatsCount: Int = 0,
  val callsCount: Int = 0,
  val storiesCount: Int = 0,
  val storyFailure: Boolean = false,
  val destinations: List<MainListRoute> = ALL_DESTINATIONS,
  val currentListLocation: MainListRoute = MainListRoute.Chats,
  val compact: Boolean = false
) {
  companion object {
    /**
     * Every tab there is, in display order. The archive is not among them: it is a list within chats
     * rather than a tab of its own.
     */
    val ALL_DESTINATIONS = listOf(MainListRoute.Feed, MainListRoute.Chats, MainListRoute.Calls, MainListRoute.Friends, MainListRoute.Me, MainListRoute.Stories)

    /**
     * Tellomi（tellomi/tellomi#1355）：底栏显示哪些标签，和 iOS `HomeTabBarController.tabsToShow` 同一套规则：
     * 动态（开了才有）、聊天、通话，[showsFriendsAndMe] 时再加好友、我——这时不显示快拍，底栏正好五个
     * 「动态、聊天、通话、好友、我」。打开 App 默认仍是聊天（[MainListRoute.Chats]）。
     */
    @JvmStatic
    fun tabsToShow(areStoriesEnabled: Boolean, isFeedEnabled: Boolean, showsFriendsAndMe: Boolean): List<MainListRoute> {
      return ALL_DESTINATIONS.filter { destination ->
        when (destination) {
          MainListRoute.Feed -> isFeedEnabled
          MainListRoute.Friends, MainListRoute.Me -> showsFriendsAndMe
          MainListRoute.Stories -> areStoriesEnabled && !showsFriendsAndMe
          else -> true
        }
      }
    }
  }
}
