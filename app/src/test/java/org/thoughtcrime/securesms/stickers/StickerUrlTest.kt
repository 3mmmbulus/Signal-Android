/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.stickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** tellomi/tellomi#1687：贴纸包分享链接发 `tell.cc/s`，老的 `signal.art/addstickers` 照样认。 */
class StickerUrlTest {

  private val packId = "9acc9e8aba563d26a4994e69263e3b25"
  private val packKey = "5a6dff3948c28efb9b7aaf93ecc375c69fc316e78077ed26867a14d10a0f6a12"

  @Test
  fun `share links use tell cc`() {
    assertEquals("https://tell.cc/s#pack_id=$packId&pack_key=$packKey", StickerUrl.createShareLink(packId, packKey))
  }

  @Test
  fun `a share link we create parses back to the same pack`() {
    val parsed = StickerUrl.parseShareLink(StickerUrl.createShareLink(packId, packKey)).get()
    assertEquals(packId, parsed.first)
    assertEquals(packKey, parsed.second)
  }

  @Test
  fun `old signal art links and tell cc with a trailing slash still parse`() {
    for (url in listOf(
      "https://signal.art/addstickers/#pack_id=$packId&pack_key=$packKey",
      "https://tell.cc/s/#pack_id=$packId&pack_key=$packKey"
    )) {
      val parsed = StickerUrl.parseShareLink(url).get()
      assertEquals(url, packId, parsed.first)
      assertEquals(url, packKey, parsed.second)
    }
  }

  @Test
  fun `other hosts and other tell cc paths are rejected`() {
    assertFalse(StickerUrl.isValidShareLink("https://example.com/s#pack_id=$packId&pack_key=$packKey"))
    assertFalse(StickerUrl.isValidShareLink("https://tell.cc/g#pack_id=$packId&pack_key=$packKey"))
  }
}
