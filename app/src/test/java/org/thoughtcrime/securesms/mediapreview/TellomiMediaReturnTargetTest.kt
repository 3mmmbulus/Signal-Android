/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.thoughtcrime.securesms.components.ThumbnailView
import java.time.Duration

/** Tellomi (interaction audit A-20): which viewer may ask the conversation for another thumbnail to close onto. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiMediaReturnTargetTest {

  private val finder: (Uri) -> ThumbnailView? = { null }

  @Test
  fun `the viewer opened right after the conversation registered claims it once`() {
    TellomiMediaReturnTarget.register(finder)

    assertTrue(TellomiMediaReturnTarget.claim())
    assertFalse("a second viewer does not get it", TellomiMediaReturnTarget.claim())
  }

  @Test
  fun `a viewer opened long after the registration does not claim it`() {
    TellomiMediaReturnTarget.register(finder)
    ShadowSystemClock.advanceBy(Duration.ofSeconds(3))

    assertFalse(TellomiMediaReturnTarget.claim())
  }

  @Test
  fun `nothing is found when the conversation has no thumbnail on screen`() {
    TellomiMediaReturnTarget.register(finder)

    assertNull(TellomiMediaReturnTarget.find(Uri.parse("content://media/1")))
  }
}
