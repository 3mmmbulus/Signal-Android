/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.TellomiMessageExtras
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.SlideDeck

/**
 * ADR-0075 §4.4, owner 2026-10-14 as Telegram: one caption in a message is shown in the bubble; with two or more only the
 * caption for the whole message is (the per-media ones are in the viewer). Messages without either keep their body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiBubbleCaptionTest {

  @Test
  fun `the rule`() {
    assertNull(TellomiCaptionAbove.bubbleCaption(null, listOf(null, "")))
    assertEquals("Trip", TellomiCaptionAbove.bubbleCaption("Trip", listOf(null, null)))
    assertEquals("Sunset", TellomiCaptionAbove.bubbleCaption(null, listOf(null, "Sunset")))
    assertEquals("Trip", TellomiCaptionAbove.bubbleCaption("Trip", listOf("Sunset", null)))
    assertEquals("", TellomiCaptionAbove.bubbleCaption(null, listOf("Sunset", "Beach")))
    assertEquals("", TellomiCaptionAbove.bubbleCaption("", listOf("Sunset", "Beach")))
  }

  @Test
  fun `an ordinary album keeps its body and its layout`() {
    val record = album(body = "caption", captions = listOf(null, null), messageCaption = null)
    assertNull(TellomiCaptionAbove.bubbleCaption(record))
    assertNull(TellomiCaptionAbove.viewerFallback(record))
    assertFalse(record.isDisplayBodyEmpty(ApplicationProvider.getApplicationContext()))
  }

  @Test
  fun `two per-media captions and nothing for the whole message show no caption, and lay out like a captionless album`() {
    val record = album(body = "Sunset\nBeach", captions = listOf("Sunset", "Beach"), messageCaption = null)
    assertEquals("", TellomiCaptionAbove.bubbleCaption(record))
    assertTrue(record.isDisplayBodyEmpty(ApplicationProvider.getApplicationContext()))
    // Items without their own caption show nothing in the viewer, not the text for other clients
    assertEquals("", TellomiCaptionAbove.viewerFallback(record))
  }

  @Test
  fun `the caption for the whole message stays in the bubble and in the viewer`() {
    val record = album(body = "Trip\nSunset", captions = listOf("Sunset", null), messageCaption = "Trip")
    assertEquals("Trip", TellomiCaptionAbove.bubbleCaption(record))
    assertFalse(record.isDisplayBodyEmpty(ApplicationProvider.getApplicationContext()))
    assertEquals("Trip", TellomiCaptionAbove.viewerFallback(record))
  }

  @Test
  fun `a single per-media caption is the bubble's caption`() {
    val record = album(body = "Sunset", captions = listOf(null, "Sunset"), messageCaption = null)
    assertEquals("Sunset", TellomiCaptionAbove.bubbleCaption(record))
    assertFalse(record.isDisplayBodyEmpty(ApplicationProvider.getApplicationContext()))
  }

  private fun album(body: String, captions: List<String?>, messageCaption: String?): MmsMessageRecord {
    val deck = SlideDeck().apply {
      captions.forEachIndexed { index, caption -> addSlide(ImageSlide(image(index, caption))) }
    }
    return FakeMessageRecords.buildMediaMmsMessageRecord(
      body = body,
      slideDeck = deck,
      messageExtras = messageCaption?.let { MessageExtras(tellomi = TellomiMessageExtras(messageCaption = it)) }
    )
  }

  private fun image(id: Int, caption: String?) = UriAttachment(
    dataUri = Uri.parse("content://$id"),
    contentType = "image/jpeg",
    transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
    size = 1000,
    width = 400,
    height = 300,
    fileName = "IMG_$id.JPG",
    fastPreflightId = null,
    voiceNote = false,
    borderless = false,
    videoGif = false,
    quote = false,
    quoteTargetContentType = null,
    caption = caption,
    stickerLocator = null,
    blurHash = null,
    audioHash = null,
    transformProperties = null
  )
}
