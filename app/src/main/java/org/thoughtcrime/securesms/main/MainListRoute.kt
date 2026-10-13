/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import org.signal.core.ui.compose.split.ListNavKey
import org.thoughtcrime.securesms.R

/**
 * Main activity tabs (chat, stories, calls) that the user can select between. This is basically tied to something we can show in the 'list'
 * panel of the main activity.
 */
@Serializable
enum class MainListRoute(
  @get:StringRes val label: Int,
  @get:RawRes val icon: Int,
  @get:StringRes val contentDescription: Int = label
) : ListNavKey {
  /**
   * Tellomi（M11 动态，tellomi/tellomi#1355）：社交动态。底栏最左（`dynamic-feed.md` 第三节第 17 条），
   * 但打开 App 仍默认落在聊天。图标是静态矢量图，不是 Lottie：[icon] 为 0，见 [MainNavigationBar] 的 `NavigationDestinationIcon`。
   */
  Feed(
    label = R.string.ConversationListTabs__tellomi_feed,
    icon = 0
  ),
  Chats(
    label = R.string.ConversationListTabs__chats,
    icon = R.raw.chats_28
  ),
  Archive(
    label = R.string.ConversationListTabs__chats,
    icon = R.raw.chats_28
  ),
  Calls(
    label = R.string.ConversationListTabs__calls,
    icon = R.raw.calls_28
  ),
  Stories(
    label = R.string.ConversationListTabs__stories,
    icon = R.raw.stories_28
  );

  val isChatsTab: Boolean
    get() = this == Chats || this == Archive

  /**
   * The tab this list location is displayed under. The archive is a list pushed onto the chats stack
   * rather than a tab of its own.
   */
  val tab: MainListRoute
    get() = if (isChatsTab) Chats else this
}
