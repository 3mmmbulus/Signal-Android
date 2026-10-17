/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import org.thoughtcrime.securesms.util.LinkUtil

/**
 * owner 2026-10-17 (ADR-0063 addendum "http and scheme-less links"): a link written as `http://b23.tv/abc` or `b23.tv/abc`
 * gets a card over https when the bundled registry claims the https link (a brand or a short link,
 * [TellomiLinkRegistry.claimsHttpsUpgrade]); first-party hosts do not count (the official badge and user cards need https in
 * the body, §4.8), and anything the registry does not claim stays a plain link, as upstream. Only https is ever fetched.
 *
 * Same rules as iOS `LinkValidator.tellomiHttpsUpgrade` and `TellomiLinkClassifier.body(_:upgradingLinkTo:claims:)`.
 */
object TellomiLinkHttpsUpgrade {

  private const val HTTP = "http://"
  private const val HTTPS = "https://"

  /** As rust/links `url_appears_in_body`: what may not come right before a link (ASCII letters, digits, URL punctuation). */
  private const val LINK_PUNCTUATION = "-._~:/?#@\$&*+=%"

  /**
   * Send side: the https link to preview for [detectedUrl] (a [org.signal.core.util.Linkifier] link: `http://…` as written, or
   * a scheme-less link it prefixed with `http://`), or null. Runs the upstream rules on the https spelling, including the
   * glued-text trim, and keeps it only if [claims].
   */
  @JvmStatic
  @JvmOverloads
  fun httpsUpgrade(detectedUrl: String, claims: (String) -> Boolean = TellomiLinkRegistry::claimsHttpsUpgrade): String? {
    if (!detectedUrl.startsWith(HTTP, ignoreCase = true)) {
      return null
    }
    val rest = detectedUrl.substring(HTTP.length)
    if (rest.isEmpty()) {
      return null
    }
    val https = LinkUtil.trimTextGluedAfterUrl(HTTPS + rest)
    if (!LinkUtil.isValidPreviewUrl(https)) {
      return null
    }
    return https.takeIf(claims)
  }

  /**
   * Receive side: the body to give rust/links for a preview of [previewUrl]. When the body writes this link as `http://…` or
   * without a scheme and the registry [claims] its https form, each such occurrence is spelt https, so that rust/links finds the
   * link in the body (whether an occurrence really ends there is still for rust/links to say). Only this copy changes; the
   * message body shown is untouched. Anything else comes back as it was.
   */
  @JvmStatic
  fun body(body: String, previewUrl: String, claims: (String) -> Boolean): String {
    if (!previewUrl.startsWith(HTTPS, ignoreCase = true) || body.contains(previewUrl)) {
      return body
    }
    // As rust/links: one trailing '/' of the preview URL may be missing in the body
    var needle = previewUrl.substring(HTTPS.length)
    if (needle.endsWith("/") && !needle.dropLast(1).endsWith("/") && !needle.dropLast(1).endsWith(":")) {
      needle = needle.dropLast(1)
    }
    if (needle.isEmpty()) {
      return body
    }

    val replaced = ArrayList<IntRange>()
    var from = 0
    while (true) {
      val at = body.indexOf(needle, from)
      if (at < 0) {
        break
      }
      from = at + 1
      val end = at + needle.length
      val before = body.substring(0, at)
      if (before.endsWith(HTTP, ignoreCase = true)) {
        replaced += (at - HTTP.length) until end
      } else if (before.isNotEmpty() && isPartOfLink(before.last())) {
        // Part of a longer link (`evil-b23.tv`, `https://x/b23.tv`) or an email address
        continue
      } else {
        replaced += at until end
      }
    }
    if (replaced.isEmpty() || !claims(previewUrl)) {
      return body
    }

    val out = StringBuilder(body)
    for (range in replaced.asReversed()) {
      out.replace(range.first, range.last + 1, HTTPS + needle)
    }
    return out.toString()
  }

  private fun isPartOfLink(c: Char): Boolean {
    return c.code < 128 && (c.isLetterOrDigit() || c in LINK_PUNCTUATION)
  }
}
