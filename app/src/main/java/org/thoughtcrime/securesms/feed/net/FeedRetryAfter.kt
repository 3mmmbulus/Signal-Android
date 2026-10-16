/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * `Retry-After` 响应头（RFC 9110 10.2.3）：整数秒，或者一个 HTTP 日期。
 *
 * [parse] 的规则（和 iOS 同一套）：
 * - 只认非负整数（纯数字）和三种 HTTP 日期（IMF-fixdate、RFC 850、asctime）；其他任何写法（空、负数、小数、`+5`、乱码）当作没有这个头，返回 null，
 *   由调用方走自己的指数退避。
 * - 结果夹在 [[MIN_SECONDS], [MAX_SECONDS]] 之内：`0` 和已经过去的日期算 1 秒（不是「马上」），超过 1 小时按 1 小时。
 * - 日期按 [now] 算差值，向上取整到秒。
 * 服务端（Signal-Server 的 `RateLimitExceededExceptionMapper`）把毫秒向下取整成秒，所以会有 `0`；夹到 1 秒正是为了这个。
 */
object FeedRetryAfter {
  const val MIN_SECONDS = 1L
  const val MAX_SECONDS = 3600L

  // 星期几不参与解析（RFC 9110 允许接收方忽略；写错星期的日期照样读）：先把它去掉再按下面三种格式读
  private val DATE_FORMATS = listOf(
    "dd MMM yyyy HH:mm:ss 'GMT'", // IMF-fixdate: Sun, 06 Nov 1994 08:49:37 GMT
    "dd-MMM-yy HH:mm:ss 'GMT'", // RFC 850: Sunday, 06-Nov-94 08:49:37 GMT
    "MMM d HH:mm:ss yyyy" // asctime: Sun Nov  6 08:49:37 1994
  ).map { DateTimeFormatter.ofPattern(it, Locale.US).withZone(ZoneOffset.UTC) }

  fun clamp(seconds: Long): Long = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

  /** 读不懂返回 null；读得懂返回夹过的秒数。 */
  @JvmStatic
  fun parse(header: String?, now: Instant): Long? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.all { it in '0'..'9' }) {
      // 位数太多的（装不进 Long）直接按上限
      return if (value.length > 12) MAX_SECONDS else clamp(value.toLong())
    }
    val spaced = value.replace(Regex(" +"), " ") // asctime 的个位数日前面是两个空格
    val withoutWeekday = if (',' in spaced) spaced.substringAfter(',').trim() else spaced.substringAfter(' ', "")
    for (format in DATE_FORMATS) {
      val date = try {
        ZonedDateTime.parse(withoutWeekday, format).toInstant()
      } catch (e: DateTimeParseException) {
        continue
      }
      // RFC 850 的两位年份：比现在晚 50 年以上的算上个世纪（RFC 9110 5.6.7）
      val utcNow = now.atZone(ZoneOffset.UTC)
      val utcDate = date.atZone(ZoneOffset.UTC)
      val fixed = if (format === DATE_FORMATS[1] && utcDate.year - utcNow.year > 50) utcDate.minusYears(100).toInstant() else date
      val millis = fixed.toEpochMilli() - now.toEpochMilli()
      return clamp(Math.floorDiv(millis + 999, 1000))
    }
    return null
  }
}
