/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * ADR-0063 §九.6, card-visual §3.9: the icon of a brand shell is read from the package by a file name that comes from the
 * registry, so the name is checked before anything is opened; a file that is not there or will not decode is "no icon".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiBrandIconsTest {

  private fun png(color: Int, width: Int = 64, height: Int = 64): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  /** What was asked of the package, and an answer for the names in [files]. */
  private class FakeAssets(private val files: Map<String, ByteArray>) {
    val opened = mutableListOf<String>()

    fun open(path: String): java.io.InputStream {
      opened += path
      return ByteArrayInputStream(files[path] ?: throw FileNotFoundException(path))
    }
  }

  @Test
  fun `a file name is lowercase letters, digits and hyphens then dot png, nothing else`() {
    for (good in listOf("taobao.png", "weixin-mp.png", "xiaohongshu.png", "a1.png", "-.png", "0.png")) {
      assertTrue(good, TellomiBrandIcons.isValidFileName(good))
    }
    for (bad in listOf(
      null,
      "",
      ".png",
      "png",
      "taobao",
      "taobao.PNG",
      "Taobao.png",
      "TAOBAO.png",
      "../x.png",
      "..png/../x.png",
      "a/b.png",
      "/a.png",
      "a\\b.png",
      "..%2Fx.png",
      "a.b.png",
      "a b.png",
      "a.png ",
      " a.png",
      "a.png\n",
      "a\n.png",
      "a.png/",
      "taobao.png.jpg",
      "taobao.jpg",
      "淘宝.png",
      "ta\u0000obao.png"
    )) {
      assertFalse("$bad", TellomiBrandIcons.isValidFileName(bad))
    }
  }

  @Test
  fun `a name that is refused never reaches the package`() {
    val assets = FakeAssets(mapOf("links/icons/x.png" to png(Color.RED)))
    val icons = TellomiBrandIcons(assets::open)

    for (hostile in listOf(null, "", "../x.png", "a/b.png", "Taobao.png", "../../etc/passwd", "links/icons/x.png", "x.png\n")) {
      assertNull("$hostile", icons.resolve(hostile))
    }
    assertEquals(emptyList<String>(), assets.opened)
  }

  @Test
  fun `a good name is opened under links icons and decoded`() {
    val assets = FakeAssets(mapOf("links/icons/taobao.png" to png(Color.rgb(254, 117, 0), width = 114, height = 114)))
    val icon = TellomiBrandIcons(assets::open).resolve("taobao.png")

    assertNotNull(icon)
    assertEquals(114, icon?.width)
    assertEquals(114, icon?.height)
    assertEquals(Color.rgb(254, 117, 0), icon?.getPixel(50, 50))
    assertEquals(listOf("links/icons/taobao.png"), assets.opened)
  }

  @Test
  fun `a file that is not there, or will not decode, is no icon and does not crash`() {
    val assets = FakeAssets(mapOf("links/icons/broken.png" to byteArrayOf(1, 2, 3, 4), "links/icons/empty.png" to ByteArray(0)))
    val icons = TellomiBrandIcons(assets::open)

    assertNull(icons.resolve("missing.png"))
    assertNull(icons.resolve("broken.png"))
    assertNull(icons.resolve("empty.png"))
  }

  @Test
  fun `an opener that throws is no icon`() {
    val icons = TellomiBrandIcons { throw IllegalStateException("no assets") }
    assertNull(icons.resolve("taobao.png"))
  }

  @Test
  fun `an icon is decoded once, and so is a missing one`() {
    val assets = FakeAssets(mapOf("links/icons/taobao.png" to png(Color.RED)))
    val icons = TellomiBrandIcons(assets::open)

    val first = icons.resolve("taobao.png")
    assertSame(first, icons.resolve("taobao.png"))
    assertNull(icons.resolve("missing.png"))
    assertNull(icons.resolve("missing.png"))

    assertEquals(listOf("links/icons/taobao.png", "links/icons/missing.png"), assets.opened)
  }

  @Test
  fun `the icons that ship in the package all decode at the size their registration says`() {
    val assets = ApplicationProvider.getApplicationContext<Application>().assets
    val icons = TellomiBrandIcons { assets.open(it) }

    val expected = mapOf(
      "douban.png" to 200,
      "eleme.png" to 120,
      "iqiyi.png" to 114,
      "taobao.png" to 114,
      "weixin-mp.png" to 180,
      "xiaohongshu.png" to 180,
      "zhihu.png" to 152
    )
    for ((name, side) in expected) {
      val icon = icons.resolve(name)
      assertNotNull(name, icon)
      assertEquals(name, side, icon?.width)
      assertEquals(name, side, icon?.height)
    }
    assertEquals("nothing else is in the folder", expected.keys, assets.list(TellomiBrandIcons.ASSET_DIR)?.toSet())
  }

  @Test
  fun `every icon the registry names for a brand shell is in the package`() {
    val registry = Json.parseToJsonElement(
      requireNotNull(javaClass.classLoader?.getResourceAsStream("links/links-2026101901.json")).use { String(it.readBytes()) }
    ).jsonObject
    val named = registry["payload"]!!.jsonObject["providers"]!!.jsonArray.mapNotNull { it.jsonObject["icon"]?.jsonPrimitive?.content }
    assertTrue(named.isNotEmpty())

    val assets = ApplicationProvider.getApplicationContext<Application>().assets
    val icons = TellomiBrandIcons { assets.open(it) }
    for (name in named) {
      assertTrue(name, TellomiBrandIcons.isValidFileName(name))
      assertNotNull("$name is bundled", icons.resolve(name))
    }
  }

  @Test
  fun `the card carries the icon name when rust-links gives one, and none otherwise`() {
    val base = """{"level":"brand","provider":"taobao","domain":"taobao.com","show_image":false,"tintable":true,"payment":false,"reason":null"""
    assertEquals("taobao.png", TellomiLinkCard.parse("""$base,"icon":"taobao.png"}""")?.icon)
    assertNull(TellomiLinkCard.parse("""$base,"icon":null}""")?.icon)
    assertNull("a libsignal older than the field says nothing", TellomiLinkCard.parse("$base}")?.icon)
    assertEquals("taobao.png", TellomiLinkCard.parse("""$base,"icon":"taobao.png","from_a_newer_libsignal":1}""")?.icon)
  }
}
