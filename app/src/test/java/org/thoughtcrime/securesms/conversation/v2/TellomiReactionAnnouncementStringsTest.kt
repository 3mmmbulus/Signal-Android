/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/** Tellomi (interaction audit A-07): what TalkBack says after choosing or taking back a reaction. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiReactionAnnouncementStringsTest {

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Test
  fun `english announcement`() {
    assertThat(context.getString(R.string.ConversationFragment__tellomi_reacted_with, "❤️")).isEqualTo("Reacted with ❤️")
    assertThat(context.getString(R.string.ConversationFragment__tellomi_reaction_removed)).isEqualTo("Reaction removed")
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese announcement`() {
    assertThat(context.getString(R.string.ConversationFragment__tellomi_reacted_with, "❤️")).isEqualTo("已回应 ❤️")
    assertThat(context.getString(R.string.ConversationFragment__tellomi_reaction_removed)).isEqualTo("已取消回应")
  }

  @Test
  @Config(qualifiers = "yue")
  fun `cantonese announcement`() {
    assertThat(context.getString(R.string.ConversationFragment__tellomi_reacted_with, "❤️")).isEqualTo("已回應 ❤️")
    assertThat(context.getString(R.string.ConversationFragment__tellomi_reaction_removed)).isEqualTo("已取消回應")
  }
}
