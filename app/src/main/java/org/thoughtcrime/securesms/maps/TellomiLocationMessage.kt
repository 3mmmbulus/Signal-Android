/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.maps

import java.net.URI
import java.net.URLDecoder

/**
 * Tellomi (spec attachment-files-location L-11): a location message, recognised from today's format.
 *
 * There is no structured location field yet (ADR #1123 / L-9 not written). Every Signal client sends a map snapshot
 * and a body whose last line is `https://maps.google.com/maps?q=<lat>,<lng>` (the comma often encoded as `%2C`):
 * - Signal-iOS: `address (several lines)` + a blank line + the link, no name (upstream `Location.messageText`);
 * - Signal-Android: `[name\n][address\n]link`, no blank line ([org.thoughtcrime.securesms.components.location.SignalPlace];
 *   the name is always empty now, older versions sent it for picked places);
 * - Tellomi (from L-11): a picked place sends `name\none-line address\nlink` (Android's format with a name), the rest as Signal-iOS.
 *
 * So: a blank line above the link → no name, everything above is the address; no blank line and two lines or more → the
 * first is the name, the rest the address; one line → the address. Whether the message is "exactly one image" is the
 * caller's to check; this only reads the text. Same rules as iOS `TellomiLocationMessage`.
 */
data class TellomiLocationMessage(
  val latitude: Double,
  val longitude: Double,
  /** The place's name: only a picked place has one (Telegram's venue). */
  val name: String?,
  /** One line of address. */
  val address: String?
) {

  /** A name = a place: the bubble writes name + address under the map; none = a plain location: only the map, the time on it. */
  val isPlace: Boolean get() = name != null

  companion object {
    /** Upstream iOS addresses run to five or six lines; more than this is someone's own text, left as it is. */
    private const val MAX_TEXT_LINES = 6
    private const val MAX_LINE_LENGTH = 300

    @JvmStatic
    fun parse(body: String?): TellomiLocationMessage? {
      if (body == null) return null
      val lines = body.replace("\r\n", "\n").split("\n").map { it.trim() }.toMutableList()
      while (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.size - 1)
      if (lines.isEmpty()) return null
      val (latitude, longitude) = coordinateFromMapsLink(lines.removeAt(lines.size - 1)) ?: return null

      var blankLineBeforeLink = false
      while (lines.lastOrNull()?.isEmpty() == true) {
        lines.removeAt(lines.size - 1)
        blankLineBeforeLink = true
      }
      val textLines = lines.filter { it.isNotEmpty() }
      if (textLines.size > MAX_TEXT_LINES || textLines.any { it.length > MAX_LINE_LENGTH }) return null

      val name: String?
      val addressLines: List<String>
      if (!blankLineBeforeLink && textLines.size >= 2) {
        name = textLines[0]
        addressLines = textLines.drop(1)
      } else {
        name = null
        addressLines = textLines
      }
      return TellomiLocationMessage(latitude, longitude, name, joinedAddress(addressLines))
    }

    /** The coordinates in `https://maps.google.com/maps?q=<lat>,<lng>` (what Signal clients send). The whole line has to be the link. */
    @JvmStatic
    fun coordinateFromMapsLink(line: String): Pair<Double, Double>? {
      if (line.any { it.isWhitespace() }) return null
      val uri = runCatching { URI(line) }.getOrNull() ?: return null
      val scheme = uri.scheme?.lowercase() ?: return null
      if (scheme != "https" && scheme != "http") return null
      if (uri.host?.lowercase() != "maps.google.com") return null
      if ((uri.rawPath ?: "") !in setOf("", "/", "/maps")) return null
      val rawQuery = uri.rawQuery?.split("&")?.firstOrNull { it.startsWith("q=") }?.removePrefix("q=") ?: return null
      val query = runCatching { URLDecoder.decode(rawQuery, "UTF-8") }.getOrNull() ?: return null
      val parts = query.split(",")
      if (parts.size != 2) return null
      val latitude = parts[0].trim().toDoubleOrNull() ?: return null
      val longitude = parts[1].trim().toDoubleOrNull() ?: return null
      if (!latitude.isFinite() || !longitude.isFinite()) return null
      if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
      return latitude to longitude
    }

    /** Upstream iOS's address lines in one: ", " between Latin text, nothing when both sides are Han / kana / hangul ("上海市静安区南京西路"). */
    private fun joinedAddress(lines: List<String>): String? {
      if (lines.isEmpty()) return null
      val result = StringBuilder(lines[0])
      for (line in lines.drop(1)) {
        val cjkJoin = result.isNotEmpty() && isCjk(result.codePointBefore(result.length)) && isCjk(line.codePointAt(0))
        if (!cjkJoin) result.append(", ")
        result.append(line)
      }
      return result.toString()
    }

    private fun isCjk(codePoint: Int): Boolean {
      return codePoint in 0x3040..0x30FF ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xAC00..0xD7AF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0x20000..0x2FA1F
    }
  }
}
