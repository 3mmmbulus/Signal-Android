/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.RequestManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.linkpreview.TellomiFirstPartyCard
import org.thoughtcrime.securesms.linkpreview.TellomiLinkDisplay
import org.thoughtcrime.securesms.linkpreview.TellomiLinkVisual
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.Optional

/**
 * ADR-0063 §4.8 / §5.1 (tellomi/tellomi#1422): what [LinkPreviewView.applyTellomiDisplay] puts on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiLinkPreviewViewTest {

  @get:Rule
  val signalStore = MockSignalStoreRule(relaxed = setOf(SettingsValues::class))

  private lateinit var view: LinkPreviewView

  private val title get() = view.findViewById<TextView>(R.id.linkpreview_title)
  private val description get() = view.findViewById<TextView>(R.id.linkpreview_description)
  private val site get() = view.findViewById<TextView>(R.id.linkpreview_site)
  private val linkIcon get() = view.findViewById<View>(R.id.linkpreview_link_icon)
  private val action get() = view.findViewById<TextView>(R.id.linkpreview_action)
  private val divider get() = view.findViewById<View>(R.id.linkpreview_divider)
  private val avatar get() = view.findViewById<View>(R.id.linkpreview_first_party_avatar)
  private val container get() = view.findViewById<View>(R.id.linkpreview_container)
  private val thumbnail get() = view.findViewById<View>(R.id.linkpreview_thumbnail)

  @Before
  fun setUp() {
    // The layout's EmojiTextViews wait for the emoji library at construction; nothing installs it in unit
    // tests, so install the bundled one (same setup as TellomiBubbleTailMarginTest).
    every { signalStore.internal.forceBuiltInEmoji } returns true
    EmojiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      mockk(relaxed = true) { every { provideForceBuiltInEmoji() } returns true }
    )
    EmojiSource.refresh()

    val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Signal_DayNight)
    view = LinkPreviewView(context)
    title.text = "Sender title"
    description.text = "Sender description"
    description.visibility = View.VISIBLE
    site.text = "www.example.com"
  }

  @Test
  fun `the official card shows the fixed title with the badge, the path and the domain`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("Tellomi website", "/download", "tellomi.app", true), true)

    val badge = view.context.getString(R.string.TellomiLinkCard__official_badge)
    assertEquals("Tellomi website  $badge", title.text.toString())
    val spans = (title.text as Spanned).getSpans(0, title.text.length, ForegroundColorSpan::class.java)
    assertEquals(1, spans.size)
    assertEquals(title.text.length - badge.length, (title.text as Spanned).getSpanStart(spans[0]))
    assertEquals("/download", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals("tellomi.app", site.text.toString())
  }

  @Test
  fun `a brand shell hides the sender's description`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", null, "taobao.com", false), true)

    assertEquals("Taobao", title.text.toString())
    // No badge: EmojiTextView may add its own spans, but never the badge colour.
    assertTrue(title.text !is Spanned || (title.text as Spanned).getSpans(0, title.text.length, ForegroundColorSpan::class.java).isEmpty())
    assertEquals(View.GONE, description.visibility)
    assertEquals("taobao.com", site.text.toString())
  }

  @Test
  fun `a condensed bubble keeps the description hidden`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("《柯洁围棋入门课》", "柯洁 · 1:02:03", "bilibili.com", false), false)

    assertEquals("《柯洁围棋入门课》", title.text.toString())
    assertEquals(View.GONE, description.visibility)
  }

  @Test
  fun `no display leaves Signal's text alone`() {
    view.applyTellomiDisplay(null, true)

    assertEquals("Sender title", title.text.toString())
    assertEquals("Sender description", description.text.toString())
    assertEquals("www.example.com", site.text.toString())
  }

  @Test
  fun `a plain-link card shows the domain as the title and the link icon, nothing else`() {
    val titleColor = title.currentTextColor
    site.visibility = View.VISIBLE

    view.applyTellomiDisplay(TellomiLinkDisplay("163.com", null, null, false, plainLink = true, lookalike = false), true)

    assertEquals("163.com", title.text.toString())
    assertEquals(View.VISIBLE, title.visibility)
    assertEquals(View.GONE, description.visibility)
    assertEquals(View.GONE, site.visibility)
    assertEquals(View.VISIBLE, linkIcon.visibility)
    assertEquals(titleColor, title.currentTextColor)
  }

  @Test
  fun `a domain that imitates a well-known one is red`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("bi1ibili.com", null, null, false, plainLink = true, lookalike = true), true)

    assertEquals(ContextCompat.getColor(view.context, org.signal.core.ui.R.color.signal_colorError), title.currentTextColor)
  }

  @Test
  fun `a recycled view drops the plain-link look`() {
    val titleColor = title.currentTextColor
    view.applyTellomiDisplay(TellomiLinkDisplay("bi1ibili.com", null, null, false, plainLink = true, lookalike = true), true)

    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", null, "taobao.com", false), true)
    assertEquals(View.GONE, linkIcon.visibility)
    assertEquals(titleColor, title.currentTextColor)
    assertEquals(View.VISIBLE, site.visibility)

    view.applyTellomiDisplay(TellomiLinkDisplay("bi1ibili.com", null, null, false, plainLink = true, lookalike = true), true)
    view.applyTellomiDisplay(null, true)
    assertEquals(View.GONE, linkIcon.visibility)
    assertEquals(titleColor, title.currentTextColor)
  }

  @Test
  fun `a first-party card shows the title, one subtitle, no domain, and the action under a hairline`() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "周末爬山群", "12 members", "Join Group", false),
      null,
      false
    )

    assertEquals("周末爬山群", title.text.toString())
    assertEquals("12 members", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals(View.GONE, site.visibility)
    assertEquals("Join Group", action.text.toString())
    assertEquals(View.VISIBLE, action.visibility)
    assertEquals(View.VISIBLE, divider.visibility)
    assertEquals(View.GONE, avatar.visibility)
  }

  @Test
  fun `a call card keeps Signal's own join button, so it has no action row`() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.CALL, "Tellomi call", null, "Join Call", false),
      null,
      false
    )

    assertEquals("Tellomi call", title.text.toString())
    assertEquals(View.GONE, description.visibility)
    assertEquals(View.GONE, action.visibility)
    assertEquals(View.GONE, divider.visibility)
  }

  @Test
  fun `the official card keeps its badge on the title`() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi website", "/download", "Open", true),
      null,
      false
    )

    val badge = view.context.getString(R.string.TellomiLinkCard__official_badge)
    assertEquals("Tellomi website  $badge", title.text.toString())
  }

  @Test
  fun `a recycled view drops the first-party look`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    view.applyTellomiFirstParty(
      requestManager,
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.STICKER, "Bandit", "24 stickers", "Add", false),
      null,
      false
    )

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), true)

    assertEquals(View.GONE, action.visibility)
    assertEquals(View.GONE, divider.visibility)
    assertEquals(View.GONE, avatar.visibility)
    assertEquals("Sender title", title.text.toString())
  }

  private val orange = TellomiLinkVisual.Colors(Color.parseColor("#FE7500"), Color.parseColor("#000000"))

  /** Makes the image on the card visible without going through Glide's request chain, which a mock cannot answer. */
  private fun showImage() {
    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi", null, "Open", false),
      null,
      false
    )
  }

  private fun params(id: Int) = view.findViewById<View>(id).layoutParams as ConstraintLayout.LayoutParams

  @Test
  fun `a tinted card is drawn in the colours of its image, and a recycled view drops them`() {
    val background = (container.background as ColorDrawable).color
    val titleColor = title.currentTextColor

    view.applyTellomiTint(orange)

    assertEquals(orange.background, (container.background as ColorDrawable).color)
    assertEquals(orange.text, title.currentTextColor)
    assertEquals(0xB3, Color.alpha(description.currentTextColor))
    assertEquals(orange.text and 0xFFFFFF, description.currentTextColor and 0xFFFFFF)

    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), true)
    assertEquals(background, (container.background as ColorDrawable).color)
    assertEquals(titleColor, title.currentTextColor)
  }

  @Test
  fun `no colours leave the card as it is`() {
    val background = (container.background as ColorDrawable).color
    view.applyTellomiTint(null)
    assertEquals(background, (container.background as ColorDrawable).color)
  }

  private fun dp(value: Int) = (value * view.resources.displayMetrics.density).toInt()

  @Test
  fun `the icon card puts a 48 square at the top right, 6 down from the top and 12 in from the right, the title and the domain on the left, and no description`() {
    showImage()
    view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", null, "bilibili.com", false), true)

    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    assertEquals(dp(48), thumbnail.layoutParams.width)
    assertEquals(dp(48), thumbnail.layoutParams.height)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).startToStart)
    assertEquals("at the top, not centred", ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).topToTop)
    assertEquals("6 down from the top is the card's own top padding", dp(6), container.paddingTop)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).bottomToBottom)
    assertEquals("the image's end edge is at the card's own end padding, as the text starts at its start padding", dp(12), container.paddingEnd)
    assertEquals(container.paddingStart, container.paddingEnd)
    assertEquals(0, params(R.id.linkpreview_thumbnail).marginEnd)
    assertEquals(R.id.linkpreview_thumbnail, params(R.id.linkpreview_title).endToStart)
    assertEquals("10 between the text and the image", dp(10), params(R.id.linkpreview_title).marginEnd)
    assertEquals("the text starts at the top too", 0f, params(R.id.linkpreview_title).verticalBias)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_title).startToStart)
    assertEquals(R.id.linkpreview_title, params(R.id.linkpreview_site).topToBottom)
    assertEquals(View.GONE, description.visibility)
    assertEquals("bilibili.com", site.text.toString())
  }

  @Test
  fun `the icon card is at least the image and its insets tall, and the image is where it is meant to be`() {
    // Measuring the image needs a window (its transfer controls are Compose).
    CoreUiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId() = "test"
        override fun provideIsIncognitoKeyboardEnabled() = false
        override fun provideIsScreenSecurityEnabled() = false
      }
    )
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    val activity = controller.setup().get()
    val card = LinkPreviewView(activity)
    activity.setContentView(card)
    val requestManager = mockk<RequestManager>(relaxed = true)
    card.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    card.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", null, "bilibili.com", false), true)
    card.applyTellomiBrandIcon(requestManager, brandIcon())
    card.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    card.measure(View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    card.layout(0, 0, card.measuredWidth, card.measuredHeight)

    val cardContainer = card.findViewById<View>(R.id.linkpreview_container)
    val image = card.findViewById<View>(R.id.linkpreview_thumbnail)
    val cardTitle = card.findViewById<View>(R.id.linkpreview_title)
    val cardSite = card.findViewById<View>(R.id.linkpreview_site)
    assertTrue("48 + 6 + 6 tall at least, was ${card.measuredHeight}", card.measuredHeight >= dp(60))
    assertEquals("6 from the top", dp(6), cardContainer.top + image.top)
    assertEquals("as far in from the right as the text is from the left", cardTitle.left - cardContainer.left, card.measuredWidth - (cardContainer.left + image.right))
    assertEquals("and that is the card's padding, 12", dp(12), card.measuredWidth - (cardContainer.left + image.right))
    assertEquals(dp(48), image.width)
    assertEquals(dp(48), image.height)
    assertTrue("the text ends 10 before the image: ${cardTitle.right} vs ${image.left}", cardTitle.right <= image.left - dp(10))
    assertTrue("the domain ends 10 before the image", cardSite.right <= image.left - dp(10))
    assertEquals("the text starts at the top, level with the image", image.top, cardTitle.top)
  }

  @Test
  fun `a card that is not an icon card keeps its image where it was`() {
    showImage()
    val startToStart = params(R.id.linkpreview_thumbnail).startToStart

    view.applyTellomiLayout(TellomiLinkVisual.Layout.LARGE_IMAGE)
    view.applyTellomiLayout(null)

    assertEquals(startToStart, params(R.id.linkpreview_thumbnail).startToStart)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).endToEnd)
  }

  @Test
  fun `a recycled view drops the icon layout`() {
    showImage()
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).endToEnd)

    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), true)

    val size = (72 * view.resources.displayMetrics.density).toInt()
    assertEquals(size, thumbnail.layoutParams.width)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).startToStart)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals("the card's padding was never changed", dp(12), container.paddingEnd)
    assertEquals(dp(8), params(R.id.linkpreview_title).marginEnd)
    assertEquals(0.5f, params(R.id.linkpreview_title).verticalBias)
    assertEquals(R.id.linkpreview_link_icon, params(R.id.linkpreview_title).endToStart)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_site).topToBottom)
    assertEquals(View.VISIBLE, description.visibility)
  }

  // --- The domain-only card of a message request (card-visual §3.3 / §7.3) ---

  @Test
  fun `the domain-only card shows the domain and the link icon, and nothing the sender wrote`() {
    view.setDomainOnly("163.com", false)

    assertEquals("163.com", title.text.toString())
    assertEquals(View.VISIBLE, title.visibility)
    assertEquals(View.VISIBLE, linkIcon.visibility)
    assertEquals(View.GONE, description.visibility)
    assertEquals(View.GONE, site.visibility)
    assertEquals(View.GONE, thumbnail.visibility)
    assertEquals(View.GONE, avatar.visibility)
    assertEquals(View.GONE, action.visibility)
    assertEquals(View.GONE, divider.visibility)
    assertEquals(View.GONE, view.findViewById<View>(R.id.linkpreview_progress_wheel).visibility)
    assertEquals(View.GONE, view.findViewById<View>(R.id.linkpreview_no_preview).visibility)
  }

  @Test
  fun `the domain-only card is in the default colours, and red when the domain imitates a well-known one`() {
    val titleColor = title.currentTextColor
    val background = (container.background as ColorDrawable).color

    view.setDomainOnly("163.com", false)
    assertEquals(titleColor, title.currentTextColor)
    assertEquals(background, (container.background as ColorDrawable).color)

    view.setDomainOnly("bi1ibili.com", true)
    assertEquals(ContextCompat.getColor(view.context, org.signal.core.ui.R.color.signal_colorError), title.currentTextColor)
    assertEquals(background, (container.background as ColorDrawable).color)
  }

  @Test
  fun `a recycled view drops whatever the card before the domain-only card left on it`() {
    val titleColor = title.currentTextColor
    val background = (container.background as ColorDrawable).color
    val requestManager = mockk<RequestManager>(relaxed = true)

    // An icon card, tinted, with its image and its first-party look.
    showImage()
    view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", "Video", "bilibili.com", false), true)
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)
    view.applyTellomiTint(orange)
    assertEquals(View.VISIBLE, thumbnail.visibility)
    assertEquals(orange.background, (container.background as ColorDrawable).color)

    view.setDomainOnly("163.com", false)

    assertEquals("163.com", title.text.toString())
    assertEquals(View.GONE, thumbnail.visibility)
    assertEquals(background, (container.background as ColorDrawable).color)
    assertEquals(titleColor, title.currentTextColor)
    assertEquals(View.GONE, description.visibility)
    assertEquals(View.GONE, site.visibility)
    assertEquals(View.VISIBLE, linkIcon.visibility)
    assertEquals("the icon layout is undone", ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals("the title is beside the link icon, not beside an image", R.id.linkpreview_link_icon, params(R.id.linkpreview_title).endToStart)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_title).startToStart)
    assertEquals(0, params(R.id.linkpreview_title).marginStart)

    // A first-party card with its action row.
    view.applyTellomiFirstParty(
      requestManager,
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "周末爬山群", "12 members", "Join Group", false),
      null,
      false
    )
    assertEquals(View.VISIBLE, action.visibility)

    view.setDomainOnly("tell.cc", false)

    assertEquals("tell.cc", title.text.toString())
    assertEquals(View.GONE, action.visibility)
    assertEquals(View.GONE, divider.visibility)
    assertEquals(View.GONE, avatar.visibility)
    assertEquals(View.GONE, thumbnail.visibility)
    assertEquals(View.GONE, description.visibility)
  }

  @Test
  fun `the domain-only card is undone for the next card on the view`() {
    val titleColor = title.currentTextColor
    view.setDomainOnly("bi1ibili.com", true)

    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)

    assertEquals("Sender title", title.text.toString())
    assertEquals(View.GONE, linkIcon.visibility)
    assertEquals(titleColor, title.currentTextColor)
    assertEquals("Sender description", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
  }

  // --- Brand shells: the icon that ships with the app (card-visual §3.9) ---

  private fun brandIcon(color: Int = Color.rgb(254, 117, 0)): Bitmap {
    return Bitmap.createBitmap(114, 114, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }
  }

  private val thumbnailImage get() = thumbnail.findViewById<ImageView>(R.id.thumbnail_image)

  @Test
  fun `a brand shell's icon is the 48 square at the top right, its name, kind and domain on the left`() {
    val icon = brandIcon()
    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", "Product", "taobao.com", false), true)

    view.applyTellomiBrandIcon(mockk<RequestManager>(relaxed = true), icon)
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)
    view.applyTellomiTint(orange)

    assertEquals(View.VISIBLE, thumbnail.visibility)
    assertEquals(dp(48), thumbnail.layoutParams.width)
    assertEquals(dp(48), thumbnail.layoutParams.height)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).topToTop)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).bottomToBottom)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_thumbnail).startToStart)
    assertEquals(R.id.linkpreview_thumbnail, params(R.id.linkpreview_title).endToStart)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_title).startToStart)
    assertEquals("the kind is the sub line, between the name and the domain", "Product", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals("Taobao", title.text.toString())
    assertEquals("taobao.com", site.text.toString())

    val drawn = thumbnailImage.drawable
    assertTrue(drawn is BitmapDrawable)
    assertSame("the bundled icon itself, not a copy of the sender's image", icon, (drawn as BitmapDrawable).bitmap)

    assertEquals("tinted by the icon", orange.background, (container.background as ColorDrawable).color)
    assertEquals(orange.text, title.currentTextColor)
  }

  @Test
  fun `a brand shell without an icon shows only its name and domain`() {
    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Meituan", "Product", "meituan.com", false), true)

    view.applyTellomiBrandIcon(mockk<RequestManager>(relaxed = true), null)
    view.applyTellomiLayout(TellomiLinkVisual.Layout.NO_IMAGE)

    assertEquals(View.GONE, thumbnail.visibility)
    assertEquals("the sub line is kept: nothing about the card changed", "Product", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals(R.id.linkpreview_link_icon, params(R.id.linkpreview_title).endToStart)
    assertEquals("Meituan", title.text.toString())
    assertEquals("meituan.com", site.text.toString())
  }

  /** PM 2026-10-19 (evening): a claimed-tier card without any icon (JD, Meituan, Pinduoduo, Tmall) is its title and domain only. */
  @Test
  fun `a claimed card without any icon shows its title and domain and no link icon`() {
    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("https://item.jd.com/100012043978.html", "Apple iPhone 15", "", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Apple iPhone 15", null, "jd.com", false), true)

    view.applyTellomiBrandIcon(mockk<RequestManager>(relaxed = true), null)
    view.applyTellomiLayout(TellomiLinkVisual.Layout.NO_IMAGE, false)

    assertEquals(View.GONE, thumbnail.visibility)
    assertEquals(View.GONE, linkIcon.visibility)
    assertEquals("Apple iPhone 15", title.text.toString())
    assertEquals("jd.com", site.text.toString())

    // The same view, reused for a card with nothing but the link: the link icon is back.
    view.applyTellomiLayout(TellomiLinkVisual.Layout.NO_IMAGE, true)
    assertEquals(View.VISIBLE, linkIcon.visibility)
  }

  @Test
  fun `a recycled view drops the brand icon`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    val background = (container.background as ColorDrawable).color
    view.applyTellomiBrandIcon(requestManager, brandIcon())
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)
    view.applyTellomiTint(orange)

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)

    assertEquals(View.GONE, thumbnail.visibility)
    assertEquals(background, (container.background as ColorDrawable).color)
    assertEquals(R.id.linkpreview_link_icon, params(R.id.linkpreview_title).endToStart)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_site).topToBottom)
    assertEquals(View.VISIBLE, description.visibility)
  }

  @Test
  fun `a brand shell's icon card keeps its kind text on one line between the name and the domain`() {
    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", "Product", "taobao.com", false), true)
    view.applyTellomiBrandIcon(mockk<RequestManager>(relaxed = true), brandIcon())

    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    assertEquals("Product", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals(1, description.maxLines)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_title).bottomToTop)
    assertEquals(R.id.linkpreview_title, params(R.id.linkpreview_description).topToBottom)
    assertEquals(R.id.linkpreview_site, params(R.id.linkpreview_description).bottomToTop)
    assertEquals(R.id.linkpreview_title, params(R.id.linkpreview_description).startToStart)
    assertEquals(R.id.linkpreview_title, params(R.id.linkpreview_description).endToEnd)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_site).topToBottom)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_site).bottomToBottom)
    assertEquals("the icon is still the 48 square at the top right", ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals(R.id.linkpreview_thumbnail, params(R.id.linkpreview_title).endToStart)
  }

  @Test
  fun `an icon card with no sub line has nothing to keep`() {
    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", null, "taobao.com", false), true)
    view.applyTellomiBrandIcon(mockk<RequestManager>(relaxed = true), brandIcon())
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    assertEquals(View.GONE, description.visibility)
    assertEquals(R.id.linkpreview_site, params(R.id.linkpreview_title).bottomToTop)
    assertEquals(R.id.linkpreview_title, params(R.id.linkpreview_site).topToBottom)
  }

  /** card-visual §3.7: three lines, title, sub line, domain, for every card; §3.2's icon geometry is about the image and does not drop one. */
  @Test
  fun `an icon card keeps its sub line whoever's image it shows`() {
    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", "Author · 3:45", "bilibili.com", false), true)
    view.applyTellomiBrandIcon(mockk<RequestManager>(relaxed = true), brandIcon())

    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    assertEquals("Author · 3:45", description.text.toString())
    assertEquals(View.VISIBLE, description.visibility)
    assertEquals(1, description.maxLines)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_title).bottomToTop)
    assertEquals(R.id.linkpreview_title, params(R.id.linkpreview_description).topToBottom)
    assertEquals(R.id.linkpreview_site, params(R.id.linkpreview_description).bottomToTop)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_site).topToBottom)
    assertEquals("bilibili.com", site.text.toString())
    assertEquals("the image is still the 48 square at the top right", dp(48), thumbnail.layoutParams.width)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_thumbnail).endToEnd)
    assertEquals("the text still ends before the image", R.id.linkpreview_thumbnail, params(R.id.linkpreview_title).endToStart)
  }

  @Test
  fun `a condensed bubble's icon card has no sub line to keep`() {
    view.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", "Author · 3:45", "bilibili.com", false), false)
    view.applyTellomiBrandIcon(mockk<RequestManager>(relaxed = true), brandIcon())

    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    assertEquals(View.GONE, description.visibility)
    assertEquals(R.id.linkpreview_site, params(R.id.linkpreview_title).bottomToTop)
  }

  @Test
  fun `a recycled view gets the sub line's placement and line limit back`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Taobao", "Product", "taobao.com", false), true)
    view.applyTellomiBrandIcon(requestManager, brandIcon())
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)

    assertEquals("one line, as every card's sub line", 1, description.maxLines)
    assertEquals(R.id.linkpreview_header_barrier, params(R.id.linkpreview_description).topToBottom)
    assertEquals(ConstraintLayout.LayoutParams.UNSET, params(R.id.linkpreview_description).bottomToTop)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_description).endToEnd)
    assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params(R.id.linkpreview_description).startToStart)
    assertEquals(R.id.linkpreview_description, params(R.id.linkpreview_site).topToBottom)
  }

  // --- Line limits (card-visual §3.7): the title two lines, the sub line one, the domain one, each cut off at its end ---

  @Test
  fun `a card is two lines of title, one of sub line and one of domain, each cut off at its end`() {
    view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", "Author · 3:45", "bilibili.com", false), true)

    assertEquals(2, title.maxLines)
    assertEquals(1, description.maxLines)
    assertEquals(1, site.maxLines)
    for (line in listOf(title, description, site)) {
      assertEquals(TextUtils.TruncateAt.END, line.ellipsize)
    }
  }

  @Test
  fun `the limits hold on a reused view, for the first-party card too`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), true)
    assertEquals(1, description.maxLines)
    assertEquals(1, site.maxLines)

    view.applyTellomiFirstParty(
      requestManager,
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.GROUP, "周末爬山群", "12 members", "Join Group", false),
      null,
      false
    )
    assertEquals(2, title.maxLines)
    assertEquals(1, description.maxLines)

    view.setDomainOnly("163.com", false)
    assertEquals(2, title.maxLines)
    assertEquals(1, site.maxLines)
  }

  @Test
  fun `the compose box keeps its own limits`() {
    val compose = LayoutInflater.from(view.context).inflate(R.layout.conversation_input_link_preview_view, null) as LinkPreviewView

    assertEquals(2, compose.findViewById<TextView>(R.id.linkpreview_title).maxLines)
    assertEquals(2, compose.findViewById<TextView>(R.id.linkpreview_description).maxLines)
  }

  // --- The no-image card: a grey link icon at the end of the title line (card-visual §3.2 / §3.7) ---

  @Test
  fun `a card with no image draws the link icon, whatever its level`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    val displays = listOf(
      "generic" to TellomiLinkDisplay("Sender title", null, "example.com", false),
      "structured" to TellomiLinkDisplay("Go Course", "Ke Jie · 1:02:03", "bilibili.com", false),
      "brand shell without an icon" to TellomiLinkDisplay("Meituan", "Product", "meituan.com", false)
    )
    for ((name, display) in displays) {
      view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
      view.applyTellomiDisplay(display, true)
      view.applyTellomiLayout(TellomiLinkVisual.Layout.NO_IMAGE)

      assertEquals(name, View.VISIBLE, linkIcon.visibility)
      assertEquals("$name: the title ends before the icon", R.id.linkpreview_link_icon, params(R.id.linkpreview_title).endToStart)
    }
  }

  @Test
  fun `the link icon is the no-image card's alone`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    for (layout in listOf(TellomiLinkVisual.Layout.ICON, TellomiLinkVisual.Layout.LARGE_IMAGE, TellomiLinkVisual.Layout.FIRST_PARTY, null)) {
      view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
      view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", null, "bilibili.com", false), true)
      view.applyTellomiLayout(layout)

      assertEquals("$layout", View.GONE, linkIcon.visibility)
    }
  }

  @Test
  fun `a recycled view drops the no-image card's link icon`() {
    val requestManager = mockk<RequestManager>(relaxed = true)
    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    view.applyTellomiDisplay(TellomiLinkDisplay("Sender title", null, "example.com", false), true)
    view.applyTellomiLayout(TellomiLinkVisual.Layout.NO_IMAGE)
    assertEquals(View.VISIBLE, linkIcon.visibility)

    view.setLinkPreview(requestManager, LinkPreview("", "Sender title", "Sender description", 0, Optional.empty()), false)
    assertEquals(View.GONE, linkIcon.visibility)

    view.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", null, "bilibili.com", false), true)
    view.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)
    assertEquals(View.GONE, linkIcon.visibility)
  }

  @Test
  fun `the no-image card's link icon is the grey one the plain-link card has`() {
    val icon = linkIcon as ImageView
    val grey = ContextCompat.getColor(view.context, org.signal.core.ui.R.color.signal_colorOnSurfaceVariant)

    assertEquals(grey, ImageViewCompat.getImageTintList(icon)?.defaultColor)
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.importantForAccessibility)
  }

  @Test
  fun `a card with no image keeps the text clear of the link icon, and is as tall as its lines`() {
    val card = cardInWindow()
    card.setLinkPreview(mockk<RequestManager>(relaxed = true), LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    card.applyTellomiDisplay(TellomiLinkDisplay("A long title that goes on and on and wraps onto a second line of the card", "Author · 3:45", "bilibili.com", false), true)
    card.applyTellomiLayout(TellomiLinkVisual.Layout.NO_IMAGE)

    card.measure(View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    card.layout(0, 0, card.measuredWidth, card.measuredHeight)

    val icon = card.findViewById<View>(R.id.linkpreview_link_icon)
    val cardTitle = card.findViewById<View>(R.id.linkpreview_title)
    val cardSub = card.findViewById<View>(R.id.linkpreview_description)
    val cardSite = card.findViewById<View>(R.id.linkpreview_site)
    assertEquals(View.VISIBLE, icon.visibility)
    assertTrue("the title ends before the icon: ${cardTitle.right} vs ${icon.left}", cardTitle.right <= icon.left)
    assertTrue("title, sub line and domain are one under the other", cardTitle.bottom <= cardSub.top && cardSub.bottom <= cardSite.top)
  }

  @Test
  fun `an icon card with a sub line is as tall as its three lines, and all three end before the image`() {
    val card = cardInWindow()
    val requestManager = mockk<RequestManager>(relaxed = true)
    card.setLinkPreview(requestManager, LinkPreview("", "Sender title", "", 0, Optional.empty()), false)
    card.applyTellomiDisplay(TellomiLinkDisplay("Bilibili", "Author · 3:45", "bilibili.com", false), true)
    card.applyTellomiBrandIcon(requestManager, brandIcon())
    card.applyTellomiLayout(TellomiLinkVisual.Layout.ICON)

    card.measure(View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    card.layout(0, 0, card.measuredWidth, card.measuredHeight)

    val image = card.findViewById<View>(R.id.linkpreview_thumbnail)
    val cardTitle = card.findViewById<View>(R.id.linkpreview_title)
    val cardSub = card.findViewById<View>(R.id.linkpreview_description)
    val cardSite = card.findViewById<View>(R.id.linkpreview_site)
    assertTrue("48 + 6 + 6 tall at least, was ${card.measuredHeight}", card.measuredHeight >= dp(60))
    assertTrue("the sub line is drawn under the title: ${cardTitle.bottom} vs ${cardSub.top}", cardSub.top >= cardTitle.bottom)
    assertTrue("the domain is drawn under the sub line", cardSite.top >= cardSub.bottom)
    assertTrue("the sub line ends 10 before the image", cardSub.right <= image.left - dp(10))
    assertTrue("the domain ends 10 before the image", cardSite.right <= image.left - dp(10))
    assertEquals("6 from the top, as every icon card", dp(6), card.findViewById<View>(R.id.linkpreview_container).top + image.top)
  }

  // --- The avatar and the cover of a first-party card are 56 (card-visual §5.2) ---

  @Test
  fun `the avatar of a first-party card is 56 square, and so is its cover`() {
    assertEquals(dp(56), avatar.layoutParams.width)
    assertEquals(dp(56), avatar.layoutParams.height)

    view.applyTellomiFirstParty(
      mockk<RequestManager>(relaxed = true),
      TellomiFirstPartyCard.Display(TellomiFirstPartyCard.Type.OFFICIAL, "Tellomi website", "/download", "Open", true),
      null,
      false
    )
    assertEquals(dp(56), thumbnail.layoutParams.width)
    assertEquals(dp(56), thumbnail.layoutParams.height)
  }

  /** A card on a real window, for measuring: its image is a ThumbnailView, whose transfer controls are Compose. */
  private fun cardInWindow(): LinkPreviewView {
    CoreUiDependencies.init(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId() = "test"
        override fun provideIsIncognitoKeyboardEnabled() = false
        override fun provideIsScreenSecurityEnabled() = false
      }
    )
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.Signal_DayNight)
    val activity = controller.setup().get()
    val card = LinkPreviewView(activity)
    activity.setContentView(card)
    return card
  }
}
