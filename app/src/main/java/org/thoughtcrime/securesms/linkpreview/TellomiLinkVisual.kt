/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.WorkerThread
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.core.util.logging.Log
import org.signal.libsignal.links.Links
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.PartAuthority

/**
 * card-visual §3.2 / §3.3 / §3.8 (tellomi/tellomi#1422): the shape of a card and the colours taken from its own image are
 * decided by rust/links (`layout` / `tint`, the same answer on every client). This only reads the answer and applies it.
 */
object TellomiLinkVisual {

  private val TAG = Log.tag(TellomiLinkVisual::class.java)

  /** The reduction rust/links expects: 32 × 32 RGBA. */
  private const val TINT_SIZE = 32

  private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
  private val json = Json { ignoreUnknownKeys = true }

  enum class Layout(val wire: String) {
    FIRST_PARTY("first_party"),
    LARGE_IMAGE("large_image"),
    ICON("icon"),
    NO_IMAGE("no_image")
  }

  data class Colors(val background: Int, val text: Int)

  data class Tint(val tinted: Boolean, val light: Colors? = null, val dark: Colors? = null) {
    fun colors(isDark: Boolean): Colors? = if (tinted) (if (isDark) dark else light) else null
  }

  /** The two native calls; a seam so the reading and reducing can be tested without the native library. */
  interface Bridge {
    fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String
    fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String
  }

  object Native : Bridge {
    override fun layout(imageWidth: Int, imageHeight: Int, kind: String, level: String): String = Links.layout(imageWidth, imageHeight, kind, level)
    override fun tint(layout: String, width: Int, height: Int, rgba: ByteArray): String = Links.tint(layout, width, height, rgba)
  }

  /**
   * What the data layer decided for one card: its shape (null: no decision) and the colours of its own image (null: none).
   * [icon] is the bundled icon of a brand shell that has one (drawn in the icon slot instead of a sender's image, card-visual
   * §3.9); it is shared, so it is only ever read.
   */
  data class Visual(val layout: Layout?, val tint: Tint?, val icon: Bitmap? = null) {
    companion object {
      @JvmField
      val NONE = Visual(null, null)
    }
  }

  private val tints = LruCache<String, Tint>(200)

  @Serializable
  private data class WireColors(val background: String, val text: String)

  @Serializable
  private data class WireTint(val tinted: Boolean, val light: WireColors? = null, val dark: WireColors? = null)

  @JvmStatic
  fun parseLayout(text: String): Layout? = Layout.values().firstOrNull { it.wire == text }

  @JvmStatic
  fun parseTint(text: String): Tint? {
    val wire = try {
      json.decodeFromString<WireTint>(text)
    } catch (e: Exception) {
      return null
    }
    if (!wire.tinted) {
      return Tint(tinted = false)
    }
    val light = wire.light?.let(::colors) ?: return null
    val dark = wire.dark?.let(::colors) ?: return null
    return Tint(tinted = true, light = light, dark = dark)
  }

  /**
   * card-visual §3.2: the image of a large-image card fills the card's width, and its box is between 1.91:1 (wide) and 1:1
   * (square); a picture that is wider or taller than that is cropped at the centre. So, at a card [width] px wide, the box is
   * [largeImageMinHeight] to [largeImageMaxHeight] px high. 1.91 is written as 191 ÷ 100 so the arithmetic is exact.
   */
  private const val LARGE_IMAGE_WIDEST = 191
  private const val LARGE_IMAGE_WIDEST_SCALE = 100

  /** The lowest the box gets: ⌈width ÷ 1.91⌉, the widest it may be. */
  @JvmStatic
  fun largeImageMinHeight(width: Int): Int = (width * LARGE_IMAGE_WIDEST_SCALE + LARGE_IMAGE_WIDEST - 1) / LARGE_IMAGE_WIDEST

  /** The highest the box gets: square. */
  @JvmStatic
  fun largeImageMaxHeight(width: Int): Int = width

  /**
   * §3.3: never for first-party or payment cards, only when there is an image to take colours from. Nor in a message
   * request: that draws the domain-only card ([TellomiLinkOnly.decideRequest]), which is not tintable and never asks for this.
   */
  @JvmStatic
  fun shouldTint(card: TellomiLinkCard?, layout: Layout?): Boolean {
    if (card == null || !card.tintable || card.payment) {
      return false
    }
    return layout == Layout.ICON || layout == Layout.LARGE_IMAGE
  }

