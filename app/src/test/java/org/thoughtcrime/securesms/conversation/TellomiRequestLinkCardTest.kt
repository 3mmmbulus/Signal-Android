/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.URLSpan
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestManager
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.internal.platform.PlatformRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.emoji.EmojiDependencies
import org.signal.emoji.EmojiSource
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.BindableConversationItem
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.components.LinkPreviewView
import org.thoughtcrime.securesms.conversation.colors.ColorizerV2
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCard
import org.thoughtcrime.securesms.linkpreview.TellomiLinkCardStyle
import org.thoughtcrime.securesms.linkpreview.TellomiLinkOnly
import org.thoughtcrime.securesms.linkpreview.TellomiLinkRegistry
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual
import org.thoughtcrime.securesms.recipients.LiveRecipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import org.thoughtcrime.securesms.testutil.UriAttachmentBuilder
import java.util.Locale
import java.util.Optional

/**
 * card-visual §3.3 / §7.3, ADR-0063 §8.1 row 6 (S1): what an incoming bubble draws for a link while its conversation is still a
 * message request, and after it is accepted. The real [ConversationItem] is bound (the legacy renderer, which draws every
 * message that has a preview), with the real rust/links and the registry that ships with the app deciding what it is given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiRequestLinkCardTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Signal_DayNight)

  private val url = "https://www.163.com/news/article/K1234.html"
  private val group = "https://tell.cc/g#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  private val call = "https://tell.cc/call/#key=bcdf-ghkm-npqr-stxz-bcdf-ghkm-npqr-stxz"

  private val orange = TellomiLinkVisual.Colors(Color.parseColor("#FE7500"), Color.parseColor("#000000"))
  private val tinted = TellomiLinkVisual.Visual(
    TellomiLinkVisual.Layout.ICON,
    TellomiLinkVisual.Tint(tinted = true, light = orange, dark = orange)
  )

  private val requestManager = mockk<RequestManager>(relaxed = true)
  private val listener = mockk<BindableConversationItem.EventListener>(relaxed = true)
  private val recipient = mockk<Recipient>(relaxed = true)

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  @Before
  fun setUp() {
    PlainTextKeyValueStore.init(ApplicationProvider.getApplicationContext())
    // The full card's domain line asks OkHttp for the registrable domain, whose suffix list OkHttp reads from the app's assets
    // once its initializer has given it the context, which nothing does in a unit test.
    PlatformRegistry.applicationContext = ApplicationProvider.getApplicationContext()
    every { signalStore.internal.forceBuiltInEmoji } returns true
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()

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

  private fun preview(url: String, title: String, withImage: Boolean = false): LinkPreview {
    return LinkPreview(
      url,
      title,
      "Sender description",
      1_790_000_000_000L,
      if (withImage) Optional.of<Attachment>(UriAttachmentBuilder.build(id = 7, contentType = "image/jpeg")) else Optional.empty()
    )
  }

  private fun message(body: String, vararg previews: LinkPreview): MmsMessageRecord {
    return FakeMessageRecords.buildMediaMmsMessageRecord(body = body, linkPreviews = previews.toList())
  }

  private fun newItem(): ConversationItem {
    val item = LayoutInflater.from(context).inflate(R.layout.conversation_item_received_multimedia, FrameLayout(context), false) as ConversationItem
    // The sender's avatar is only drawn in a group, and needs Glide; it is not what is under test.
    ConversationItem::class.java.getDeclaredField("contactPhoto").apply { isAccessible = true }.set(item, null)
    item.setEventListener(listener)
    return item
  }

  /**
   * Binds [record] the way the adapter does: the data layer decides first (the full card, and the request card), the view draws
   * what it is given. [visual] is what the data layer would have taken from the card's own image (none can be read here).
   */
  private fun bind(
    record: MmsMessageRecord,
    accepted: Boolean,
    item: ConversationItem = newItem(),
    visual: TellomiLinkVisual.Visual = TellomiLinkVisual.Visual.NONE,
    requestCard: TellomiLinkCard? = TellomiLinkOnly.decideRequest(record, false)
  ): ConversationItem {
    val properties = ConversationMessage.ComputedProperties(mockk(relaxed = true), TellomiLinkOnly.decide(record, false), null, visual, requestCard)
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
    item.bind(owner, message, Optional.empty(), Optional.empty(), requestManager, Locale.US, emptySet(), recipient, null, false, false, accepted, false, ColorizerV2(), ConversationItemDisplayMode.Standard)
    return item
  }

  private val ConversationItem.card: LinkPreviewView get() = findViewById(R.id.link_preview)
  private val ConversationItem.body: TextView get() = findViewById(R.id.conversation_item_body)
  private fun View.text(id: Int) = findViewById<TextView>(id)
  private fun View.visibilityOf(id: Int) = findViewById<View>(id).visibility
  private val ConversationItem.cardBackground: Int get() = (card.findViewById<View>(R.id.linkpreview_container).background as ColorDrawable).color

  private fun neutral() = ContextCompat.getColor(context, org.signal.core.ui.R.color.signal_neutralSurface)

  private fun ConversationItem.linkSpans(): Int = (body.text as? Spannable)?.getSpans(0, body.text.length, URLSpan::class.java)?.size ?: 0

  private fun cardTexts(item: ConversationItem): List<String> {
    val texts = mutableListOf<String>()
    fun walk(view: View) {
      if (view is TextView) texts += view.text.toString()
      if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
    }
    walk(item.card)
    return texts
  }

  // ---- while the conversation is a message request ----

  @Test
  fun `a request shows a link-only message as the domain and a link icon, and keeps the link text as plain text`() {
    val record = message(url, preview(url, "网易新闻", withImage = true))

    val item = bind(record, accepted = false, visual = tinted)

    assertEquals(View.VISIBLE, item.card.visibility)
    assertEquals("163.com", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(View.VISIBLE, item.card.visibilityOf(R.id.linkpreview_link_icon))
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_description))
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_site))
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_thumbnail))
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_action))
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_first_party_avatar))

    // The link text is not hidden, even though the message is only this link, and it does not open anything.
    assertEquals(View.VISIBLE, item.body.visibility)
    assertEquals(url, item.body.text.toString())
    assertEquals(0, item.linkSpans())
  }

  @Test
  fun `a request draws no image and asks for none, and takes no colour`() {
    val record = message(url, preview(url, "网易新闻", withImage = true))

    val item = bind(record, accepted = false, visual = tinted)

    verify { requestManager wasNot Called }
    assertNull("no thumbnail of the message is inflated", item.findViewById<View>(R.id.image_view))
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_thumbnail))
    assertEquals("the neutral card colour, not the image's", neutral(), item.cardBackground)
  }

  @Test
  fun `a request does not open the link when the card is tapped`() {
    val item = bind(message(url, preview(url, "网易新闻")), accepted = false)

    item.card.performClick()

    verify(exactly = 0) { listener.onLinkPreviewClicked(any()) }
  }

  @Test
  fun `a request is never a big-image card, whatever the full card would have been`() {
    val record = message(url, preview(url, "网易新闻", withImage = true))
    val bigImage = TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.LARGE_IMAGE, null)

    val item = bind(record, accepted = false, visual = bigImage)

    // Decides the space under a quote and the corners: the domain card is not the big-image kind, and gets the space of the small card.
    val hasBigImage = ConversationItem::class.java.getDeclaredMethod("hasBigImageLinkPreview", MessageRecord::class.java).apply { isAccessible = true }
    assertFalse(hasBigImage.invoke(item, record) as Boolean)
  }

  @Test
  fun `a request shows a link with text around it as the domain above the whole text`() {
    val record = message("看看 $url，挺有意思", preview(url, "网易新闻"))

    val item = bind(record, accepted = false)

    assertEquals("163.com", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(View.VISIBLE, item.body.visibility)
    assertEquals("看看 $url，挺有意思", item.body.text.toString())
    assertEquals(0, item.linkSpans())
  }

  @Test
  fun `a request shows a link sent without a preview as the domain too`() {
    val item = bind(message(url), accepted = false)

    assertEquals("163.com", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(View.VISIBLE, item.card.visibilityOf(R.id.linkpreview_link_icon))
    assertEquals(View.VISIBLE, item.body.visibility)
    assertEquals(url, item.body.text.toString())
  }

  @Test
  fun `a request shows a domain that imitates a well-known one in red`() {
    val lookalike = "https://www.bi1ibili.com/video/BV1YDhJ6ZEL6"

    val item = bind(message(lookalike, preview(lookalike, "哔哩哔哩")), accepted = false)

    assertEquals("bi1ibili.com", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(ContextCompat.getColor(context, org.signal.core.ui.R.color.signal_colorError), item.card.text(R.id.linkpreview_title).currentTextColor)
  }

  @Test
  fun `a request shows a tell-cc group invite as the domain only, without the group name, avatar or action`() {
    val item = bind(message(group, preview(group, "周末爬山群", withImage = true)), accepted = false)

    assertEquals("tell.cc", item.card.text(R.id.linkpreview_title).text.toString())
    for (id in listOf(R.id.linkpreview_description, R.id.linkpreview_site, R.id.linkpreview_action, R.id.linkpreview_first_party_avatar, R.id.linkpreview_thumbnail)) {
      assertEquals(View.GONE, item.card.visibilityOf(id))
    }
    assertFalse("the group name is nowhere on the card", cardTexts(item).any { it.contains("周末爬山群") || it.contains("Sender") })
    verify { requestManager wasNot Called }

    item.card.performClick()
    verify(exactly = 0) { listener.onLinkPreviewClicked(any()) }
  }

  @Test
  fun `a request shows a call link as the domain only, with no join button`() {
    val item = bind(message(call, preview(call, "Tellomi call")), accepted = false)

    assertEquals("tell.cc", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_thumbnail))
    assertNull("Signal's own join button under the bubble is not even inflated", item.findViewById<View>(R.id.join_button))
    verify { requestManager wasNot Called }
  }

  @Test
  fun `a request hides the join button a recycled bubble had for a call link`() {
    val item = newItem()
    // As left by an accepted conversation's call link on this view (joining is not tested here; the button only needs to exist).
    val stub = ConversationItem::class.java.getDeclaredField("joinCallLinkStub").apply { isAccessible = true }.get(item) as org.signal.core.ui.view.Stub<*>
    stub.get().visibility = View.VISIBLE

    bind(message(call, preview(call, "Tellomi call")), accepted = false, item = item)

    assertEquals(View.GONE, item.findViewById<View>(R.id.join_button).visibility)
  }

  @Test
  fun `a request shows the other first-party links and a brand shell as the domain only`() {
    for ((link, domain) in mapOf("https://tell.cc/scam01" to "tell.cc", "https://tellomi.app/security" to "tellomi.app", "https://item.taobao.com/item.htm?id=100032608854" to "taobao.com")) {
      val item = bind(message(link, preview(link, "@kefu", withImage = true)), accepted = false, visual = tinted)

      assertEquals(link, domain, item.card.text(R.id.linkpreview_title).text.toString())
      assertFalse(link, cardTexts(item).any { it.contains("@kefu") || it.contains("Tellomi") })
      assertEquals(link, View.GONE, item.card.visibilityOf(R.id.linkpreview_action))
      assertEquals(link, View.GONE, item.card.visibilityOf(R.id.linkpreview_thumbnail))
      assertEquals(link, neutral(), item.cardBackground)
    }
    verify { requestManager wasNot Called }
  }

  @Test
  fun `a request without a request card draws no card, and never the full one`() {
    val record = message(url, preview(url, "网易新闻"))

    val item = bind(record, accepted = false, requestCard = null)

    assertTrue("the link preview is not shown", item.findViewById<View>(R.id.link_preview)?.visibility != View.VISIBLE)
    assertEquals(View.VISIBLE, item.body.visibility)
    assertEquals(url, item.body.text.toString())
    assertEquals(0, item.linkSpans())
  }

  // ---- after it is accepted: the card as it was ----

  @Test
  fun `once accepted, the same message shows the whole card, and the card opens the link`() {
    val record = message(url, preview(url, "网易新闻"))

    val item = bind(record, accepted = true)

    assertEquals(View.VISIBLE, item.card.visibility)
    assertEquals("网易新闻", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals("163.com", item.card.text(R.id.linkpreview_site).text.toString())
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_link_icon))
    assertEquals("a message that is only a link shows the card alone", View.GONE, item.body.visibility)

    item.card.performClick()
    verify(exactly = 1) { listener.onLinkPreviewClicked(match { it.url == url }) }
  }

  @Test
  fun `once accepted, a card without an image to show is neutral grey, whatever tint the data layer had`() {
    // DESIGN.md §9 (PM 2026-10-24): only an icon strip keeps its image's colours; with no image drawn this is a title strip.
    val item = bind(message(url, preview(url, "网易新闻")), accepted = true, visual = tinted)

    assertEquals(TellomiLinkCardStyle.NEUTRAL_LIGHT, item.cardBackground)
    assertEquals(ContextCompat.getColor(context, org.signal.core.ui.R.color.signal_colorOnSurface), item.card.text(R.id.linkpreview_title).currentTextColor)
  }

  @Test
  fun `once accepted, a link in the text is a link again`() {
    val item = bind(message("看看 $url", preview(url, "网易新闻")), accepted = true)

    assertEquals("网易新闻", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(View.VISIBLE, item.body.visibility)
    assertTrue(item.linkSpans() > 0)
  }

  @Test
  fun `accepting a request turns the domain card into the whole card on the same view, and back`() {
    val record = message(url, preview(url, "网易新闻"))
    val item = newItem()

    bind(record, accepted = false, item = item, visual = tinted)
    assertEquals("163.com", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(neutral(), item.cardBackground)
    assertEquals(View.VISIBLE, item.body.visibility)

    bind(record, accepted = true, item = item, visual = tinted)
    assertEquals("网易新闻", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals("163.com", item.card.text(R.id.linkpreview_site).text.toString())
    assertEquals(TellomiLinkCardStyle.NEUTRAL_LIGHT, item.cardBackground)
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_link_icon))
    assertEquals(View.GONE, item.body.visibility)

    bind(record, accepted = false, item = item, visual = tinted)
    assertEquals("163.com", item.card.text(R.id.linkpreview_title).text.toString())
    assertEquals(neutral(), item.cardBackground)
    assertEquals(View.GONE, item.card.visibilityOf(R.id.linkpreview_site))
    assertEquals(View.VISIBLE, item.card.visibilityOf(R.id.linkpreview_link_icon))
    assertEquals(View.VISIBLE, item.body.visibility)
  }
}
