package org.thoughtcrime.securesms.util

import com.google.common.net.InetAddresses
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.signal.core.util.Linkifier
import org.thoughtcrime.securesms.stickers.StickerUrl
import java.net.URI
import java.net.URISyntaxException
import java.util.Objects
import java.util.regex.Pattern

/**
 * Utilities for validating various links for multiple situations.
 */
object LinkUtil {
  private val DOMAIN_PATTERN = Pattern.compile("^(https?://)?([^/]+).*$")
  private val ALL_ASCII_PATTERN = Pattern.compile("^[\\x00-\\x7F]*$")
  private val ALL_NON_ASCII_PATTERN = Pattern.compile("^[^\\x00-\\x7F]*$")
  private val ILLEGAL_CHARACTERS_PATTERN = Pattern.compile("[\u202C\u202D\u202E\u2500-\u25FF]")
  private val ILLEGAL_PERIODS_PATTERN = Pattern.compile("(\\.{2,}|…)")

  private val INVALID_DOMAINS = listOf("example", "example\\.com", "example\\.net", "example\\.org", "i2p", "invalid", "localhost", "onion", "test")
  private val INVALID_DOMAINS_REGEX: Regex = Regex("^(.+\\.)?(${INVALID_DOMAINS.joinToString("|")})\\.?\$")

  /**
   * Link previews must have all valid URL characters, an allowed domain if present, and must include https://
   */
  @JvmStatic
  fun isValidPreviewUrl(linkUrl: String?): Boolean {
    if (linkUrl == null) {
      return false
    }

    if (StickerUrl.isValidShareLink(linkUrl)) {
      return true
    }

    val (isLegal, domain) = isLegalUrlInternal(linkUrl)

    if (!isLegal || domain?.matches(INVALID_DOMAINS_REGEX) == true) {
      return false
    }

    if (!isValidURI(linkUrl)) {
      return false
    }

    val httpUrl = linkUrl.toHttpUrlOrNull() ?: return false

    if (httpUrl.scheme != "https") {
      return false
    }

    val host = httpUrl.host

    if (host.matches(INVALID_DOMAINS_REGEX)) {
      return false
    }

    if (isPrivateOrLocalHost(host)) {
      return false
    }

    return true
  }

  /**
   * Text story link previews must have all valid URL characters, a present and allowed domain, and must have a TLD.
   */
  @JvmStatic
  fun isValidTextStoryPostPreview(url: String): Boolean {
    val (isLegal, domain) = isLegalUrlInternal(url)

    if (!isLegal || domain == null || domain.matches(INVALID_DOMAINS_REGEX)) {
      return false
    }

    return domain.lastIndexOf('.', domain.lastIndex) != -1
  }

  /**
   * A URL is legal if it has all valid URL characters.
   */
  @JvmStatic
  fun isLegalUrl(url: String): Boolean {
    return isLegalUrlInternal(url).isLegal
  }

  private fun isLegalUrlInternal(url: String): LegalCharactersResult {
    if (ILLEGAL_CHARACTERS_PATTERN.matcher(url).find()) {
      return LegalCharactersResult(false)
    }

    val matcher = DOMAIN_PATTERN.matcher(url)
    if (!matcher.matches()) {
      return LegalCharactersResult(false)
    }

    val domain = Objects.requireNonNull(matcher.group(2))

    if (ILLEGAL_PERIODS_PATTERN.matcher(domain).find()) {
      return LegalCharactersResult(false)
    }

    val cleanedDomain = domain.replace("\\.".toRegex(), "")
    return LegalCharactersResult(
      isLegal = ALL_ASCII_PATTERN.matcher(cleanedDomain).matches() || ALL_NON_ASCII_PATTERN.matcher(cleanedDomain).matches(),
      domain = domain
    )
  }

  @JvmStatic
  private fun isValidURI(linkUri: String?): Boolean {
    return if (linkUri == null) {
      false
    } else {
      try {
        val ignored = URI(linkUri)
        true
      } catch (e: URISyntaxException) {
        false
      }
    }
  }

  private fun isPrivateOrLocalHost(host: String): Boolean {
    if (!InetAddresses.isInetAddress(host)) {
      return false
    }

    val address = InetAddresses.forString(host)

    if (address.isAnyLocalAddress ||
      address.isLoopbackAddress ||
      address.isLinkLocalAddress ||
      address.isSiteLocalAddress ||
      address.isMulticastAddress
    ) {
      return true
    }

    // IPv6 unique local addresses (fc00::/7) are not covered by the standard helpers above.
    val bytes = address.address
    return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
  }

