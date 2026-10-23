package org.thoughtcrime.securesms.conversation.colors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.archive.proto.ChatStyle
import org.thoughtcrime.securesms.backup.v2.util.toRemote
import org.thoughtcrime.securesms.database.model.databaseprotos.ChatColor

class LegacyUltramarineTest {

  private val legacy = ChatColor(singleColor = ChatColor.SingleColor(color = 0xFF315FF4.toInt()))

  @Test
  fun builtInLegacySolidLoadsAsNewUltramarine() {
    val colors = ChatColors.forChatColor(ChatColors.Id.BuiltIn, legacy)
    assertEquals(ChatColorsPalette.Bubbles.ULTRAMARINE, colors)
    assertTrue(colors.isGradient())
    assertNull(colors.singleColor)
  }

  @Test
  fun autoLegacySolidKeepsAutoIdButUsesGradient() {
    val colors = ChatColors.forChatColor(ChatColors.Id.Auto, legacy)
    assertEquals(ChatColors.Id.Auto, colors.id)
    assertEquals(ChatColorsPalette.Bubbles.ULTRAMARINE.linearGradient, colors.linearGradient)
  }

  @Test
  fun customSolidSameValueIsUntouched() {
    val colors = ChatColors.forChatColor(ChatColors.Id.Custom(7), legacy)
    assertEquals(0xFF315FF4.toInt(), colors.singleColor)
    assertNotEquals(ChatColorsPalette.Bubbles.ULTRAMARINE, colors)
  }

  @Test
  fun otherBuiltInSolidsUnchanged() {
    val crimson = ChatColorsPalette.Bubbles.CRIMSON
    assertEquals(crimson, ChatColors.forChatColor(ChatColors.Id.BuiltIn, crimson.serialize()))
    assertFalse(ChatColors.isLegacyUltramarine(ChatColors.Id.BuiltIn, crimson.singleColor!!))
  }

  @Test
  fun backupExportsLegacySolidAsUltramarinePreset() {
    val raw = ChatColors.forColor(ChatColors.Id.BuiltIn, 0xFF315FF4.toInt())
    assertEquals(ChatStyle.BubbleColorPreset.SOLID_ULTRAMARINE, raw.toRemote())
    assertEquals(ChatStyle.BubbleColorPreset.SOLID_ULTRAMARINE, ChatColorsPalette.Bubbles.ULTRAMARINE.toRemote())
  }

  @Test
  fun backupRoundTripOfLoadedLegacyColor() {
    val loaded = ChatColors.forChatColor(ChatColors.Id.BuiltIn, legacy)
    assertEquals(ChatStyle.BubbleColorPreset.SOLID_ULTRAMARINE, loaded.toRemote())
  }
}
