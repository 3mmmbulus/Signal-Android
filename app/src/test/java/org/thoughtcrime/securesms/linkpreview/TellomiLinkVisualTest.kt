/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * card-visual §3.2 / §3.3 / §3.8 (tellomi/tellomi#1422): the shape of a card and the colours from its own image come from
 * rust/links (`layout` / `tint`); the client only reads the answer and applies it. Same cases as Desktop
 * (`linkCardVisual_test.std.ts`, tellomi/Signal-Desktop#30).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiLinkVisualTest {

  private val orange = """{"tinted":true,"source":"#FE7500","light":{"background":"#FE7500","text":"#000000"},"dark":{"background":"#994600","text":"#FFFFFF"}}"""
  private val card = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, domain = "bilibili.com", tintable = true)

  @Test
  fun `the four shapes are read, anything else is no decision`() {
    assertEquals(TellomiLinkVisual.Layout.FIRST_PARTY, TellomiLinkVisual.parseLayout("first_party"))
    assertEquals(TellomiLinkVisual.Layout.LARGE_IMAGE, TellomiLinkVisual.parseLayout("large_image"))
    assertEquals(TellomiLinkVisual.Layout.ICON, TellomiLinkVisual.parseLayout("icon"))
    assertEquals(TellomiLinkVisual.Layout.NO_IMAGE, TellomiLinkVisual.parseLayout("no_image"))
    assertNull(TellomiLinkVisual.parseLayout("hologram"))
    assertNull(TellomiLinkVisual.parseLayout(""))
    assertNull(TellomiLinkVisual.parseLayout("\"icon\""))
  }

  @Test
  fun `a tinted result has a light and a dark set of colours`() {
    val tint = TellomiLinkVisual.parseTint(orange)
    assertEquals(
      TellomiLinkVisual.Tint(
        tinted = true,
        light = TellomiLinkVisual.Colors(Color.parseColor("#FE7500"), Color.parseColor("#000000")),
        dark = TellomiLinkVisual.Colors(Color.parseColor("#994600"), Color.parseColor("#FFFFFF"))
      ),
      tint
    )
    assertEquals(Color.parseColor("#FE7500"), tint?.colors(isDark = false)?.background)
    assertEquals(Color.parseColor("#994600"), tint?.colors(isDark = true)?.background)
    assertEquals(Color.parseColor("#FFFFFF"), tint?.colors(isDark = true)?.text)
  }

  @Test
  fun `a neutral image keeps the default colours`() {
    val tint = TellomiLinkVisual.parseTint("""{"tinted":false,"source":"#808082"}""")
    assertEquals(TellomiLinkVisual.Tint(tinted = false), tint)
    assertNull(tint?.colors(isDark = false))
    assertNull(TellomiLinkVisual.Tint(tinted = true).colors(isDark = true))
  }

  @Test
  fun `anything it does not understand is no decision`() {
    assertNull(TellomiLinkVisual.parseTint("not json"))
    assertNull(TellomiLinkVisual.parseTint("{}"))
    assertNull(TellomiLinkVisual.parseTint("""{"tinted":true}"""))
    assertNull(
      "a colour that is not #RRGGBB is not applied",
      TellomiLinkVisual.parseTint("""{"tinted":true,"light":{"background":"red","text":"#000000"},"dark":{"background":"#994600","text":"#FFFFFF"}}""")
    )
  }

  @Test
  fun `only third-party cards with an image are tinted`() {
    assertTrue(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.ICON))
    assertTrue(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.LARGE_IMAGE))
    assertFalse(TellomiLinkVisual.shouldTint(card.copy(tintable = false), TellomiLinkVisual.Layout.ICON))
    assertFalse(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.FIRST_PARTY))
    assertFalse(TellomiLinkVisual.shouldTint(card, TellomiLinkVisual.Layout.NO_IMAGE))
    assertFalse(TellomiLinkVisual.shouldTint(card, null))
    assertFalse(TellomiLinkVisual.shouldTint(null, TellomiLinkVisual.Layout.ICON))
  }

  @Test
  fun `the layout is asked for the size of the image the card shows, and none for a card that shows none`() {
    val asked = mutableListOf<List<Any>>()
    val bridge = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String {
        asked += listOf(imageWidth, imageHeight, kind, level)
        return "icon"
      }

      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = error("not asked")
    }

    val structured = card.copy(level = TellomiLinkCard.Level.STRUCTURED, kind = "video")
    assertEquals(TellomiLinkVisual.Layout.ICON, TellomiLinkVisual.layout(bridge, structured, imageWidth = 1200, imageHeight = 630))
    assertEquals(listOf<Any>(1200, 630, "video", "structured"), asked[0])

    TellomiLinkVisual.layout(bridge, card.copy(level = TellomiLinkCard.Level.FIRST_PARTY, kind = "tellomi.group"), imageWidth = 1200, imageHeight = 630)
    assertEquals(listOf<Any>(1200, 630, "tellomi.group", "first_party"), asked[1])

    TellomiLinkVisual.layout(bridge, card.copy(showImage = false), imageWidth = 1200, imageHeight = 630)
    assertEquals("a card that shows no image is asked with none", listOf<Any>(0, 0, "", "generic"), asked[2])

    TellomiLinkVisual.layout(bridge, card.copy(level = TellomiLinkCard.Level.PLAIN_LINK), imageWidth = 0, imageHeight = 0)
    assertEquals("plain_link", asked[3][3])
  }

  @Test
  fun `a bridge that fails is no decision`() {
    val failing = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = throw IllegalStateException("no native library")
      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = throw IllegalStateException("no native library")
    }
    assertNull(TellomiLinkVisual.layout(failing, card, imageWidth = 100, imageHeight = 100))
    assertNull(TellomiLinkVisual.tint(failing, TellomiLinkVisual.Layout.ICON, solidPng(Color.rgb(254, 117, 0))))
  }

  @Test
  fun `the image is reduced to 32 by 32 RGBA before rust-links sees it`() {
    var seen: Triple<String, Int, Int>? = null
    var pixels: ByteArray? = null
    val bridge = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = error("not asked")

      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String {
        seen = Triple(layout, width, height)
        pixels = rgba
        return orange
      }
    }

    val tint = TellomiLinkVisual.tint(bridge, TellomiLinkVisual.Layout.ICON, solidPng(Color.rgb(254, 117, 0), width = 200, height = 100))

    assertNotNull(tint)
    assertEquals(Triple("icon", 32, 32), seen)
    assertEquals(32 * 32 * 4, pixels?.size)
    assertArrayEquals("first pixel is opaque orange, as R G B A", byteArrayOf(0xFE.toByte(), 0x75, 0x00, 0xFF.toByte()), pixels?.copyOfRange(0, 4))
    assertArrayEquals("and so is the last", byteArrayOf(0xFE.toByte(), 0x75, 0x00, 0xFF.toByte()), pixels?.copyOfRange(32 * 32 * 4 - 4, 32 * 32 * 4))
  }

  @Test
  fun `an image that cannot be decoded is not tinted`() {
    val bridge = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = error("not asked")
      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = error("not asked")
    }
    assertNull(TellomiLinkVisual.tint(bridge, TellomiLinkVisual.Layout.ICON, byteArrayOf(1, 2, 3)))
  }

  private fun bridgeOf(layoutName: String = "icon", tintJson: String = orange, onTint: () -> Unit = {}) = object : TellomiLinkVisual.Bridge {
    override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = layoutName
    override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String {
      onTint()
      return tintJson
    }
  }

  @Test
  fun `a card gets its shape and the colours of its image`() {
    val visual = TellomiLinkVisual.decide(bridgeOf(), card, 100, 100, "decide-1") { solidPng(Color.rgb(254, 117, 0)) }

    assertEquals(TellomiLinkVisual.Layout.ICON, visual.layout)
    assertEquals(Color.parseColor("#FE7500"), visual.tint?.colors(isDark = false)?.background)
  }

  @Test
  fun `no card, no decision`() {
    assertEquals(TellomiLinkVisual.Visual.NONE, TellomiLinkVisual.decide(bridgeOf(), null, 100, 100, "decide-2") { error("not read") })
    assertEquals(TellomiLinkVisual.Visual.NONE, TellomiLinkVisual.decide(bridgeOf(layoutName = "hologram"), card, 100, 100, "decide-2") { error("not read") })
  }

  @Test
  fun `a card that is not tinted never reads its image`() {
    val notTintable = card.copy(tintable = false)
    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.ICON, null), TellomiLinkVisual.decide(bridgeOf(), notTintable, 100, 100, "decide-3") { error("not read") })

    val firstParty = card.copy(level = TellomiLinkCard.Level.FIRST_PARTY)
    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.FIRST_PARTY, null), TellomiLinkVisual.decide(bridgeOf("first_party"), firstParty, 100, 100, "decide-3") { error("not read") })

    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null), TellomiLinkVisual.decide(bridgeOf("no_image"), card, 0, 0, null) { error("not read") })
  }

  @Test
  fun `an image that is not on the device yet gives a shape and no colours`() {
    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.ICON, null), TellomiLinkVisual.decide(bridgeOf(), card, 100, 100, "decide-4") { null })
  }

  @Test
  fun `a neutral image gives no colours, and the same image is read once`() {
    var tints = 0
    var reads = 0
    val neutral = bridgeOf(tintJson = """{"tinted":false,"source":"#808082"}""", onTint = { tints++ })
    repeat(2) {
      val visual = TellomiLinkVisual.decide(neutral, card, 100, 100, "decide-5") {
        reads++
        solidPng(Color.GRAY)
      }
      assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.ICON, null), visual)
    }
    assertEquals(1, reads)
    assertEquals(1, tints)
  }

  // --- Brand shells: the icon that ships with the app (card-visual §3.7 / §3.9) ---

  private val brand = TellomiLinkCard(
    level = TellomiLinkCard.Level.BRAND,
    provider = "taobao",
    kind = "product",
    domain = "taobao.com",
    showImage = false,
    icon = "brand-1.png",
    tintable = true
  )

  /** Records what rust/links was asked, and answers with [layoutName] / [tintJson]. */
  private class RecordingBridge(private val layoutName: String = "icon", private val tintJson: String) : TellomiLinkVisual.Bridge {
    val layouts = mutableListOf<List<Any>>()
    val tints = mutableListOf<Triple<String, Int, Int>>()
    val pixels = mutableListOf<ByteArray>()

    override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String {
      layouts += listOf(imageWidth, imageHeight, kind, level)
      return layoutName
    }

    override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String {
      tints += Triple(layout, width, height)
      pixels += rgba
      return tintJson
    }
  }

  private fun iconsOf(vararg files: Pair<String, ByteArray>): Pair<TellomiBrandIcons, MutableList<String>> {
    val opened = mutableListOf<String>()
    val byPath = files.associate { "links/icons/${it.first}" to it.second }
    val icons = TellomiBrandIcons { path ->
      opened += path
      java.io.ByteArrayInputStream(byPath[path] ?: throw java.io.FileNotFoundException(path))
    }
    return icons to opened
  }

  @Test
  fun `a brand shell with its icon is asked the icon's size, and tinted from the icon's own pixels`() {
    val bridge = RecordingBridge(tintJson = orange)
    val (icons, opened) = iconsOf("brand-1.png" to solidPng(Color.rgb(254, 117, 0), width = 114, height = 114))

    val visual = TellomiLinkVisual.decideBrand(bridge, icons, brand)

    assertEquals("the icon's size, no kind, the brand level", listOf(listOf<Any>(114, 114, "", "brand")), bridge.layouts)
    assertEquals(listOf(Triple("icon", 32, 32)), bridge.tints)
    assertArrayEquals("the first pixel of what rust/links saw is the icon's orange", byteArrayOf(0xFE.toByte(), 0x75, 0x00, 0xFF.toByte()), bridge.pixels.single().copyOfRange(0, 4))
    assertEquals(32 * 32 * 4, bridge.pixels.single().size)
    assertEquals(listOf("links/icons/brand-1.png"), opened)

    assertEquals(TellomiLinkVisual.Layout.ICON, visual.layout)
    assertEquals("the colours rust/links gave, as they are", Color.parseColor("#FE7500"), visual.tint?.colors(isDark = false)?.background)
    assertEquals(Color.parseColor("#994600"), visual.tint?.colors(isDark = true)?.background)
    assertEquals(114, visual.icon?.width)
    assertEquals(114, visual.icon?.height)
  }

  @Test
  fun `the icon is left as it was after its colours are taken`() {
    val (icons, _) = iconsOf("brand-2.png" to solidPng(Color.rgb(254, 117, 0), width = 114, height = 114))
    val visual = TellomiLinkVisual.decideBrand(RecordingBridge(tintJson = orange), icons, brand.copy(icon = "brand-2.png"))

    assertFalse(visual.icon!!.isRecycled)
    assertEquals(Color.rgb(254, 117, 0), visual.icon!!.getPixel(10, 10))
  }

  @Test
  fun `a payment shell is never tinted, and shows the icon if it has one`() {
    val bridge = RecordingBridge(tintJson = orange)
    val (icons, _) = iconsOf("brand-3.png" to solidPng(Color.BLUE))

    val visual = TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = "brand-3.png", tintable = false, payment = true))

    assertEquals(TellomiLinkVisual.Layout.ICON, visual.layout)
    assertNull(visual.tint)
    assertNotNull("card-visual §3.9: the icon shows when there is one; only the tint is left out", visual.icon)
    assertEquals(emptyList<Any>(), bridge.tints)

    val locked = TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = "brand-3.png", tintable = false, payment = false))
    assertNull("not tintable is not tinted either", locked.tint)
    assertEquals(emptyList<Any>(), bridge.tints)
  }

  @Test
  fun `a brand shell with no icon is the card it always was, and the package is not opened`() {
    val bridge = RecordingBridge(layoutName = "no_image", tintJson = orange)
    val (icons, opened) = iconsOf()

    val visual = TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = null))

    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null), visual)
    assertNull(visual.icon)
    assertEquals("no image size", listOf(listOf<Any>(0, 0, "", "brand")), bridge.layouts)
    assertEquals(emptyList<Any>(), bridge.tints)
    assertEquals(emptyList<String>(), opened)
  }

  @Test
  fun `a brand shell whose icon cannot be read is the same card`() {
    val bridge = RecordingBridge(layoutName = "no_image", tintJson = orange)
    val (icons, _) = iconsOf("brand-4.png" to byteArrayOf(9, 9, 9))

    for (name in listOf("brand-missing.png", "brand-4.png")) {
      assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null), TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = name)))
    }
    assertEquals(emptyList<Any>(), bridge.tints)
  }

  @Test
  fun `a name that is not a plain file name never reaches the package`() {
    val bridge = RecordingBridge(layoutName = "no_image", tintJson = orange)
    val (icons, opened) = iconsOf("x.png" to solidPng(Color.RED))

    for (hostile in listOf("../x.png", "a/b.png", "X.png", "", "links/icons/x.png")) {
      assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null), TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = hostile)))
    }
    assertEquals(emptyList<String>(), opened)
  }

  @Test
  fun `only a brand shell takes an icon from the package`() {
    val bridge = RecordingBridge(layoutName = "no_image", tintJson = orange)
    val (icons, opened) = iconsOf("brand-5.png" to solidPng(Color.RED))

    val visual = TellomiLinkVisual.decideBrand(bridge, icons, card.copy(icon = "brand-5.png"))

    assertNull(visual.icon)
    assertEquals(emptyList<String>(), opened)
  }

  @Test
  fun `a claimed-tier card whose page gave no image takes the platform's bundled icon and its colours`() {
    // links-2026101901 (PM 2026-10-19): Taobao and the others are `claimed`; rust/links gives their page card `icon`.
    val claimed = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, title = "淘宝", domain = "taobao.com", showImage = false, icon = "brand-10.png", tintable = true, reason = "claimed")
    val bridge = RecordingBridge(tintJson = orange)
    val (icons, opened) = iconsOf("brand-10.png" to solidPng(Color.rgb(254, 117, 0), width = 114, height = 114))

    assertTrue(TellomiLinkVisual.usesBundledIcon(claimed))
    val visual = TellomiLinkVisual.decideBrand(bridge, icons, claimed)

    assertEquals("the icon's size, not 0 x 0 (the no-image card)", listOf(listOf<Any>(114, 114, "", "generic")), bridge.layouts)
    assertEquals(listOf("links/icons/brand-10.png"), opened)
    assertEquals(TellomiLinkVisual.Layout.ICON, visual.layout)
    assertEquals(Color.parseColor("#FE7500"), visual.tint?.colors(isDark = false)?.background)
    assertNotNull(visual.icon)
  }

  @Test
  fun `a claimed-tier card with a page image uses the page image, and a card with nothing has no icon`() {
    val claimed = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, domain = "taobao.com", showImage = true, icon = "taobao.png", tintable = true)
    assertFalse(TellomiLinkVisual.usesBundledIcon(claimed))
    assertFalse(TellomiLinkVisual.usesBundledIcon(claimed.copy(showImage = false, icon = null)))
    assertFalse(TellomiLinkVisual.usesBundledIcon(claimed.copy(level = TellomiLinkCard.Level.STRUCTURED, showImage = false)))
    assertTrue(TellomiLinkVisual.usesBundledIcon(brand))
  }

  // --- Claimed-tier cards without a bundled icon (PM 2026-10-19, evening: JD, Meituan, Pinduoduo, Tmall get none) ---

  @Test
  fun `a claimed card whose sender fetched the site icon is the icon card with the icon's colours`() {
    val claimed = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, title = "Apple iPhone 15", domain = "jd.com", showImage = true, tintable = true, reason = "claimed")
    val bridge = RecordingBridge(tintJson = orange)

    val visual = TellomiLinkVisual.decide(bridge, claimed, 180, 180, "claimed-site-icon") { solidPng(Color.rgb(254, 117, 0), width = 180, height = 180) }

    assertFalse("no bundled icon: the sender's site icon is the image", TellomiLinkVisual.usesBundledIcon(claimed))
    assertEquals(listOf(listOf<Any>(180, 180, "", "generic")), bridge.layouts)
    assertEquals(listOf(Triple("icon", 32, 32)), bridge.tints)
    assertEquals(TellomiLinkVisual.Layout.ICON, visual.layout)
    assertEquals(Color.parseColor("#FE7500"), visual.tint?.colors(isDark = false)?.background)
    assertFalse(TellomiLinkVisual.showsLinkIcon(claimed, visual.layout))
  }

  @Test
  fun `a claimed card with nothing fetched is its title and domain, without the link icon`() {
    val claimed = TellomiLinkCard(level = TellomiLinkCard.Level.GENERIC, title = "Apple iPhone 15", domain = "jd.com", showImage = false, tintable = true, reason = "claimed")
    val bridge = bridgeOf(layoutName = "no_image", tintJson = orange)

    val visual = TellomiLinkVisual.decide(bridge, claimed, 0, 0, null) { null }

    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null), visual)
    assertFalse("a claimed card has a title: no link icon", TellomiLinkVisual.showsLinkIcon(claimed, visual.layout))

    val blocked = TellomiLinkCard(level = TellomiLinkCard.Level.BRAND, provider = "jd", kind = "product", domain = "jd.com", showImage = false, tintable = true, reason = "claimed_no_title")
    val shell = TellomiLinkVisual.decideBrand(bridgeOf(layoutName = "no_image", tintJson = orange), iconsOf().first, blocked)
    assertEquals(TellomiLinkVisual.Layout.NO_IMAGE, shell.layout)
    assertFalse("the platform's name and the domain, no link icon", TellomiLinkVisual.showsLinkIcon(blocked, shell.layout))

    assertTrue("a card with nothing at all keeps the link icon", TellomiLinkVisual.showsLinkIcon(card.copy(showImage = false), TellomiLinkVisual.Layout.NO_IMAGE))
    assertTrue(TellomiLinkVisual.showsLinkIcon(null, TellomiLinkVisual.Layout.NO_IMAGE))
    assertFalse(TellomiLinkVisual.showsLinkIcon(card, TellomiLinkVisual.Layout.ICON))
    assertTrue(TellomiLinkVisual.isClaimed(blocked) && TellomiLinkVisual.isClaimed(claimed) && !TellomiLinkVisual.isClaimed(brand))
  }

  @Test
  fun `a neutral icon gives the shape and no colours, and each icon is tinted once`() {
    val bridge = RecordingBridge(tintJson = """{"tinted":false,"source":"#808082"}""")
    val (icons, _) = iconsOf("brand-6.png" to solidPng(Color.GRAY))

    repeat(2) {
      val visual = TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = "brand-6.png"))
      assertEquals(TellomiLinkVisual.Layout.ICON, visual.layout)
      assertNull(visual.tint)
      assertNotNull(visual.icon)
    }
    assertEquals(1, bridge.tints.size)
  }

  @Test
  fun `a shape that is not the icon card shows no icon`() {
    val bridge = RecordingBridge(layoutName = "no_image", tintJson = orange)
    val (icons, _) = iconsOf("brand-7.png" to solidPng(Color.RED))

    val visual = TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = "brand-7.png"))

    assertEquals(TellomiLinkVisual.Visual(TellomiLinkVisual.Layout.NO_IMAGE, null), visual)
    assertEquals(emptyList<Any>(), bridge.tints)
  }

  @Test
  fun `a bridge that fails is no decision for a brand shell too`() {
    val failing = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = throw IllegalStateException("no native library")
      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = throw IllegalStateException("no native library")
    }
    val (icons, _) = iconsOf("brand-8.png" to solidPng(Color.RED))

    assertEquals(TellomiLinkVisual.Visual.NONE, TellomiLinkVisual.decideBrand(failing, icons, brand.copy(icon = "brand-8.png")))

    val layoutOnly = object : TellomiLinkVisual.Bridge {
      override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = "icon"
      override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = throw IllegalStateException("no native library")
    }
    val visual = TellomiLinkVisual.decideBrand(layoutOnly, icons, brand.copy(icon = "brand-8.png"))
    assertEquals("the icon still shows, untinted", TellomiLinkVisual.Layout.ICON, visual.layout)
    assertNull(visual.tint)
    assertNotNull(visual.icon)
  }

  @Test
  fun `a brand shell is tinted, a payment one never`() {
    assertTrue(TellomiLinkVisual.shouldTint(brand, TellomiLinkVisual.Layout.ICON))
    assertFalse(TellomiLinkVisual.shouldTint(brand.copy(tintable = false, payment = true), TellomiLinkVisual.Layout.ICON))
  }

  @Test
  fun `the sender's image plays no part in a brand shell`() {
    // A brand shell is asked with the icon's size even when the sender sent a big picture: its image is not shown (show_image is false).
    val bridge = RecordingBridge(tintJson = orange)
    val (icons, _) = iconsOf("brand-9.png" to solidPng(Color.rgb(254, 117, 0), width = 100, height = 100))

    TellomiLinkVisual.decideBrand(bridge, icons, brand.copy(icon = "brand-9.png"))

    assertEquals(listOf<Any>(100, 100, "", "brand"), bridge.layouts.single())
    assertFalse(brand.showImage)
  }

  private fun solidPng(color: Int, width: Int = 64, height: Int = 64): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }
}
