/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.maps

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import androidx.annotation.ColorInt
import org.signal.core.util.dp
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.util.MediaUtil
import kotlin.math.min

/**
 * Tellomi (spec attachment-files-location L-11): a location message in the chat is a map, width × half-width, with the place's
 * name and address under it; a plain location is only the map with the time on it. The map is the received snapshot
 * centre-cropped (upstream iOS's square keeps its pin in the middle); there is no map SDK in this cut.
 *
 * Same as iOS `CVComponentTellomiLocationText`, with Telegram Android's sizes (`ChatMessageCell` geo: the title bold 15, the
 * address 13).
 */
object TellomiLocationBubble {

  /** Telegram iOS draws the map at most 300 wide (`maxDimensions.width`); Android uses the same cap. */
  private const val MAX_MAP_WIDTH_DP = 300

  const val TITLE_SIZE_SP = 15
  const val ADDRESS_SIZE_SP = 13
  const val ADDRESS_MAX_LINES = 2

  /**
   * The location in [record], or null: exactly one image (no video, GIF, sticker, file, contact, link preview, poll, view-once)
   * and a body that [TellomiLocationMessage.parse] reads as one.
   */
  @JvmStatic
  fun recognize(record: MessageRecord): TellomiLocationMessage? {
    if (record !is MmsMessageRecord || record.isRemoteDelete || record.isViewOnce) return null
    if (record.linkPreviews.isNotEmpty() || record.sharedContacts.isNotEmpty() || record.poll != null) return null
    val slideDeck = record.slideDeck
    if (slideDeck.textSlide != null) return null
    val slides = slideDeck.slides
    if (slides.size != 1) return null
    val slide = slides[0]
    if (slide !is ImageSlide || slide.hasVideo() || slide.hasSticker() || slide.isBorderless) return null
    return TellomiLocationMessage.parse(record.body)
  }

  /**
   * The chat list and notifications write a location as "📍 Location" (like "📷 Photo"), not its name, address and maps link;
   * places too. Telegram does this on both platforms (Android `MessageObject` geo / venue → `AttachLocation`, iOS
   * `MessageContentKind` `.location` → `Message.Location`). Spec attachment-files-location §3.3.1; same as iOS
   * `TellomiLocationMessage.previewText`.
   */
  const val PREVIEW_EMOJI = "\uD83D\uDCCD"

  /**
   * A reply quoting [body] with [slide] quotes a location: the body reads as one and the quoted attachment is one still image
   * (no GIF, video, sticker or borderless image, as [recognize]). The quote then says "Location" next to the map instead of the text. Same as iOS
   * `TellomiLocationMessage.isQuotedLocation`.
   */
  @JvmStatic
  fun isQuotedLocation(body: CharSequence?, slide: Slide?, slideCount: Int, contentType: String?): Boolean {
    if (body == null || slide == null || slideCount != 1) return false
    if (slide.hasSticker() || slide.isBorderless || slide.isVideoGif || !MediaUtil.isImageType(contentType) || MediaUtil.isGif(contentType)) return false
    return TellomiLocationMessage.parse(body.toString()) != null
  }

  /** The map's size in px: as wide as photos may be, at most 300dp; half as high. */
  @JvmStatic
  fun mapSize(maxMediaWidthPx: Int): IntArray {
    val width = min(maxMediaWidthPx, MAX_MAP_WIDTH_DP.dp)
    return intArrayOf(width, width / 2)
  }

  /**
   * Name (medium [TITLE_SIZE_SP], one line, cut to [availableWidthPx]) and address ([ADDRESS_SIZE_SP], secondary colour) for
   * the bubble's text view, which is limited to 1 + [ADDRESS_MAX_LINES] lines. Null for a plain location: no text under the map.
   */
  @JvmStatic
  fun text(context: Context, location: TellomiLocationMessage, @ColorInt titleColor: Int, @ColorInt addressColor: Int, availableWidthPx: Int): CharSequence? {
    val name = location.name ?: return null
    val density = context.resources.displayMetrics.scaledDensity
    val titlePaint = TextPaint().apply {
      textSize = TITLE_SIZE_SP * density
      typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val title = TextUtils.ellipsize(name, titlePaint, availableWidthPx.toFloat(), TextUtils.TruncateAt.END)

    val builder = SpannableStringBuilder()
    builder.append(title)
    builder.setSpan(AbsoluteSizeSpan(TITLE_SIZE_SP, true), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    builder.setSpan(TypefaceSpan("sans-serif-medium"), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    builder.setSpan(ForegroundColorSpan(titleColor), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val address = location.address
    if (!address.isNullOrEmpty()) {
      builder.append('\n')
      val start = builder.length
      builder.append(address)
      builder.setSpan(AbsoluteSizeSpan(ADDRESS_SIZE_SP, true), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      builder.setSpan(ForegroundColorSpan(addressColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return builder
  }

  /** The bubble's text view shows the name line and at most [ADDRESS_MAX_LINES] address lines. */
  @JvmStatic
  fun maxLines(location: TellomiLocationMessage): Int = if (location.address.isNullOrEmpty()) 1 else 1 + ADDRESS_MAX_LINES
}
