/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.signal.core.ui.motion.TellomiMotion
import java.time.Duration

/**
 * Tellomi (interaction audit A-27): switching the chat list to search results and back fades the new content in instead of swapping it in
 * one frame. Parameters follow Telegram Android's `DialogsActivity.showSearch`: search results settle from 1.05, the chat list from 0.95,
 * 200 / 180 ms ease-out; the simplified tier only fades (Telegram's low-end path), the minimal tier does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSearchResultsTransitionTest {

  @Test
  fun full_tier_scales_and_fades_like_telegram() {
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = true, tier = TellomiMotion.Tier.FULL))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 200L, startScale = 1.05f))
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = false, tier = TellomiMotion.Tier.FULL))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 180L, startScale = 0.95f))
  }

  @Test
  fun simplified_tier_only_fades() {
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = true, tier = TellomiMotion.Tier.SIMPLIFIED))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 200L, startScale = 1f))
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = false, tier = TellomiMotion.Tier.SIMPLIFIED))
      .isEqualTo(TellomiSearchResultsTransition.Spec(animate = true, durationMs = 180L, startScale = 1f))
  }

  @Test
  fun minimal_tier_does_not_animate() {
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = true, tier = TellomiMotion.Tier.MINIMAL).animate).isEqualTo(false)
    assertThat(TellomiSearchResultsTransition.specFor(toSearch = false, tier = TellomiMotion.Tier.MINIMAL).animate).isEqualTo(false)
  }

  /** Search results arrive asynchronously: the list stays hidden until the first page lands, then fades in (not over a blank list). */
  @Test
  fun empty_search_results_wait_for_the_first_page_before_fading_in() {
    val list = View(ApplicationProvider.getApplicationContext<Application>())
    val adapter = CountingAdapter()

    TellomiSearchResultsTransition.playWhenFilled(list, adapter, toSearch = true, timeoutMs = 400L)
    assertThat(list.alpha).isEqualTo(0f)
    assertThat(adapter.hasObservers()).isEqualTo(true)

    adapter.count = 3
    adapter.notifyItemRangeInserted(0, 3)

    assertThat(adapter.hasObservers()).isEqualTo(false)
    assertThat(list.animate().duration).isEqualTo(200L)
  }

  @Test
  fun no_results_still_fade_in_after_the_timeout() {
    val list = View(ApplicationProvider.getApplicationContext<Application>())
    val adapter = CountingAdapter()

    TellomiSearchResultsTransition.playWhenFilled(list, adapter, toSearch = true, timeoutMs = 400L)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(399))
    assertThat(adapter.hasObservers()).isEqualTo(true)

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertThat(adapter.hasObservers()).isEqualTo(false)
    assertThat(list.animate().duration).isEqualTo(200L)
  }

  @Test
  fun cancelling_a_pending_fade_stops_listening_and_shows_the_list() {
    val list = View(ApplicationProvider.getApplicationContext<Application>())
    val adapter = CountingAdapter()

    val cancel = TellomiSearchResultsTransition.playWhenFilled(list, adapter, toSearch = true, timeoutMs = 400L)
    cancel.run()

    assertThat(adapter.hasObservers()).isEqualTo(false)
    assertThat(list.alpha).isEqualTo(1f)
  }

  @Test
  fun a_list_that_already_has_items_fades_in_right_away() {
    val list = View(ApplicationProvider.getApplicationContext<Application>())
    val adapter = CountingAdapter().apply { count = 5 }

    TellomiSearchResultsTransition.playWhenFilled(list, adapter, toSearch = false, timeoutMs = 400L)

    assertThat(adapter.hasObservers()).isEqualTo(false)
    assertThat(list.animate().duration).isEqualTo(180L)
  }

  private class CountingAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var count = 0
    override fun getItemCount() = count
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(View(parent.context)) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
  }

  @Test
  fun play_queues_the_fade_in_with_the_tier_duration() {
    val list = View(ApplicationProvider.getApplicationContext<Application>())

    TellomiSearchResultsTransition.play(list, toSearch = true)
    assertThat(list.animate().duration).isEqualTo(200L)

    TellomiSearchResultsTransition.play(list, toSearch = false)
    assertThat(list.animate().duration).isEqualTo(180L)
  }
}
