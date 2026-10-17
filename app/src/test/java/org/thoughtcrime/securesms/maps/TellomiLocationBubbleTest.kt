/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.maps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.util.MediaUtil
import java.util.Locale

/** Spec attachment-files-location L-11 (Android): which messages are location bubbles, the map apps "Directions" lists, and the page's text. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiLocationBubbleTest {

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val place = TellomiLocationMessage(31.2297, 121.456, "星巴克臻选上海烘焙工坊", "静安区南京西路789号兴业太古汇")
  private val placeBody = "星巴克臻选上海烘焙工坊\n静安区南京西路789号兴业太古汇\nhttps://maps.google.com/maps?q=31.2297%2C121.456"

  private fun image(contentType: String = MediaUtil.IMAGE_JPEG, borderless: Boolean = false, videoGif: Boolean = false, id: Long = 1) = FakeMessageRecords.buildDatabaseAttachment(
    attachmentId = org.signal.core.models.database.AttachmentId(id),
    contentType = contentType,
    borderless = borderless,
    videoGif = videoGif,
    width = 768,
    height = 768
  )

  // ---------------------------------------------------------------------------------------------------------------------------
  // Recognising
  // ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun `one image and a maps link is a location`() {
    val record = FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck(listOf(image())))
    assertEquals(place, TellomiLocationBubble.recognize(record))
  }

  @Test
  fun `anything else is not`() {
    val notALocation = listOf(
      // A photo with an ordinary caption.
      FakeMessageRecords.buildMediaMmsMessageRecord(body = "Look at this", slideDeck = SlideDeck(listOf(image()))),
      // The link without the map.
      FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck()),
      // Two images.
      FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck(listOf(image(id = 1), image(id = 2)))),
      // A video, a GIF, a sticker-like borderless image.
      FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck(listOf(image(contentType = MediaUtil.VIDEO_MP4)))),
      FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck(listOf(image(contentType = MediaUtil.VIDEO_MP4, videoGif = true)))),
      FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck(listOf(image(borderless = true)))),
      // View-once.
      FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck(listOf(image())), viewOnce = true)
    )
    notALocation.forEachIndexed { index, record -> assertNull("case $index", TellomiLocationBubble.recognize(record)) }
  }

  @Test
  fun `the map is width by half-width, at most 300dp`() {
    val density = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
    val narrow = TellomiLocationBubble.mapSize(200)
    assertEquals(200, narrow[0])
    assertEquals(100, narrow[1])
    val wide = TellomiLocationBubble.mapSize(2000)
    assertEquals((300 * density).toInt(), wide[0])
    assertEquals(wide[0] / 2, wide[1])
  }

  @Test
  fun `a place's text is the name and the address, a plain location has none`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val text = TellomiLocationBubble.text(context, place, Color.BLACK, Color.GRAY, 10_000)
    assertEquals("星巴克臻选上海烘焙工坊\n静安区南京西路789号兴业太古汇", text.toString())
    assertEquals(3, TellomiLocationBubble.maxLines(place))
    assertNull(TellomiLocationBubble.text(context, place.copy(name = null), Color.BLACK, Color.GRAY, 10_000))
    // A long name is cut to one line.
    val cut = TellomiLocationBubble.text(context, place.copy(name = "很长的名字".repeat(40)), Color.BLACK, Color.GRAY, 300)!!.toString()
    assertTrue(cut, cut.substringBefore('\n').endsWith("…"))
  }

  @Test
  fun `multi-select takes a location message as one piece, a captioned photo as two`() {
    fun parts(record: org.thoughtcrime.securesms.database.model.MessageRecord): org.thoughtcrime.securesms.conversation.mutiselect.MultiselectCollection {
      val message = io.mockk.mockk<org.thoughtcrime.securesms.conversation.ConversationMessage>(relaxed = true) {
        io.mockk.every { messageRecord } returns record
        io.mockk.every { isActiveCollapsedHead } returns false
        io.mockk.every { isActiveCollapsibleHead } returns false
      }
      return org.thoughtcrime.securesms.conversation.mutiselect.Multiselect.getParts(message)
    }
    val location = parts(FakeMessageRecords.buildMediaMmsMessageRecord(body = placeBody, slideDeck = SlideDeck(listOf(image()))))
    assertTrue(location.isSingle())
    assertTrue(location.asSingle().singlePart is org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart.Message)
    val photo = parts(FakeMessageRecords.buildMediaMmsMessageRecord(body = "Look at this", slideDeck = SlideDeck(listOf(image()))))
    assertEquals(2, photo.size)
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // Map apps
  // ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun `route links`() {
    val amap = TellomiMapApps.routeUri(TellomiMapApps.AMAP_PACKAGE, place, "Location")!!
    assertEquals("amapuri", amap.scheme)
    assertEquals("31.229700", amap.getQueryParameter("dlat"))
    assertEquals("121.456000", amap.getQueryParameter("dlon"))
    assertEquals("星巴克臻选上海烘焙工坊", amap.getQueryParameter("dname"))
    assertEquals("0", amap.getQueryParameter("dev"))
    assertEquals("0", amap.getQueryParameter("t"))
    assertEquals("2", TellomiMapApps.routeUri(TellomiMapApps.AMAP_PACKAGE, place, "Location", TellomiMapApps.Directions.WALKING)!!.getQueryParameter("t"))

    val baidu = TellomiMapApps.routeUri(TellomiMapApps.BAIDU_PACKAGE, place, "Location")!!
    assertEquals("name:星巴克臻选上海烘焙工坊|latlng:31.229700,121.456000", baidu.getQueryParameter("destination"))
    assertEquals("gcj02", baidu.getQueryParameter("coord_type"))
    assertEquals("driving", baidu.getQueryParameter("mode"))

    val tencent = TellomiMapApps.routeUri(TellomiMapApps.TENCENT_PACKAGE, place, "Location")!!
    assertEquals("drive", tencent.getQueryParameter("type"))
    assertEquals("31.229700,121.456000", tencent.getQueryParameter("tocoord"))

    assertNull(TellomiMapApps.routeUri("com.google.android.apps.maps", place, "Location"))
    val geo = TellomiMapApps.geoUri(place.copy(name = "A (B)"), "Location")
    assertEquals("geo", geo.scheme)
    assertEquals("geo:31.229700,121.456000?q=31.229700%2C121.456000(A%20%20B)", geo.toString())
  }

  @Test
  fun `only installed map apps are listed, the Chinese ones first, each app once`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val packageManager = shadowOf(context.packageManager)
    assertTrue(TellomiMapApps.installed(context.packageManager, place, "Location").isEmpty())

    fun install(packageName: String, scheme: String) {
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
    install("com.google.android.apps.maps", "geo")
    install(TellomiMapApps.AMAP_PACKAGE, "amapuri")
    install(TellomiMapApps.AMAP_PACKAGE, "geo")
    install(TellomiMapApps.TENCENT_PACKAGE, "qqmap")

    val apps = TellomiMapApps.installed(context.packageManager, place, "Location")
    assertEquals(listOf(TellomiMapApps.AMAP_PACKAGE, TellomiMapApps.TENCENT_PACKAGE, "com.google.android.apps.maps"), apps.map { it.packageName })
    assertEquals("amapuri", apps[0].intent.data?.scheme)
    assertEquals(TellomiMapApps.AMAP_PACKAGE, apps[0].intent.`package`)
    assertEquals("geo", apps[2].intent.data?.scheme)
    assertEquals("com.google.android.apps.maps", apps[2].intent.`package`)
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // The page's text
  // ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun `distance, subtitle and share text`() {
    assertEquals("350 m", TellomiLocationActivity.formatDistance(350.4, Locale.CHINA))
    assertEquals("1.2 km", TellomiLocationActivity.formatDistance(1234.0, Locale.CHINA))
    assertEquals("328 ft", TellomiLocationActivity.formatDistance(100.0, Locale.US))
    assertEquals("1.2 mi", TellomiLocationActivity.formatDistance(2000.0, Locale.US))

    assertEquals("地址 • 1.2 km away", TellomiLocationActivity.subtitle("地址", "1.2 km away"))
    assertEquals("地址", TellomiLocationActivity.subtitle("地址", null))
    assertEquals("1.2 km away", TellomiLocationActivity.subtitle("", "1.2 km away"))

    assertEquals(placeBody, TellomiLocationActivity.shareText(place, place.address))
    assertEquals("https://maps.google.com/maps?q=31.2297%2C121.456", TellomiLocationActivity.shareText(place.copy(name = null), null))
  }

  @Test
  fun `the page gets what it needs from the intent`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val map = Uri.parse("content://org.thoughtcrime.securesms/part/1")
    val intent = TellomiLocationActivity.intent(context, place, map)
    assertEquals(31.2297, intent.getDoubleExtra("latitude", 0.0), 0.0)
    assertEquals(121.456, intent.getDoubleExtra("longitude", 0.0), 0.0)
    assertEquals("星巴克臻选上海烘焙工坊", intent.getStringExtra("name"))
    assertEquals("静安区南京西路789号兴业太古汇", intent.getStringExtra("address"))
    assertNotNull(intent.getParcelableExtra("map"))
  }
}
