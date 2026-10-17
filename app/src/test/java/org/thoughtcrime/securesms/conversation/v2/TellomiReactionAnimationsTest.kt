/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.core.ui.motion.TellomiMotion
import java.io.File

/** Tellomi（交互审计 A-06）：回应条上会动的表情——素材都在、静止帧对、出场从静止帧播一圈回到静止帧、许可页有署名。和 iOS 同一套。 */
class TellomiReactionAnimationsTest {

  private val assets = File("src/main/assets")

  @Test
  fun `the default bar's ten emoji each have an asset with Noto's rest marker`() {
    val expectedRestFrames = mapOf(
      "❤️" to 75, "👍" to 0, "👎" to 0, "😂" to 116, "😮" to 0, "😢" to 61,
      "🙏" to 0, "🔥" to 0, "🎉" to 25, "👏" to 0
    )
    for ((emoji, restFrame) in expectedRestFrames) {
      val path = TellomiReactionAnimations.assetPath(emoji)
      assertThat(path, emoji).isNotNull()
      val json = File(assets, path!!).readText()
      assertThat(restMarkerFrame(json), emoji).isEqualTo(restFrame)
      assertThat(Regex(""""fr"\s*:\s*60\b""").containsMatchIn(json), emoji).isTrue()
    }
    assertThat(TellomiReactionAnimations.animatedEmoji.size).isEqualTo(expectedRestFrames.size)
    assertThat(File(assets, TellomiReactionAnimations.ASSET_DIRECTORY).list()!!.size).isEqualTo(expectedRestFrames.size)
  }

  @Test
  fun `lookup ignores the variation selector only`() {
    assertThat(TellomiReactionAnimations.assetPath("❤️")).isEqualTo("tellomi_reactions/2764_fe0f.json")
    assertThat(TellomiReactionAnimations.assetPath("❤")).isEqualTo("tellomi_reactions/2764_fe0f.json")
    assertThat(TellomiReactionAnimations.assetPath("👍🏽")).isNull()
    assertThat(TellomiReactionAnimations.assetPath("🤔")).isNull()
    assertThat(TellomiReactionAnimations.assetPath("❤️‍🔥")).isNull()
  }

  @Test
  fun `the appear plays one full turn from the rest frame back to it`() {
    assertThat(TellomiReactionAnimations.appearSegments(0f, 0f, 71f)).isEqualTo(listOf(0f..71f))
    assertThat(TellomiReactionAnimations.appearSegments(75f, 0f, 109f)).isEqualTo(listOf(75f..109f, 0f..75f))
    assertThat(TellomiReactionAnimations.appearSegments(109f, 0f, 109f)).isEqualTo(listOf(0f..109f))
  }

  @Test
  fun `only the full motion tier plays them`() {
    assertThat(TellomiReactionAnimations.isPlaybackAllowed(TellomiMotion.Tier.FULL)).isTrue()
    assertThat(TellomiReactionAnimations.isPlaybackAllowed(TellomiMotion.Tier.SIMPLIFIED)).isFalse()
    assertThat(TellomiReactionAnimations.isPlaybackAllowed(TellomiMotion.Tier.MINIMAL)).isFalse()
  }

  @Test
  fun `the licenses page credits Noto Emoji Animation under CC BY 4 0`() {
    val credit = File(assets, TellomiReactionAnimations.ACKNOWLEDGMENT_ASSET).readText()
    assertThat(credit).contains("Noto Emoji Animation")
    assertThat(credit).contains("Google")
    assertThat(credit).contains("CC BY 4.0")
    assertThat(credit).contains("https://creativecommons.org/licenses/by/4.0/")
  }

  /** The "tm" of the marker whose "cm" is "rest", whatever order the keys come in. */
  private fun restMarkerFrame(json: String): Int? {
    val marker = Regex("""\{[^{}]*"cm"\s*:\s*"rest"[^{}]*\}""").find(json)?.value ?: return null
    return Regex(""""tm"\s*:\s*([0-9.]+)""").find(marker)?.groupValues?.get(1)?.toDouble()?.toInt()
  }
}
