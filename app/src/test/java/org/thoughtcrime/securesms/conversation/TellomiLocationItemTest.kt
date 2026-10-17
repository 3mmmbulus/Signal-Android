/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.descendants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import okhttp3.internal.platform.PlatformRegistry
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
import org.signal.core.ui.CoreUiDependencies
import org.signal.emoji.EmojiDependencies
import org.signal.emoji.EmojiSource
import org.thoughtcrime.securesms.BindableConversationItem
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ConversationItemThumbnail
import org.thoughtcrime.securesms.components.ThumbnailView
import org.thoughtcrime.securesms.components.emoji.EmojiTextView
import org.thoughtcrime.securesms.conversation.colors.ColorizerV2
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.keyvalue.InternalValues
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.maps.TellomiLocationActivity
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.recipients.LiveRecipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.File
import java.util.Locale
import java.util.Optional

/**
 * Spec attachment-files-location L-11 (Android): the real [ConversationItem] draws a location message as a map, width ×
 * half-width (the snapshot centre-cropped), with a place's name and address under it; a plain location is the map alone with
 * the time on it; tapping opens [TellomiLocationActivity]. Pictures go to `app/build/tellomi-location/`
 * (`TELLOMI_LOCATION_DIR` moves them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiLocationItemTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class, InternalValues::class))

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val activity: AppCompatActivity by lazy {
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    controller.setup().get()
  }

  private val requestManager: RequestManager = run {
    val builder = mockk<RequestBuilder<Drawable>>(relaxed = true)
    every { builder.diskCacheStrategy(any()) } returns builder
    every { builder.downsample(any()) } returns builder
    every { builder.transition(any()) } returns builder
    every { builder.override(any(), any()) } returns builder
    every { builder.apply(any()) } returns builder
    mockk(relaxed = true) { every { load(any<Any>()) } returns builder }
  }
  private val listener = mockk<BindableConversationItem.EventListener>(relaxed = true)
  private val recipient = mockk<Recipient>(relaxed = true)

  private val density get() = activity.resources.displayMetrics.density

  private val placeBody = "星巴克臻选上海烘焙工坊\n静安区南京西路789号兴业太古汇\nhttps://maps.google.com/maps?q=31.2297%2C121.456"
  private val signalIosBody = "中国\n上海市静安区\n南京西路789号\n\nhttps://maps.google.com/maps?q=31.2297%2C121.456"

  @Before
  fun setUp() {
    PlainTextKeyValueStore.init(ApplicationProvider.getApplicationContext())
    PlatformRegistry.applicationContext = ApplicationProvider.getApplicationContext()
    every { signalStore.internal.forceBuiltInEmoji } returns true
    every { signalStore.settings.theme } returns SettingsValues.Theme.LIGHT
    every { signalStore.settings.messageFontSize } returns 16
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()
    CoreUiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId() = "test"
        override fun provideIsIncognitoKeyboardEnabled() = false
        override fun provideIsScreenSecurityEnabled() = false
      }
    )
    val live = mockk<LiveRecipient>(relaxed = true)
    every { recipient.resolve() } returns recipient
    every { recipient.live() } returns live
    every { live.get() } returns recipient
  }

  private fun record(body: String): MmsMessageRecord {
    val image = FakeMessageRecords.buildDatabaseAttachment(contentType = MediaUtil.IMAGE_JPEG, width = 768, height = 768)
    return FakeMessageRecords.buildMediaMmsMessageRecord(body = body, slideDeck = SlideDeck(listOf(image)), dateSent = 1_790_000_000_000L, dateReceived = 1_790_000_000_000L)
  }

  private fun bind(record: MmsMessageRecord): ConversationItem {
    val root = FrameLayout(activity)
    activity.setContentView(root)
    val item = LayoutInflater.from(activity).inflate(R.layout.conversation_item_received_multimedia, root, false) as ConversationItem
    root.addView(item)
    ConversationItem::class.java.getDeclaredField("contactPhoto").apply { isAccessible = true }.set(item, null)
    item.setEventListener(listener)
    val message = mockk<ConversationMessage>(relaxed = true) {
      every { messageRecord } returns record
      every { computedProperties } returns ConversationMessage.ComputedProperties(mockk(relaxed = true))
      every { getDisplayBody(any()) } answers { SpannableString(record.body) }
      every { threadRecipient } returns recipient
      every { deletedByRecipient } returns null
      every { bottomButton } returns null
      every { mentions } returns emptyList()
    }
    val owner = object : LifecycleOwner {
      val registry = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.RESUMED }
      override val lifecycle: Lifecycle get() = registry
    }
    item.bind(owner, message, Optional.empty(), Optional.empty(), requestManager, Locale.US, emptySet(), recipient, null, false, false, true, false, ColorizerV2(), ConversationItemDisplayMode.Standard)
    item.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    item.layout(0, 0, item.measuredWidth, item.measuredHeight)
    return item
  }

  /** The map that would come with the message: a square with a dot in the middle (upstream iOS's 256 × 256 with the pin on the point). */
  private fun putMap(item: ConversationItem) {
    val square = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(square)
    canvas.drawColor(Color.parseColor("#CFE8E0"))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    canvas.drawCircle(384f, 384f, 24f, paint)
    val thumbnail = item.findViewById<View>(R.id.image_view)
    thumbnail.findViewById<View>(R.id.thumbnail_blurhash)?.visibility = View.GONE
    thumbnail.findViewById<ImageView>(R.id.thumbnail_image)!!.apply {
      visibility = View.VISIBLE
      scaleType = ImageView.ScaleType.CENTER_CROP
      setImageBitmap(square)
    }
    item.measure(View.MeasureSpec.makeMeasureSpec(item.measuredWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    item.layout(0, 0, item.measuredWidth, item.measuredHeight)
  }

  @Test
  fun `a place is a map, width by half-width, with its name and address under it`() {
    val item = bind(record(placeBody))
    val map = item.findViewById<ConversationItemThumbnail>(R.id.image_view)
    assertEquals(View.VISIBLE, map.visibility)
    val expectedWidth = minOf(activity.resources.getDimensionPixelSize(R.dimen.media_bubble_max_width), (300 * density).toInt())
    assertEquals(expectedWidth, map.measuredWidth)
    assertEquals(expectedWidth / 2, map.measuredHeight)

    val body = item.findViewById<EmojiTextView>(R.id.conversation_item_body)
    assertEquals(View.VISIBLE, body.visibility)
    assertEquals("星巴克臻选上海烘焙工坊\n静安区南京西路789号兴业太古汇", body.text.toString())
    assertEquals(3, body.maxLines)
    assertTrue("the link is not shown", !body.text.contains("maps.google.com"))
    save(item, "place")
  }

  @Test
  fun `a plain location is the map alone, the time on it`() {
    val item = bind(record(signalIosBody))
    val map = item.findViewById<ConversationItemThumbnail>(R.id.image_view)
    assertEquals(map.measuredWidth / 2, map.measuredHeight)
    assertEquals(View.GONE, item.findViewById<View>(R.id.conversation_item_body).visibility)
    // The footer on the map is the thumbnail's own.
    assertTrue(map.footer.resolved())
    assertEquals(View.VISIBLE, map.footer.get().visibility)
    save(item, "plain")
  }

  @Test
  fun `an ordinary photo with a caption is unchanged`() {
    val item = bind(record("Look at this"))
    assertEquals("Look at this", item.findViewById<EmojiTextView>(R.id.conversation_item_body).text.toString())
  }

  @Test
  fun `tapping the name opens the location page`() {
    val item = bind(record(placeBody))
    item.findViewById<View>(R.id.conversation_item_body).performClick()
    val started = shadowOf(activity).nextStartedActivity
    assertNotNull(started)
    assertEquals(TellomiLocationActivity::class.java.name, started.component?.className)
    assertEquals("星巴克臻选上海烘焙工坊", started.getStringExtra("name"))
    assertEquals(31.2297, started.getDoubleExtra("latitude", 0.0), 0.0)
  }

  @Test
  fun `tapping the map opens the location page`() {
    val item = bind(record(signalIosBody))
    val thumbnail = item.findViewById<ViewGroup>(R.id.image_view).descendants.filterIsInstance<ThumbnailView>().first()
    thumbnail.performClick()
    val started = shadowOf(activity).nextStartedActivity
    assertNotNull(started)
    assertEquals(TellomiLocationActivity::class.java.name, started.component?.className)
    assertNull(started.getStringExtra("name"))
  }

  @Test
  fun `in multi-select a tap selects, it does not open the page`() {
    val record = record(placeBody)
    val root = FrameLayout(activity)
    activity.setContentView(root)
    val item = LayoutInflater.from(activity).inflate(R.layout.conversation_item_received_multimedia, root, false) as ConversationItem
    root.addView(item)
    ConversationItem::class.java.getDeclaredField("contactPhoto").apply { isAccessible = true }.set(item, null)
    val message = mockk<ConversationMessage>(relaxed = true) {
      every { messageRecord } returns record
      every { computedProperties } returns ConversationMessage.ComputedProperties(mockk(relaxed = true))
      every { getDisplayBody(any()) } answers { SpannableString(record.body) }
      every { threadRecipient } returns recipient
      every { deletedByRecipient } returns null
      every { bottomButton } returns null
      every { mentions } returns emptyList()
    }
    val owner = object : LifecycleOwner {
      val registry = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.RESUMED }
      override val lifecycle: Lifecycle get() = registry
    }
    val selected = setOf<org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart>(org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart.Message(message))
    item.bind(owner, message, Optional.empty(), Optional.empty(), requestManager, Locale.US, selected, recipient, null, false, false, true, false, ColorizerV2(), ConversationItemDisplayMode.Standard)
    item.findViewById<View>(R.id.conversation_item_body).performClick()
    assertNull(shadowOf(activity).nextStartedActivity)
  }

  private fun save(item: ConversationItem, name: String) {
    putMap(item)
    val bitmap = Bitmap.createBitmap(item.measuredWidth, item.measuredHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    item.draw(canvas)
    val dir = File(System.getenv("TELLOMI_LOCATION_DIR") ?: "build/tellomi-location").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
