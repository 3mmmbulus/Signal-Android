/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.app.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.Hex
import org.signal.libsignal.links.LinkRegistry
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.testutil.UriAttachmentBuilder
import java.util.Optional

/**
 * ADR-0063 §5.1 rule 4 (tellomi/tellomi#1422): every golden `classify` case from rust/links, fed
 * through Android's own shapes (a [LinkPreview] with its thumbnail and raw `rich` bytes, the slide
 * deck's content types), must give exactly the card Rust gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiLinkRegistryTest {

  private fun resource(name: String): ByteArray {
    return requireNotNull(javaClass.classLoader?.getResourceAsStream("links/$name")) { name }.use { it.readBytes() }
  }

  private val golden = Json.parseToJsonElement(String(resource("classify-golden.json"))).jsonObject

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  @After
  fun tearDown() {
    TellomiLinkRegistry.setForTesting(null)
  }

  @Test
  fun `gives the rust-links card for every golden case`() {
    val registry = LinkRegistry.load(resource(golden.str("registry")!!))
    TellomiLinkRegistry.setForTesting(registry)
    val cases = golden["classify"]!!.jsonArray.map { it.jsonObject }
    assert(cases.size >= 40)

    // The golden comes from a libsignal that reports the brand shell's `icon` (libsignal#11 / #13). The build this app pins
    // may be older: its cards have no `icon` key at all. Then the icon is the one field it cannot answer, and every other
    // field is still compared; once the pin moves to a build with the field, `icon` is compared too.
    val first = cases.first()
    val nativeReportsIcon = registry.classify(first.str("preview")!!, first.str("body")!!, first.str("message")?.takeIf { it.isNotEmpty() } ?: "{}").contains("\"icon\":")

    for (case in cases) {
      val name = case.str("name")!!
      val preview = Json.parseToJsonElement(case.str("preview")!!).jsonObject
      val message = Json.parseToJsonElement(case.str("message")?.takeIf { it.isNotEmpty() } ?: "{}").jsonObject

      val thumbnail: Optional<Attachment> = if (preview["has_image"]?.jsonPrimitive?.booleanOrNull == true) {
        Optional.of(UriAttachmentBuilder.build(id = 1, contentType = "image/jpeg"))
      } else {
        Optional.empty()
      }
      val linkPreview = LinkPreview(
        preview.str("url")!!,
        preview.str("title") ?: "",
        preview.str("description") ?: "",
        0,
        thumbnail,
        preview.str("rich")?.let { Hex.fromStringCondensed(it) }
      )

      val card = TellomiLinkRegistry.classify(
        linkPreview,
        case.str("body")!!,
        message["is_story"]?.jsonPrimitive?.boolean ?: false,
        message["attachment_content_types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
      )

      val expected = TellomiLinkCard.parse(case.str("card")!!)
      assertNotNull("golden card parses: $name", expected)
      assertEquals(name, if (nativeReportsIcon) expected else expected?.copy(icon = null), card)
    }
  }

  @Test
  fun `only a brand shell that has a bundled icon carries its file name`() {
    val cards = golden["classify"]!!.jsonArray.map { TellomiLinkCard.parse(it.jsonObject.str("card")!!)!! }

    val withIcon = cards.filter { it.icon != null }
    assertEquals(listOf("taobao.png"), withIcon.map { it.icon })
    assertEquals(TellomiLinkCard.Level.BRAND, withIcon.single().level)
    assertTrue("a brand shell's icon can be drawn and tinted", withIcon.single().tintable && !withIcon.single().payment)

    val alipay = cards.single { it.provider == "alipay" }
    assertNull("no official icon: name and domain only", alipay.icon)
    assertTrue("a payment shell is never tinted", alipay.payment && !alipay.tintable)
    assertTrue(TellomiBrandIcons.isValidFileName(withIcon.single().icon))
  }

  @Test
  fun `makes no decision without a registry`() {
    TellomiLinkRegistry.setForTesting(null)
    val card = TellomiLinkRegistry.classify(
      LinkPreview("https://example.com/", "Example", "", 0, Optional.empty()),
      "https://example.com/",
      false,
      emptyList()
    )
    assertNull(card)
  }

  @Test
  fun `sends rich as hex and says whether there is an image`() {
    val withRich = TellomiLinkCard.previewInputJson(
      LinkPreview("https://a.example/", "A", "", 0, Optional.of(UriAttachmentBuilder.build(id = 2, contentType = "image/png")), byteArrayOf(0x0a, 0x05, 0x76))
    )
    assertEquals("""{"url":"https://a.example/","title":"A","has_image":true,"rich":"0a0576"}""", withRich)

    val plain = TellomiLinkCard.previewInputJson(LinkPreview("https://b.example/", "", "", 0, Optional.empty()))
    assertEquals("""{"url":"https://b.example/","has_image":false}""", plain)
  }

  /** owner 2026-10-17 (ADR-0063 addendum): a preview of a scheme-less / http:// link the registry claims is kept. */
  @Test
  fun `keeps the https preview of a scheme-less or http link the registry claims`() {
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource("links-2026101901.json")))
    val preview = LinkPreview("https://b23.tv/BV1GJ411x7h7", "视频标题", "", 0, Optional.empty())
    for (body in listOf("看这个 b23.tv/BV1GJ411x7h7", "看这个 http://b23.tv/BV1GJ411x7h7")) {
      assertTrue(body, TellomiLinkRegistry.receiveCheck(preview, body, false, emptyList())!!.keepPreview)
      assertNotNull(body, TellomiLinkRegistry.classify(preview, body, false, emptyList()))
    }
    assertTrue(TellomiLinkRegistry.claimsHttpsUpgrade("https://b23.tv/BV1GJ411x7h7"))
    assertTrue(!TellomiLinkRegistry.claimsHttpsUpgrade("https://tell.cc/g/abc"))
    assertTrue(!TellomiLinkRegistry.claimsHttpsUpgrade("http://b23.tv/BV1GJ411x7h7"))
  }

  /** …and an http:// link the registry does not claim is still dropped, as upstream. */
  @Test
  fun `drops the preview of an http link the registry does not claim`() {
    TellomiLinkRegistry.setForTesting(LinkRegistry.load(resource("links-2026101901.json")))
    val preview = LinkPreview("https://www.wikipedia.org/wiki/Tellomi", "维基百科", "", 0, Optional.empty())
    val check = TellomiLinkRegistry.receiveCheck(preview, "看这个 http://www.wikipedia.org/wiki/Tellomi", false, emptyList())
    assertTrue(!check!!.keepPreview)
  }
}
