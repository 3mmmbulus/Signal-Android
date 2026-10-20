/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailActivity
import org.thoughtcrime.securesms.feed.net.FeedCard

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedShareChooserTest {

  @Before
  fun setUp() = FeedShareChooser.reset()

  @After
  fun tearDown() = FeedShareChooser.reset()

  @Test
  fun `chosen target reports completed once`() {
    val got = mutableListOf<FeedShareOutcome>()
    val token = FeedShareChooser.register { got += it }
    assertThat(FeedShareChooser.deliver(token, chosen = true)).isTrue()
    assertThat(FeedShareChooser.deliver(token, chosen = true)).isFalse()
    assertThat(got).containsExactly(FeedShareOutcome.COMPLETED)
  }

  @Test
  fun `no chosen component reports nothing`() {
    val got = mutableListOf<FeedShareOutcome>()
    val token = FeedShareChooser.register { got += it }
    FeedShareChooser.deliver(token, chosen = false)
    assertThat(got).isEmpty()
    assertThat(FeedShareChooser.pendingCount()).isEqualTo(0)
  }

  @Test
  fun `unknown or null token is ignored`() {
    val got = mutableListOf<FeedShareOutcome>()
    FeedShareChooser.register { got += it }
    assertThat(FeedShareChooser.deliver(null, chosen = true)).isFalse()
    assertThat(FeedShareChooser.deliver("nope", chosen = true)).isFalse()
    assertThat(got).isEmpty()
  }

  @Test
  fun `cancelled shares do not pile up`() {
    val first = FeedShareChooser.register { error("evicted") }
    repeat(FeedShareChooser.MAX_PENDING) { FeedShareChooser.register { } }
    assertThat(FeedShareChooser.pendingCount()).isEqualTo(FeedShareChooser.MAX_PENDING)
    assertThat(FeedShareChooser.deliver(first, chosen = true)).isFalse()
  }

  @Test
  fun `receiver maps chosen component to completed`() {
    val got = mutableListOf<FeedShareOutcome>()
    val token = FeedShareChooser.register { got += it }
    val context = ApplicationProvider.getApplicationContext<Application>()
    FeedShareChosenReceiver().onReceive(
      context,
      Intent(FeedShareChooser.ACTION)
        .putExtra(FeedShareChooser.EXTRA_TOKEN, token)
        .putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName("com.example", "com.example.Share"))
    )
    assertThat(got).containsExactly(FeedShareOutcome.COMPLETED)
  }

  @Test
  fun `receiver ignores other actions`() {
    val got = mutableListOf<FeedShareOutcome>()
    val token = FeedShareChooser.register { got += it }
    FeedShareChosenReceiver().onReceive(
      ApplicationProvider.getApplicationContext(),
      Intent("other").putExtra(FeedShareChooser.EXTRA_TOKEN, token).putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName("a", "b"))
    )
    assertThat(got).isEmpty()
  }

  @Test
  fun `share to apps starts a chooser with an intent sender`() {
    // 真实调用方是详情页（Activity 的 Context）；Application 的 Context 起 Activity 要 FLAG_ACTIVITY_NEW_TASK
    val context = Robolectric.buildActivity(Activity::class.java).setup().get()
    FeedShare.shareToApps(context, FeedCard(id = "p1", kind = FeedCard.KIND_POST, shareable = true)) { }
    val started = Shadows.shadowOf(context).nextStartedActivity
    assertThat(started.action).isEqualTo(Intent.ACTION_CHOOSER)
    @Suppress("DEPRECATION")
    assertThat(started.getParcelableExtra<android.content.IntentSender>(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER)).isNotNull()
    assertThat(FeedShareChooser.pendingCount()).isEqualTo(1)
  }

  @Test
  fun `detail intent keeps the transient outer fields`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val card = FeedCard(id = "p1", kind = FeedCard.KIND_POST, text = "hi", shareable = true, pinned = true, held = true)
    val back = FeedPostDetailActivity.cardFrom(FeedPostDetailActivity.intent(context, card))!!
    assertThat(back.shareable).isTrue()
    assertThat(back.pinned).isTrue()
    assertThat(back.held).isTrue()
    val plain = FeedPostDetailActivity.cardFrom(FeedPostDetailActivity.intent(context, card.copy(shareable = false, pinned = false, held = false)))!!
    assertThat(plain.shareable).isFalse()
    assertThat(plain.pinned).isFalse()
  }
}