  /** The visual of the card [decision] made for [record]: the preview's image is the one shown, and it is read from the device. */
  @JvmStatic
  @WorkerThread
  fun forMessage(record: MessageRecord, decision: TellomiLinkOnly.Decision): Visual {
    val card = decision.card ?: return Visual.NONE
    if ((card.level == TellomiLinkCard.Level.BRAND && !card.showImage) || usesBundledIcon(card)) {
      // A brand shell never shows the sender's image (show_image is false); it shows the icon that ships with the app.
      // So does a claimed-tier page card whose page gave no image (registry 2026-10-19): the platform's bundled icon.
      return decideBrand(Native, TellomiBrandIcons.bundled, card)
    }
    val preview = decision.localPreview ?: (record as? MmsMessageRecord)?.linkPreviews?.firstOrNull()
    val image: Attachment? = if (card.showImage) preview?.thumbnail?.orElse(null) else null
    if (image != null && (image.width <= 0 || image.height <= 0)) {
      // Its size is not known yet (still downloading): keep Signal's display until it is, rather than flip from a card without an image.
      return Visual.NONE
    }
    val uri = image?.uri
    return decide(card, image?.width ?: 0, image?.height ?: 0, uri?.toString()) {
      if (image?.transferState != AttachmentTable.TRANSFER_PROGRESS_DONE || uri == null) {
        null
      } else {
        try {
          PartAuthority.getAttachmentStream(AppDependencies.application, uri).use { it.readBytes() }
        } catch (e: Exception) {
          Log.w(TAG, "Could not read the card image", e)
          null
        }
      }
    }
  }

  /**
   * The shape and colours of one card, decided off the main thread with the card (ADR-0063 §5.1 rule 4). The image is the
   * one the card shows ([imageWidth] × [imageHeight], 0 × 0 for none); [imageKey] names it for the cache and
   * [readImage] reads its bytes, or null while it is not on the device yet.
   */
  @JvmStatic
  @WorkerThread
  fun decide(card: TellomiLinkCard?, imageWidth: Int, imageHeight: Int, imageKey: String?, readImage: () -> ByteArray?): Visual {
    return decide(Native, card, imageWidth, imageHeight, imageKey, readImage)
  }

  @WorkerThread
  fun decide(bridge: Bridge, card: TellomiLinkCard?, imageWidth: Int, imageHeight: Int, imageKey: String?, readImage: () -> ByteArray?): Visual {
    if (card == null) {
      return Visual.NONE
    }
    val layout = layout(bridge, card, imageWidth, imageHeight) ?: return Visual.NONE
    if (!shouldTint(card, layout) || imageKey == null) {
      return Visual(layout, null)
    }

    val key = "${layout.wire}\u0000$imageKey"
    val cached = tints.get(key)
    if (cached != null) {
      return Visual(layout, cached.takeIf { it.tinted })
    }
    val bytes = readImage() ?: return Visual(layout, null)
    val tint = tint(bridge, layout, bytes)
    if (tint != null) {
      tints.put(key, tint)
    }
    return Visual(layout, tint?.takeIf { it.tinted })
  }

  /**
   * The shape and colours of a brand shell (card-visual §3.7 / §3.9): with a readable bundled icon, `layout` is asked the
   * icon's size and `tint` gets the icon's own pixels; without one (no `icon`, a name that is refused, a file that is
   * missing or will not decode) it is exactly the card it has always been, name and domain only. The sender's image plays
   * no part. Payment shells are never tinted ([shouldTint]), whatever they carry.
   */
  @JvmStatic
  @WorkerThread
  fun decideBrand(bridge: Bridge, icons: TellomiBrandIcons, card: TellomiLinkCard): Visual {
    val icon = if (usesBundledIcon(card)) icons.resolve(card.icon) else null
    if (icon == null) {
      return decide(bridge, card, 0, 0, null) { null }
    }

    val layout = askLayout(bridge, icon.width, icon.height, "", levelName(card.level)) ?: return Visual.NONE
    if (layout != Layout.ICON) {
      return Visual(layout, null)
    }
    if (!shouldTint(card, layout)) {
      return Visual(layout, null, icon)
    }

    val key = "${layout.wire}\u0000icon:${card.icon}"
    val cached = tints.get(key)
    if (cached != null) {
      return Visual(layout, cached.takeIf { it.tinted }, icon)
    }
    val tint = tint(bridge, layout, icon)
    if (tint != null) {
      tints.put(key, tint)
    }
    return Visual(layout, tint?.takeIf { it.tinted }, icon)
  }

  /**
   * Whether the card's image is the icon bundled with the app: a brand shell with an `icon`, or a claimed-tier page card
   * (`generic` with an `icon`, registry links-2026101901, PM 2026-10-19) whose page gave no image (show_image false).
   * rust/links gives `icon` on those two only. The no-image card (link icon) is left for a card with nothing at all.
   */
  @JvmStatic
  fun usesBundledIcon(card: TellomiLinkCard): Boolean {
    if (card.icon == null) {
      return false
    }
    return when (card.level) {
      TellomiLinkCard.Level.BRAND -> true
      TellomiLinkCard.Level.GENERIC -> !card.showImage
      else -> false
    }
  }

