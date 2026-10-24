/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.recipients.ui.RecipientPicker
import java.util.Locale

/** 底栏「好友」：会话压在哪个栈上、只列个人、顶部入口何时显示、邀请文字（和 iOS cb1f5f4f 同一句）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiFriendsTabTest {

  @Test
  fun `conversation opened from friends goes on the friends stack, everything else on chats`() {
    assertEquals(MainListRoute.Friends, MainNavigationViewModel.chatsDetailRoot(MainListRoute.Friends))
    for (route in MainListRoute.entries.filter { it != MainListRoute.Friends }) {
      assertEquals(route.name, MainListRoute.Chats, MainNavigationViewModel.chatsDetailRoot(route))
    }
  }

  @Test
  fun `friends list only has people, no groups and not self`() {
    assertEquals(setOf(RecipientPicker.DisplayMode.PUSH), TellomiFriendsTab.DISPLAY_MODES)
  }

  @Test
  fun `header rows are hidden while searching`() {
    assertTrue(TellomiFriendsTab.showsHeaderRows(""))
    assertTrue(TellomiFriendsTab.showsHeaderRows("  "))
    assertFalse(TellomiFriendsTab.showsHeaderRows("kai"))
  }

  @Test
  fun `invite text ends with the download link`() {
    val context: Context = ApplicationProvider.getApplicationContext()
    val en = TellomiFriendsTab.inviteText(localized(context, Locale.ENGLISH))
    assertEquals("I'm inviting you to install Tellomi! Here is the link: https://tellomi.app/download/", en)
    val zh = TellomiFriendsTab.inviteText(localized(context, Locale.SIMPLIFIED_CHINESE))
    assertEquals("请你下载Tellomi加密通讯软件！链接：https://tellomi.app/download/", zh)
  }

  private fun localized(context: Context, locale: Locale): Context {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    return context.createConfigurationContext(configuration)
  }
}
