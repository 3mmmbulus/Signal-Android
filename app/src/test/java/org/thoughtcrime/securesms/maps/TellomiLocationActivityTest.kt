/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.maps

import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.io.File

/**
 * Spec attachment-files-location L-11 (Android): the location page. Name or "Location" as the title, "address • distance" (no
 * distance without permission, none asked for), "Directions" → nothing installed: a note; one app: opened; several: a list.
 * Copy address. The page is drawn to `app/build/tellomi-location/page.png` (`TELLOMI_LOCATION_DIR` moves it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiLocationActivityTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val place = TellomiLocationMessage(31.2297, 121.456, "星巴克臻选上海烘焙工坊", "静安区南京西路789号兴业太古汇")

  @Before
  fun setUp() {
    every { signalStore.settings.theme } returns SettingsValues.Theme.LIGHT
  }

  private fun launch(location: TellomiLocationMessage = place): TellomiLocationActivity {
    val context = ApplicationProvider.getApplicationContext<Application>()
    return Robolectric.buildActivity(TellomiLocationActivity::class.java, TellomiLocationActivity.intent(context, location, null)).setup().get()
  }

  private fun install(packageName: String, scheme: String) {
    val packageManager = shadowOf(ApplicationProvider.getApplicationContext<Application>().packageManager)
    val component = ComponentName(packageName, "$packageName.Main")
    packageManager.addActivityIfNotPresent(component)
    packageManager.addIntentFilterForActivity(
      component,
      IntentFilter(Intent.ACTION_VIEW).apply {
        addDataScheme(scheme)
        addCategory(Intent.CATEGORY_DEFAULT)
      }
    )
  }

  @Test
  fun `a place shows its name and address, without asking for location`() {
    val activity = launch()
    assertEquals("星巴克臻选上海烘焙工坊", activity.findViewById<TextView>(R.id.tellomi_location_title).text.toString())
    assertEquals("静安区南京西路789号兴业太古汇", activity.findViewById<TextView>(R.id.tellomi_location_subtitle).text.toString())
    assertEquals("Place", activity.findViewById<MaterialToolbar>(R.id.tellomi_location_toolbar).title.toString())
    // No permission was asked for.
    assertNull(shadowOf(activity).lastRequestedPermission)
    save(activity)
    // The map is width × half-width, like the bubble.
    val map = activity.findViewById<View>(R.id.tellomi_location_map)
    assertEquals(map.width / 2, map.height)
  }

  @Test
  fun `a plain location is titled Location`() {
    val activity = launch(place.copy(name = null))
    assertEquals("Location", activity.findViewById<TextView>(R.id.tellomi_location_title).text.toString())
    assertEquals("Location", activity.findViewById<MaterialToolbar>(R.id.tellomi_location_toolbar).title.toString())
  }

  @Test
  fun `directions with no map app says so`() {
    val activity = launch()
    activity.findViewById<View>(R.id.tellomi_location_directions).performClick()
    assertEquals("No map app on this phone", ShadowToast.getTextOfLatestToast())
    assertNull(shadowOf(activity).nextStartedActivity)
  }

  @Test
  fun `directions with one map app opens it straight away`() {
    install(TellomiMapApps.AMAP_PACKAGE, "amapuri")
    val activity = launch()
    activity.findViewById<View>(R.id.tellomi_location_directions).performClick()
    val started = shadowOf(activity).nextStartedActivity
    assertNotNull(started)
    assertEquals(TellomiMapApps.AMAP_PACKAGE, started.`package`)
    assertEquals("amapuri", started.data?.scheme)
    assertEquals("31.229700", started.data?.getQueryParameter("dlat"))
  }

  @Test
  fun `directions with several map apps lists them`() {
    install(TellomiMapApps.AMAP_PACKAGE, "amapuri")
    install(TellomiMapApps.TENCENT_PACKAGE, "qqmap")
    install("com.google.android.apps.maps", "geo")
    val activity = launch()
    activity.findViewById<View>(R.id.tellomi_location_directions).performClick()
    assertNull(shadowOf(activity).nextStartedActivity)
    val dialog = ShadowDialog.getLatestDialog() as AlertDialog
    assertTrue(dialog.isShowing)
    assertEquals(3, dialog.listView.adapter.count)
  }

  @Test
  fun `copy address puts the name and address on the clipboard`() {
    val activity = launch()
    activity.copyAddress()
    val clipboard = ContextCompat.getSystemService(activity, ClipboardManager::class.java)!!
    assertEquals("星巴克臻选上海烘焙工坊 静安区南京西路789号兴业太古汇", clipboard.primaryClip?.getItemAt(0)?.text.toString())
  }

  private fun save(activity: TellomiLocationActivity) {
    val root = activity.findViewById<View>(R.id.tellomi_location_root)
    val width = (360 * activity.resources.displayMetrics.density).toInt()
    val height = (800 * activity.resources.displayMetrics.density).toInt()
    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    root.layout(0, 0, width, height)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
      drawColor(Color.WHITE)
      root.draw(this)
    }
    val dir = File(System.getenv("TELLOMI_LOCATION_DIR") ?: "build/tellomi-location").apply { mkdirs() }
    File(dir, "page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
