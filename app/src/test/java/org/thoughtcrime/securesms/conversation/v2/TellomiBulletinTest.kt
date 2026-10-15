/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import com.google.android.material.snackbar.Snackbar
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.google.android.material.R as MaterialR

/** Tellomi (interaction audit A-30): chat notices sit in the host above the composer and can carry an action. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiBulletinTest {

  private fun host(): CoordinatorLayout {
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(MaterialR.style.Theme_Material3_DayNight_NoActionBar)
    val activity = controller.setup().get()
    val host = CoordinatorLayout(activity)
    activity.setContentView(host)
    shadowOf(Looper.getMainLooper()).idle()
    return host
  }

  @Test
  fun `the notice is shown inside the host, not at the bottom of the whole window`() {
    val host = host()

    val bulletin = TellomiBulletin.show(host, "Saved")
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(bulletin.view.parent).isSameInstanceAs(host)
  }

  @Test
  fun `its action runs the given callback`() {
    val host = host()
    var tapped = false

    val bulletin = TellomiBulletin.show(host, "Saved", actionText = "View") { tapped = true }
    shadowOf(Looper.getMainLooper()).idle()
    bulletin.view.findViewById<android.widget.Button>(MaterialR.id.snackbar_action).performClick()

    assertThat(tapped).isTrue()
  }

  @Test
  fun `short and long toasts become short and long notices`() {
    assertThat(TellomiBulletin.durationForToast(Toast.LENGTH_SHORT)).isEqualTo(Snackbar.LENGTH_SHORT)
    assertThat(TellomiBulletin.durationForToast(Toast.LENGTH_LONG)).isEqualTo(Snackbar.LENGTH_LONG)
  }
}
