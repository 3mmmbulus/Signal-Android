/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.internal.platform.PlatformRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependencies
import org.signal.emoji.EmojiDependencies
import org.signal.emoji.EmojiSource
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.BindableConversationItem
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.components.ConversationItemThumbnail
import org.thoughtcrime.securesms.components.LinkPreviewView
import org.thoughtcrime.securesms.components.ThumbnailView
import org.thoughtcrime.securesms.conversation.colors.ColorizerV2
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCardStyle
import org.thoughtcrime.securesms.linkpreview.TellomiLinkOnly
import org.thoughtcrime.securesms.linkpreview.TellomiLinkRegistry
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.recipients.LiveRecipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.Locale
import java.util.Optional
import kotlin.math.ceil

/**
 * card-visual §3.2 / §3.7 (audit B4, B5, B7): the shapes of the cards in a message bubble. The real [ConversationItem] is bound
 * (the legacy renderer, which draws every message that has a preview) with the real rust/links and the registry that ships with
 * the app; what the data layer would have taken from the card's own image is handed in ([TellomiLinkVisual.Visual]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiLinkCardLayoutTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  /** The bubble is on a real window: its image's transfer controls are Compose, which needs one to be measured. */
  private val activity: AppCompatActivity by lazy {
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    controller.setup().get()
  }
  private val density get() = activity.resources.displayMetrics.density
  private fun dp(value: Int) = (value * density).toInt()

  private val url = "https://www.163.com/news/article/K1234.html"
  private val requestManager = stubbedGlide()
  private val listener = mockk<BindableConversationItem.EventListener>(relaxed = true)
  private val recipient = mockk<Recipient>(relaxed = true)

  /**
   * A Glide whose requests all lead nowhere: what a thumbnail asks it for is not under test here, but a relaxed mock answers a chain of
   * builder calls with the wrong type.
   */
  private fun stubbedGlide(): RequestManager {
    val builder = mockk<RequestBuilder<Drawable>>(relaxed = true)
    every { builder.diskCacheStrategy(any()) } returns builder
    every { builder.downsample(any()) } returns builder
    every { builder.transition(any()) } returns builder
    every { builder.override(any(), any()) } returns builder
    every { builder.apply(any()) } returns builder
    return mockk(relaxed = true) { every { load(any<Any>()) } returns builder }
  }

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  @Before
  fun setUp() {
    PlainTextKeyValueStore.init(ApplicationProvider.getApplicationContext())
    PlatformRegistry.applicationContext = ApplicationProvider.getApplicationContext()
    every { signalStore.internal.forceBuiltInEmoji } returns true
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

    val golden = Json.parseToJsonElement(String(resource("classify-golden.json"))).jsonObject
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource(golden["registry"]!!.jsonPrimitive.content)))
  }

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  /** A downloaded image of [width] × [height]; two with the same [id] count as the same picture, which a thumbnail does not draw twice. */
  private fun image(width: Int, height: Int, id: Int = 7) = UriAttachment(
    dataUri = Uri.parse("content://$id"),
    contentType = "image/jpeg",
    transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
    size = 1000,
    width = width,
    height = height,
    fileName = "preview.jpg",
    fastPreflightId = null,
    voiceNote = false,
    borderless = false,
    videoGif = false,
    quote = false,
    quoteTargetContentType = null,
    caption = null,
    stickerLocator = null,
    blurHash = null,
    audioHash = null,
    transformProperties = null
  )

  private fun preview(title: String, image: UriAttachment? = null) = LinkPreview(url, title, "Sender description", 0, Optional.ofNullable(image))

  private fun newItem(): ConversationItem {
    val root = FrameLayout(activity)
    activity.setContentView(root)
    val item = LayoutInflater.from(activity).inflate(R.layout.conversation_item_received_multimedia, root, false) as ConversationItem
    root.addView(item)
    ConversationItem::class.java.getDeclaredField("contactPhoto").apply { isAccessible = true }.set(item, null)
    item.setEventListener(listener)
    return item
  }

  /** Binds [record] in an accepted conversation, the way the adapter does: the data layer decides, the view draws what it is given. */
  private fun bind(record: MmsMessageRecord, visual: TellomiLinkVisual.Visual, item: ConversationItem = newItem()): ConversationItem {
    val properties = ConversationMessage.ComputedProperties(mockk(relaxed = true), TellomiLinkOnly.decide(record, false), null, visual, null)
    val message = mockk<ConversationMessage>(relaxed = true) {
      every { messageRecord } returns record
      every { computedProperties } returns properties
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
    return item
  }

  private val ConversationItem.card: LinkPreviewView get() = findViewById(R.id.link_preview)

  // ---- B4: the large image is a box of 1.91:1 at the widest, square at the narrowest ----

  /** The box the image of a large-image card is drawn in, for an image of [width] × [height]. */
  private fun largeImageBox(width: Int, height: Int): Pair<Int, Int> {
    val record = FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview("网易新闻", image(width, height))))
    val item = bind(record, TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.LARGE_IMAGE, null))
    val thumbnail = item.findViewById<ConversationItemThumbnail>(R.id.image_view).findViewById<ThumbnailView>(R.id.conversation_thumbnail_image)
    thumbnail.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    return thumbnail.measuredWidth to thumbnail.measuredHeight
  }

  @Test
  fun `the large image of a card is the card's width, and its box is between 1 to 1 and 1_91 to 1`() {
    val cases = listOf(
      "1200 x 630, the usual og:image" to (1200 to 630),
      "1400 x 800" to (1400 to 800),
      "1920 x 1080, 16:9" to (1920 to 1080),
      "1200 x 1200, square" to (1200 to 1200),
      "600 x 1200, tall" to (600 to 1200),
      "900 x 1600, a phone screenshot" to (900 to 1600),
      "2000 x 500, a banner" to (2000 to 500),
      "1200 x 400, wider than a banner" to (1200 to 400)
    )
    for ((name, size) in cases) {
      val (width, height) = largeImageBox(size.first, size.second)

      // The thumbnail's own arithmetic is in doubles and truncates, so a 240 can come out as 239.
      assertTrue("$name: the card's width, was $width", width in (dp(240) - 1)..dp(240))
      assertTrue("$name: at most 1.91:1 wide, was $width x $height", width <= ceil(height * 1.91).toInt())
      assertTrue("$name: no taller than square, was $width x $height", width >= height)
    }
  }

  @Test
  fun `a photo message on the view that drew a large-image card is bounded as a photo is`() {
    val item = newItem()
    bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview("网易新闻", image(1200, 630)))), TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.LARGE_IMAGE, null), item)

    // 1000 x 200 is 5:1: a photo is as wide as 240 and as low as 100 (the layout's own bounds); a card's image would be 126 at the lowest.
    val photo = FakeMessageRecords.buildMediaMmsMessageRecord(
      body = "",
      slideDeck = SlideDeck().apply { addSlide(ImageSlide(image(1000, 200, id = 8))) }
    )
    bind(photo, TellomiLinkVisual.Visual.NONE, item)
    val thumbnail = item.findViewById<ConversationItemThumbnail>(R.id.image_view).findViewById<ThumbnailView>(R.id.conversation_thumbnail_image)
    thumbnail.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))

    assertEquals(dp(100), thumbnail.measuredHeight)
  }

  // ---- B5: a card with no image has the link icon ----

  @Test
  fun `a generic link with no image is its title and domain, without the compass`() {
    val item = bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview("网易新闻"))), TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null))

    assertEquals("网易新闻", item.card.findViewById<android.widget.TextView>(R.id.linkpreview_title).text.toString())
    assertEquals("163.com", item.card.findViewById<android.widget.TextView>(R.id.linkpreview_site).text.toString())
    assertEquals(View.GONE, item.card.findViewById<View>(R.id.linkpreview_link_icon).visibility)
  }

  @Test
  fun `a card the data layer has not decided on has no link icon`() {
    val item = bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview("网易新闻"))), TellomiLinkVisual.Visual.NONE)
    val card = item.card

    assertEquals("网易新闻", card.findViewById<android.widget.TextView>(R.id.linkpreview_title).text.toString())
    assertEquals("163.com", card.findViewById<android.widget.TextView>(R.id.linkpreview_site).text.toString())
    assertEquals(View.GONE, card.findViewById<View>(R.id.linkpreview_description).visibility)
    assertEquals(View.GONE, card.findViewById<View>(R.id.linkpreview_link_icon).visibility)
    val background = card.findViewById<View>(R.id.linkpreview_container).background as android.graphics.drawable.ColorDrawable
    assertEquals(TellomiLinkCardStyle.NEUTRAL_LIGHT, background.color)
  }

  @Test
  fun `a card the data layer has not decided on, with nothing, is the domain strip with the compass`() {
    val item = bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview(""))), TellomiLinkVisual.Visual.NONE)
    val card = item.card

    assertEquals("163.com", card.findViewById<android.widget.TextView>(R.id.linkpreview_title).text.toString())
    assertEquals(View.GONE, card.findViewById<View>(R.id.linkpreview_site).visibility)
    assertEquals(View.VISIBLE, card.findViewById<View>(R.id.linkpreview_link_icon).visibility)
  }

  // ---- DESIGN.md §9 (PM 2026-10-19, after Apple Messages): the strips ----

  @Test
  fun `a card with a title and no image is the neutral grey title strip without the compass, and no sub line`() {
    val item = bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview("网易新闻"))), TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null))
    val card = item.card

    assertEquals(View.GONE, card.findViewById<View>(R.id.linkpreview_description).visibility)
    assertEquals(2, card.findViewById<android.widget.TextView>(R.id.linkpreview_title).maxLines)
    assertEquals(View.GONE, card.findViewById<View>(R.id.linkpreview_link_icon).visibility)
    val background = card.findViewById<View>(R.id.linkpreview_container).background as android.graphics.drawable.ColorDrawable
    assertEquals(TellomiLinkCardStyle.NEUTRAL_LIGHT, background.color)
  }

  @Test
  fun `a card with nothing is the domain as its only line, with the compass`() {
    val item = bind(FakeMessageRecords.buildMediaMmsMessageRecord(body = url, linkPreviews = listOf(preview(""))), TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null))
    val card = item.card

    assertEquals("163.com", card.findViewById<android.widget.TextView>(R.id.linkpreview_title).text.toString())
    assertEquals(View.GONE, card.findViewById<View>(R.id.linkpreview_site).visibility)
    assertEquals(View.VISIBLE, card.findViewById<View>(R.id.linkpreview_link_icon).visibility)
  }
}
