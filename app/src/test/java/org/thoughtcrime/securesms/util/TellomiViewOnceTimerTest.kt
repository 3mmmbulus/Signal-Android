/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import androidx.core.net.toUri
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.thoughtcrime.securesms.mediasend.TellomiTimedSends
import org.thoughtcrime.securesms.mediasend.TellomiTimedSends.Part
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.TellomiDataMessage

/**
 * ADR-0079 (tellomi/tellomi#1714): a timed view once is remembered from what arrives, the viewer stays open N seconds (a video
 * max(duration, N)), and a batch with timers is sent one by one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiViewOnceTimerTest {

  @Test
  fun `only the allowed seconds on a view once message count`() {
    assertThat(TellomiViewOnceTimer.seconds(DataMessage(isViewOnce = true, tellomi = TellomiDataMessage(viewOnceSeconds = 10)))).isEqualTo(10)
    assertThat(TellomiViewOnceTimer.seconds(DataMessage(isViewOnce = false, tellomi = TellomiDataMessage(viewOnceSeconds = 10)))).isNull()
    assertThat(TellomiViewOnceTimer.seconds(DataMessage(tellomi = TellomiDataMessage(viewOnceSeconds = 10)))).isNull()
    for (odd in listOf(0, 5, 60)) {
      assertThat(TellomiViewOnceTimer.seconds(DataMessage(isViewOnce = true, tellomi = TellomiDataMessage(viewOnceSeconds = odd)))).isNull()
    }
    assertThat(TellomiViewOnceTimer.seconds(DataMessage(isViewOnce = true))).isNull()
  }

  @Test
  fun `a received timed view once is kept in the message extras, with the caption flags`() {
    val extras = TellomiCaptionAbove.extrasFrom(DataMessage(isViewOnce = true, tellomi = TellomiDataMessage(viewOnceSeconds = 30)))
    assertThat(TellomiViewOnceTimer.seconds(extras)).isEqualTo(30)
    assertThat(TellomiCaptionAbove.isCaptionAboveMedia(extras)).isFalse()

    assertThat(TellomiCaptionAbove.extrasFrom(DataMessage(isViewOnce = true))).isNull()
    assertThat(TellomiCaptionAbove.extras(captionAboveMedia = false, viewOnceSeconds = 7)).isNull()
    assertThat(TellomiViewOnceTimer.seconds(TellomiCaptionAbove.extras(captionAboveMedia = false, viewOnceSeconds = 3))).isEqualTo(3)
  }

  @Test
  fun `the viewer stays open for the seconds or the whole video`() {
    assertThat(TellomiViewOnceTimer.viewerDurationMillis(10, null)).isEqualTo(10_000L)
    assertThat(TellomiViewOnceTimer.viewerDurationMillis(10, 4_000L)).isEqualTo(10_000L)
    assertThat(TellomiViewOnceTimer.viewerDurationMillis(3, 12_500L)).isEqualTo(12_500L)
  }

  @Test
  fun `with a timer the batch goes one by one and the caption rides on the last untimed`() {
    val none = TellomiTimedSends.NONE
    assertThat(TellomiTimedSends.parts(listOf(none, 10, none), hasBody = true)).containsExactly(
      Part(index = 0, isViewOnce = false, seconds = null, hasBody = false),
      Part(index = 1, isViewOnce = true, seconds = 10, hasBody = false),
      Part(index = 2, isViewOnce = false, seconds = null, hasBody = true)
    )
  }

  @Test
  fun `all timed puts the caption first as its own text`() {
    assertThat(TellomiTimedSends.parts(listOf(TellomiTimedSends.VIEW_ONCE, 30), hasBody = true)).containsExactly(
      Part(index = null, isViewOnce = false, seconds = null, hasBody = true),
      Part(index = 0, isViewOnce = true, seconds = null, hasBody = false),
      Part(index = 1, isViewOnce = true, seconds = 30, hasBody = false)
    )
    assertThat(TellomiTimedSends.parts(listOf(3), hasBody = false)).containsExactly(Part(index = 0, isViewOnce = true, seconds = 3, hasBody = false))
  }

  @Test
  fun `choices follow the media to what is actually sent`() {
    val a = media("content://media/a")
    val b = media("content://media/b")
    val rendered = media("content://blob/b-render")
    assertThat(TellomiTimedSends.renderedChoices(listOf(a, b), mapOf(a to a, b to rendered), mapOf(b.uri to 10))).isEqualTo(mapOf(rendered.uri to 10))
    assertThat(TellomiTimedSends.renderedChoices(listOf(a), mapOf(a to a), emptyMap())).isEqualTo(emptyMap())
  }

  private fun media(uri: String) = Media(
    uri = uri.toUri(),
    contentType = "image/jpeg",
    date = 0,
    width = 100,
    height = 100,
    size = 1024,
    duration = 0,
    isBorderless = false,
    isVideoGif = false,
    bucketId = null,
    caption = null,
    transformProperties = null,
    fileName = null
  )
}