  /**
   * Tellomi (2026-10-17, TestFlight feedback "many links don't become info cards"): text glued right after a link with no
   * space, e.g. "看这个https://b23.tv/abc很好", gets taken into the link, and the preview then fetches a path that doesn't
   * exist. Cut at the first non-ASCII character, the same rule as iOS `LinkValidator.tellomiUrlTrimmedBeforeGluedText`:
   * - only inside path / query / fragment, never in the host (a mixed-script host is a spoofing risk);
   * - only right after an ASCII letter or digit ("abc很好", ".html网易新闻"); never after `/ ? = & #`, because
   *   "/wiki/中国" cut to "/wiki/" is another page, so such links are left as they are.
   * The receiver's libsignal `url_appears_in_body` already treats a non-ASCII character as the end of a link.
   */
  @JvmStatic
  fun trimTextGluedAfterUrl(url: String): String {
    val cut = url.indexOfFirst { it.code > 0x7F }
    if (cut <= 0) {
      return url
    }
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) {
      return url
    }
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd + 3)
    if (authorityEnd < 0 || authorityEnd >= cut) {
      return url
    }
    val last = url[cut - 1]
    if (!(last in '0'..'9' || last in 'a'..'z' || last in 'A'..'Z')) {
      return url
    }
    return url.substring(0, cut)
  }

  /** Chinese sentence punctuation and closing brackets / quotes: the same table as iOS `LinkValidator.isIriTerminator`. */
  private const val CJK_URL_TERMINATORS = "，。！？；：、）」』】》〉”’"

  /**
   * Tellomi (PM 2026-10-19, ADR-0063 §5.6): a link with Chinese in its path / query / fragment gets a preview (as Telegram,
   * behaviour only). [Linkifier] already ends the link at whitespace (full-width and no-break spaces included) and at
   * bidi / zero-width characters, and the raw IRI is sent as the preview URL (old receivers accept it too). This only adds
   * what iOS does on top: the link also ends at Chinese sentence punctuation, so "…/wiki/中国，很好" previews "…/wiki/中国".
   * Only in path / query / fragment; the host is never touched.
   */
  @JvmStatic
  fun cutAtCjkSentencePunctuation(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) {
      return url
    }
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd + 3)
    if (authorityEnd < 0) {
      return url
    }
    val cut = url.indexOfAny(CJK_URL_TERMINATORS.toCharArray(), authorityEnd)
    return if (cut < 0) url else url.substring(0, cut)
  }

  /**
   * Tellomi (PM 2026-10-19, ADR-0063 §5.6): a link with a zero-width or direction-control character in it gets no preview at
   * all and stays a plain link, because such characters are used to disguise a URL. It is not cut short before the character
   * and previewed (same rule as iOS `LinkValidator.tellomiPercentEncodedIri(_:followedBy:)`, behaviour only).
   *
   * [previewUrl] is what would be previewed for [link] in [text]: [Linkifier.DetectedLink.url] cut short and possibly respelt
   * with https. True when the part of [text] it was taken from has a hidden character in it, or is followed right away by
   * one, so the link did not end normally there (at whitespace or punctuation) but at the hidden character. A hidden
   * character after whitespace or punctuation is not part of the link. Hidden: the same categories as iOS (format, control,
   * private use, surrogate, unassigned, line / paragraph separator); a line break after a link is a normal end
   * ([Linkifier.isHiddenCharacter]).
   */
  @JvmStatic
  fun hasHiddenCharacter(text: CharSequence, link: Linkifier.DetectedLink, previewUrl: String): Boolean {
    val written = text.subSequence(link.start, link.end).toString()
    val writtenScheme = written.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val previewScheme = previewUrl.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val end = (link.start + writtenScheme + previewUrl.length - previewScheme).coerceIn(link.start, link.end)

    var i = link.start
    while (i < end) {
      val codePoint = Character.codePointAt(text, i)
      if (isHiddenCodePoint(codePoint)) {
        return true
      }
      i += Character.charCount(codePoint)
    }
    if (end < text.length) {
      val next = Character.codePointAt(text, end)
      // The same test Linkifier uses for a hidden character right after a link, so a link is either tappable and previewed or
      // neither: line breaks (tab, LF, CR, U+0085, U+2028/2029) are a normal end, U+001C–U+001F are hidden (as on iOS)
      if (Linkifier.isHiddenCharacter(next)) {
        return true
      }
    }
    return false
  }

  private fun isHiddenCodePoint(codePoint: Int): Boolean {
    return when (Character.getType(codePoint).toByte()) {
      Character.FORMAT,
      Character.CONTROL,
      Character.PRIVATE_USE,
      Character.SURROGATE,
      Character.UNASSIGNED,
      Character.LINE_SEPARATOR,
      Character.PARAGRAPH_SEPARATOR -> true
      else -> false
    }
  }

  private data class LegalCharactersResult(val isLegal: Boolean, val domain: String? = null)
}