  /**
   * A card of a claimed-tier platform (links-2026101901): rust/links' `reason` is `claimed` (the page card) or
   * `claimed_no_title` (no title: the platform's brand shell).
   */
  @JvmStatic
  fun isClaimed(card: TellomiLinkCard?): Boolean = card?.reason == "claimed" || card?.reason == "claimed_no_title"

  /**
   * Whether a card of [layout] gets the link icon at the end of its title line (PM 2026-10-19, evening): only a card with no
   * image; and not a claimed-tier card, which always has a title or the platform's name — the link icon is only for a card
   * with nothing at all. A claimed card whose sender fetched the site icon is the icon card with the icon's colours instead.
   */
  @JvmStatic
  fun showsLinkIcon(card: TellomiLinkCard?, layout: Layout?): Boolean = layout == Layout.NO_IMAGE && !isClaimed(card)

  /** The layout as [TellomiLinkCardStyle] reads it; first-party and no decision are null (first-party cards keep their own style). */
  @JvmStatic
  fun styleLayout(layout: Layout?): TellomiLinkCardStyle.Layout? = when (layout) {
    Layout.LARGE_IMAGE -> TellomiLinkCardStyle.Layout.LARGE_IMAGE
    Layout.ICON -> TellomiLinkCardStyle.Layout.ICON
    Layout.NO_IMAGE -> TellomiLinkCardStyle.Layout.NO_IMAGE
    Layout.FIRST_PARTY, null -> null
  }

  /** Null is "no decision": the card shows the way it did before layout existed. */
  @JvmStatic
  fun layout(bridge: Bridge, card: TellomiLinkCard, imageWidth: Int, imageHeight: Int): Layout? {
    val width = if (card.showImage) imageWidth else 0
    val height = if (card.showImage) imageHeight else 0
    return askLayout(bridge, width, height, if (card.showImage) card.kind.orEmpty() else "", levelName(card.level))
  }

  private fun askLayout(bridge: Bridge, width: Int, height: Int, kind: String, level: String): Layout? {
    return try {
      parseLayout(bridge.layout(width, height, kind, level))
    } catch (e: Exception) {
      Log.w(TAG, "layout failed", e)
      null
    }
  }

  @JvmStatic
  @WorkerThread
  fun tint(bridge: Bridge, layout: Layout, imageBytes: ByteArray): Tint? {
    val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
    return try {
      tint(bridge, layout, decoded)
    } finally {
      decoded.recycle()
    }
  }

  /** The colours of an image that is already decoded (the bundled icon of a brand shell); [image] is left as it is. */
  @JvmStatic
  @WorkerThread
  fun tint(bridge: Bridge, layout: Layout, image: Bitmap): Tint? {
    val rgba = reduce(image)
    return try {
      parseTint(bridge.tint(layout.wire, TINT_SIZE, TINT_SIZE, rgba))
    } catch (e: Exception) {
      Log.w(TAG, "tint failed", e)
      null
    }
  }

  private fun levelName(level: TellomiLinkCard.Level): String = when (level) {
    TellomiLinkCard.Level.PLAIN_LINK -> "plain_link"
    TellomiLinkCard.Level.GENERIC -> "generic"
    TellomiLinkCard.Level.BRAND -> "brand"
    TellomiLinkCard.Level.STRUCTURED -> "structured"
    TellomiLinkCard.Level.FIRST_PARTY -> "first_party"
  }

  private fun colors(wire: WireColors): Colors? {
    if (!HEX_COLOR.matches(wire.background) || !HEX_COLOR.matches(wire.text)) {
      return null
    }
    return Colors(parse(wire.background), parse(wire.text))
  }

  private fun parse(hex: String): Int = (0xFF000000L or hex.substring(1).toLong(16)).toInt()

  /** [image] to 32 × 32 RGBA; it is not recycled (the scaled copy is, when there is one). */
  private fun reduce(image: Bitmap): ByteArray {
    val scaled = Bitmap.createScaledBitmap(image, TINT_SIZE, TINT_SIZE, true)
    val pixels = IntArray(TINT_SIZE * TINT_SIZE)
    scaled.getPixels(pixels, 0, TINT_SIZE, 0, 0, TINT_SIZE, TINT_SIZE)
    if (scaled !== image) {
      scaled.recycle()
    }

    val out = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { i, argb ->
      out[i * 4] = (argb shr 16).toByte()
      out[i * 4 + 1] = (argb shr 8).toByte()
      out[i * 4 + 2] = argb.toByte()
      out[i * 4 + 3] = (argb ushr 24).toByte()
    }
    return out
  }
}
