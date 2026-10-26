/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.net.Uri
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.core.models.media.Media
import org.thoughtcrime.securesms.sharing.MultiShareArgs

/**
 * Tellomi（审计 5.4）：转发时隐藏说明文字的规则。对齐 iOS TellomiForwardCaptionPolicyTests。
 */
@RunWith(RobolectricTestRunner::class)
class TellomiForwardCaptionPolicyTest {

  @Test
  fun senderIsAlwaysHidden() {
    assertThat(TellomiForwardCaptionPolicy.SENDER_ALWAYS_HIDDEN).isTrue()
  }

  @Test
  fun captionOnAttachmentCanBeHidden() {
    val args = mediaWithCaption("海边")
    assertThat(TellomiForwardCaptionPolicy.canHideCaption(args)).isTrue()
    val out = TellomiForwardCaptionPolicy.outgoingArgs(args, hideCaption = true)
    assertThat(out.draftText).isNull()
    assertThat(out.media.single().caption).isNull()
    assertThat(out.media.single().uri).isEqualTo(args.media.single().uri)
  }

  @Test
  fun captionKeptWhenToggleOff() {
    val args = mediaWithCaption("海边")
    val out = TellomiForwardCaptionPolicy.outgoingArgs(args, hideCaption = false)
    assertThat(out.draftText).isEqualTo("海边")
  }

  @Test
  fun textOnlyMessageNeverLosesText() {
    val args = MultiShareArgs.Builder(setOf()).withDraftText("你好").build()
    assertThat(TellomiForwardCaptionPolicy.canHideCaption(args)).isFalse()
    val out = TellomiForwardCaptionPolicy.outgoingArgs(args, hideCaption = true)
    assertThat(out.draftText).isEqualTo("你好")
  }

  @Test
  fun whitespaceCaptionCountsAsNone() {
    assertThat(TellomiForwardCaptionPolicy.canHideCaption(mediaWithCaption(" \n "))).isFalse()
    assertThat(TellomiForwardCaptionPolicy.canHideCaption(mediaWithCaption(null))).isFalse()
  }

  @Test
  fun mediaCaptionAloneCanBeHidden() {
    val photo = media(caption = "相册说明")
    val args = MultiShareArgs.Builder(setOf()).withMedia(listOf(photo)).build()
    assertThat(TellomiForwardCaptionPolicy.canHideCaption(args)).isTrue()
    val out = TellomiForwardCaptionPolicy.outgoingArgs(args, hideCaption = true)
    assertThat(out.media.single().caption).isNull()
  }

  @Test
  fun dataUriAttachmentWithDraftIsCaption() {
    val args = MultiShareArgs.Builder(setOf())
      .withDataUri(Uri.parse("content://org.thoughtcrime.securesms/part/3"))
      .withDataType("application/pdf")
      .withDraftText("文件说明")
      .build()
    assertThat(TellomiForwardCaptionPolicy.hasAttachments(args)).isTrue()
    assertThat(TellomiForwardCaptionPolicy.canHideCaption(args)).isTrue()
    assertThat(TellomiForwardCaptionPolicy.outgoingArgs(args, hideCaption = true).draftText).isNull()
  }

  @Test
  fun toggleShownIfAnyItemHasCaption() {
    assertThat(
      TellomiForwardCaptionPolicy.showsHideCaptionToggle(
        listOf(
          MultiShareArgs.Builder(setOf()).withDraftText("纯文字").build(),
          mediaWithCaption("图片说明")
        )
      )
    ).isTrue()
    assertThat(
      TellomiForwardCaptionPolicy.showsHideCaptionToggle(
        listOf(
          MultiShareArgs.Builder(setOf()).withDraftText("纯文字").build(),
          mediaWithCaption(null)
        )
      )
    ).isFalse()
    assertThat(TellomiForwardCaptionPolicy.showsHideCaptionToggle(emptyList())).isFalse()
  }

  @Test
  fun outgoingArgsListAppliesPerItem() {
    val text = MultiShareArgs.Builder(setOf()).withDraftText("你好").build()
    val media = mediaWithCaption("海边")
    val out = TellomiForwardCaptionPolicy.outgoingArgs(listOf(text, media), hideCaption = true)
    assertThat(out[0].draftText).isEqualTo("你好")
    assertThat(out[1].draftText).isNull()
  }

  private fun mediaWithCaption(caption: String?): MultiShareArgs {
    return MultiShareArgs.Builder(setOf())
      .withMedia(listOf(media(caption = null)))
      .withDraftText(caption)
      .build()
  }

  private fun media(caption: String?): Media {
    return Media(
      uri = Uri.parse("content://org.thoughtcrime.securesms/part/1"),
      contentType = "image/jpeg",
      date = 0,
      width = 100,
      height = 100,
      size = 10,
      duration = 0,
      isBorderless = false,
      isVideoGif = false,
      bucketId = null,
      caption = caption,
      transformProperties = null,
      fileName = null
    )
  }
}
