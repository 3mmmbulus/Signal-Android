/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.view.View
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 审计 4.2 UI：出现一次 / 点了重播 / 关键帧动画挂到 View。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiSingleEmojiAnimationPlayerTest {

  @After
  fun tearDown() {
    TellomiSingleEmojiAnimationPlayer.resetSharedForTests()
  }

  @Test
  fun `appear plays once per message`() {
    val player = TellomiSingleEmojiAnimationPlayer()
    assertThat(player.consumeAppear(messageId = 1L, jumbomojiCount = 1, reduceMotion = false)).isTrue()
    assertThat(player.consumeAppear(messageId = 1L, jumbomojiCount = 1, reduceMotion = false)).isFalse()
    assertThat(player.consumeAppear(messageId = 2L, jumbomojiCount = 1, reduceMotion = false)).isTrue()
  }

  @Test
  fun `non single emoji and reduce motion never recorded`() {
    val player = TellomiSingleEmojiAnimationPlayer()
    assertThat(player.consumeAppear(messageId = 1L, jumbomojiCount = 2, reduceMotion = false)).isFalse()
    assertThat(player.consumeAppear(messageId = 1L, jumbomojiCount = null, reduceMotion = false)).isFalse()
    assertThat(player.consumeAppear(messageId = 1L, jumbomojiCount = 1, reduceMotion = true)).isFalse()
    assertThat(player.hasPlayedOnAppear(1L)).isFalse()
  }

  @Test
  fun `tap always replays unless reduce motion`() {
    val player = TellomiSingleEmojiAnimationPlayer()
    player.consumeAppear(messageId = 1L, jumbomojiCount = 1, reduceMotion = false)
    assertThat(player.shouldPlayOnTap(jumbomojiCount = 1, reduceMotion = false)).isTrue()
    assertThat(player.shouldPlayOnTap(jumbomojiCount = 1, reduceMotion = false)).isTrue()
    assertThat(player.shouldPlayOnTap(jumbomojiCount = 1, reduceMotion = true)).isFalse()
    assertThat(player.shouldPlayOnTap(jumbomojiCount = 3, reduceMotion = false)).isFalse()
  }

  @Test
  fun `animator matches keyframes`() {
    val view = View(RuntimeEnvironment.getApplication())
    val animator = TellomiSingleEmojiAnimationPlayer.makeAnimator(view)
    assertThat(animator.duration).isEqualTo(TellomiSingleEmojiAnimation.DURATION_MS)
    assertThat(animator.values).isNotNull()
    assertThat(animator.values!!.size).isEqualTo(2)
  }

  @Test
  fun `play starts animator and cancel resets scale`() {
    val view = View(RuntimeEnvironment.getApplication())
    view.scaleX = 2f
    view.scaleY = 2f
    TellomiSingleEmojiAnimationPlayer.play(view)
    // First keyframe is 0.6; ObjectAnimator applies it on start.
    assertThat(view.scaleX).isEqualTo(0.6f)
    assertThat(view.scaleY).isEqualTo(0.6f)
    TellomiSingleEmojiAnimationPlayer.cancel(view)
    assertThat(view.scaleX).isEqualTo(1f)
    assertThat(view.scaleY).isEqualTo(1f)
  }
}
