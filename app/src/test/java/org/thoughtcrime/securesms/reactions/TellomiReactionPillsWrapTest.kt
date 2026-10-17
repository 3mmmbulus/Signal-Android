/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.emoji.EmojiSource
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Tellomi（交互审计 A-07，owner 2026-10-17）：消息下面的回应胶囊——每个回应一格，放不下就换行，行朝屏幕中间那一侧对齐。
 * iOS 同一刀是 Signal-iOS#284。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiReactionPillsWrapTest {

  @Test
  fun `every reaction keeps its own pill, most popular first`() {
    val source = mockk<EmojiSource> { every { variationsToCanonical } returns emptyMap() }
    mockkObject(EmojiSource) {
      every { EmojiSource.latest } returns source

      val me = RecipientId.from(1)
      val other = RecipientId.from(2)
      val records = listOf("👍", "😂", "😮", "🔥", "❤️").mapIndexed { i, emoji -> ReactionRecord(emoji, other, i.toLong(), i.toLong()) } +
        ReactionRecord("❤️", me, 9, 9)

      val reactions = ReactionsConversationView.buildSortedReactionsList(records, me)

      assertEquals("five reactions, five pills (the old rail folded everything after two into \"+N\")", 5, reactions.size)
      assertEquals("❤️", reactions[0].baseEmoji)
      assertEquals(2, reactions[0].count)
      assertTrue(reactions[0].isUserWasSender)
    }
  }

  @Test
  fun `pills wrap into rows lined up towards the middle of the screen`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val rail = ReactionsConversationView(context).apply {
      // In a message row the rail always sits in a parent that gives it margin layout params.
      layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    repeat(6) { rail.addView(View(context).apply { layoutParams = FlexboxLayout.LayoutParams(60, 26) }) }

    rail.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))

    assertEquals("three 60 px pills fit in 200 px, the rest wrap", 2, rail.flexLines.size)
    assertEquals(26 * 2 + ReactionsConversationView.ROW_GAP, rail.measuredHeight)
    assertEquals("incoming pills hang from the bubble's end", JustifyContent.FLEX_END, rail.justifyContent)
  }

  @Test
  fun `rows wrap at the bubble's width but keep about three pills`() {
    val outer = 5 * 2 // OUTER_MARGIN either side, in px at Robolectric's density
    assertEquals(400 - outer, ReactionsConversationView.maxRailWidth(400, 0))
    assertEquals(ReactionsConversationView.MIN_RAIL_WIDTH, ReactionsConversationView.maxRailWidth(60, 0))
    assertEquals("never wider than the row", 300, ReactionsConversationView.maxRailWidth(500, 300))
  }

  @Test
  fun `a rail wider than its bubble moves towards the middle of the screen`() {
    // Outgoing (bubble on the right): pinned at the bubble's start, so the overflow goes left.
    assertEquals(-50f, ReactionsConversationView.overflowShift(150, 110, true, true))
    // Incoming (bubble on the left): pinned at the bubble's end, so it goes right.
    assertEquals(50f, ReactionsConversationView.overflowShift(150, 110, false, true))
    // Right to left mirrors it.
    assertEquals(50f, ReactionsConversationView.overflowShift(150, 110, true, false))
    // A rail that fits does not move.
    assertEquals(0f, ReactionsConversationView.overflowShift(90, 110, true, true))
  }
}
