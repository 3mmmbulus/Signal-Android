/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import java.net.URI

/**
 * The one style of a third-party link card in a conversation (PM 2026-10-19, after Apple Messages; DESIGN.md §9). The Android
 * port of iOS `TellomiLinkCardStyle.swift` (tellomi/Signal-iOS tellomi aa97c012): the same decisions, the same numbers.
 *
 * - [Kind.LARGE_IMAGE]: a large image on top, the title and the domain under it, neutral grey;
 * - [Kind.ICON_STRIP]: a small image (site icon, square picture, a brand shell's bundled icon): one strip, the title (bold) and the
 *   domain (grey) on the left, the icon on the right; the strip takes the icon's colours (rust/links `tint`), neutral grey without;
 * - [Kind.TEXT_STRIP]: no image, a title: neutral grey strip, title and domain, nothing on the right;
 * - [Kind.DOMAIN_STRIP]: nothing was fetched: neutral grey strip, the domain only, a compass on the right.
 *
 * Large or small is still rust/links' `layout`. No sub line (the sender's description, kind names such as 「网页」) in any of them.
 * First-party cards, group invites and call links do not come here. Pure logic, no Android types, so it is unit tested on the JVM.
 */
object TellomiLinkCardStyle {

  enum class Kind {
    LARGE_IMAGE,
    ICON_STRIP,
    TEXT_STRIP,
    DOMAIN_STRIP
  }

  /** The layout the data layer gave ([TellomiLinkVisual.Layout] without first-party); null: no decision. */
  enum class Layout {
    LARGE_IMAGE,
    ICON,
    NO_IMAGE
  }

  /** The two lines on the card. [title] null: the domain is the only line. */
  data class Text(val title: String?, val domain: String)

  /** Neutral grey: light #E9E9EB, dark #2C2C2E (iOS `TellomiLinkCardColors.neutralBackground`, DESIGN.md §1.5 / §9). */
  const val NEUTRAL_LIGHT: Int = 0xFFE9E9EB.toInt()
  const val NEUTRAL_DARK: Int = 0xFF2C2C2E.toInt()

  /** Geometry, in dp (DESIGN.md §9, iOS `CVLinkPreviewViewAdapterTellomiStrip` / `...Large`). */
  const val ICON_DP: Int = 36
  const val ICON_RADIUS_DP: Int = 8
  const val COMPASS_DP: Int = 26
  const val TRAILING_GAP_DP: Int = 10
  const val LINE_GAP_DP: Int = 2

  /** Strip paddings (start, top, end, bottom): with a trailing icon or compass, and without one. Large image: the text bar. */
  @JvmField val STRIP_PADDING_DP: IntArray = intArrayOf(14, 9, 10, 9)

  @JvmField val STRIP_NO_TRAILING_PADDING_DP: IntArray = intArrayOf(14, 9, 14, 10)

  @JvmField val LARGE_IMAGE_TEXT_PADDING_DP: IntArray = intArrayOf(14, 9, 14, 10)

  /** Type sizes in sp: iOS subheadline (15, title semibold; the only line of a domain strip, regular) and footnote (13, domain). */
  const val TITLE_SP: Int = 15
  const val DOMAIN_SP: Int = 13

  /** The title is at most two lines, cut off with an ellipsis. */
  const val MAX_TITLE_LINES: Int = 2

  /**
   * Which of the four styles.
   * - [hasImage]: there is an image to draw now (downloaded, or a brand shell's bundled icon);
   * - [layout]: rust/links' layout; null (no decision): an image is taken as small, never guessed large;
   * - [isPlainLink]: a card drawn from the URL alone (no preview, or rust/links said plain link): always the domain only.
   */
  @JvmStatic
  fun kind(layout: Layout?, hasImage: Boolean, hasTitle: Boolean, isPlainLink: Boolean): Kind {
    if (isPlainLink) {
      return Kind.DOMAIN_STRIP
    }
    if (hasImage) {
      when (layout) {
        Layout.LARGE_IMAGE -> return Kind.LARGE_IMAGE
        Layout.ICON, null -> return Kind.ICON_STRIP
        Layout.NO_IMAGE -> Unit
      }
    }
    return if (hasTitle) Kind.TEXT_STRIP else Kind.DOMAIN_STRIP
  }

  /**
   * Whether the strip has the compass on the right (PM, since TestFlight 31; confirmed 2026-10-24): only a card with nothing at
   * all, the grey domain strip. An icon strip has its icon; a title strip is its title and domain with nothing on the right,
   * claimed or not. [isClaimed] no longer changes the answer; it is kept so callers say what they know.
   */
  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  fun showsCompass(kind: Kind, isClaimed: Boolean): Boolean = kind == Kind.DOMAIN_STRIP

  /** Only an icon strip takes colours from its image; every other card is neutral grey (a large image card too). */
  @JvmStatic
  fun isTinted(kind: Kind): Boolean = kind == Kind.ICON_STRIP

  /**
   * The two lines: the title the data layer decided (og:title / title); a blank title, or one equal to the domain, is not shown.
   * Without a domain, the URL's host (without `www.`); without a host either, null: the caller draws no card.
   */
  @JvmStatic
  fun text(title: String?, domain: String?, url: String?): Text? {
    val cleanDomain = domain?.let(::clean) ?: url?.let(::hostForDisplay) ?: return null
    val cleanTitle = title?.let(::clean)?.takeUnless { it.equals(cleanDomain, ignoreCase = true) }
    return Text(cleanTitle, cleanDomain)
  }

  /** `https://www.Baidu.com/s?wd=1` → `baidu.com`; one without a scheme (`jd.com/x`) is read as https. */
  @JvmStatic
  fun hostForDisplay(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) {
      return null
    }
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val host = try {
      URI(withScheme).host
    } catch (e: Exception) {
      null
    }?.lowercase()
    if (host.isNullOrEmpty()) {
      return null
    }
    return if (host.startsWith("www.") && host.length > 4) host.substring(4) else host
  }

  /** Unicode white space (an ideographic space too), as iOS `.whitespacesAndNewlines`. */
  private val WHITESPACE = Regex("(?U)\\s+")

  private fun clean(text: String): String? {
    val collapsed = text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    return collapsed.ifEmpty { null }
  }
}
