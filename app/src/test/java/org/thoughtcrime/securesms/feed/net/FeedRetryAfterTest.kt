/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import java.time.Instant

class FeedRetryAfterTest {

  private val now = Instant.parse("1994-11-06T08:49:00Z")

  private fun parse(header: String?, at: Instant = now) = FeedRetryAfter.parse(header, at)

  @Test
  fun `delta seconds are taken as they are inside 1 to 3600`() {
    assertThat(parse("1")).isEqualTo(1L)
    assertThat(parse("30")).isEqualTo(30L)
    assertThat(parse("3599")).isEqualTo(3599L)
    assertThat(parse("3600")).isEqualTo(3600L)
    assertThat(parse(" 5 ")).isEqualTo(5L)
    assertThat(parse("007")).isEqualTo(7L)
  }

  @Test
  fun `zero is one second and anything above an hour is an hour`() {
    assertThat(parse("0")).isEqualTo(1L)
    assertThat(parse("3601")).isEqualTo(3600L)
    assertThat(parse("86400")).isEqualTo(3600L)
    assertThat(parse("99999999999999999999999")).isEqualTo(3600L)
  }

  @Test
  fun `anything that is not a non-negative integer or an http date is not a header`() {
    for (bad in listOf(null, "", "   ", "-5", "-0", "1.5", "1e3", "+5", "5s", "abc", "0x10", "\u0663", "30 seconds", "Sun, 06 Nov 1994 08:49:37 +0000", "1994-11-06T08:49:37Z", "Mon, 06 Nov 1994 08:49:37 GMT x")) {
      assertThat(parse(bad), "header=$bad").isNull()
    }
  }

  @Test
  fun `an IMF-fixdate is the difference to now, rounded up to whole seconds`() {
    assertThat(parse("Sun, 06 Nov 1994 08:49:37 GMT")).isEqualTo(37L)
    assertThat(parse("Sun, 06 Nov 1994 08:49:37 GMT", Instant.parse("1994-11-06T08:49:00.500Z"))).isEqualTo(37L) // 36.5 s → 37
    assertThat(parse("Sun, 06 Nov 1994 08:50:00 GMT")).isEqualTo(60L)
  }

  @Test
  fun `a date in the past is one second and a date more than an hour away is an hour`() {
    assertThat(parse("Sun, 06 Nov 1994 08:49:00 GMT")).isEqualTo(1L) // exactly now
    assertThat(parse("Sun, 06 Nov 1994 08:00:00 GMT")).isEqualTo(1L)
    assertThat(parse("Sun, 06 Nov 1994 09:49:01 GMT")).isEqualTo(3600L)
    assertThat(parse("Sun, 06 Nov 1994 12:00:00 GMT")).isEqualTo(3600L)
    assertThat(parse("Mon, 07 Nov 1994 08:49:00 GMT")).isEqualTo(3600L)
  }

  @Test
  fun `the two obsolete date formats are accepted too`() {
    assertThat(parse("Sunday, 06-Nov-94 08:49:37 GMT")).isEqualTo(37L) // RFC 850; two digit year 94 next to now = 1994
    assertThat(parse("Sun Nov  6 08:49:37 1994")).isEqualTo(37L) // asctime, two spaces before a one digit day
    assertThat(parse("Sat Nov 5 08:49:37 1994")).isEqualTo(1L) // past
    // RFC 850 year more than 50 years ahead of now means the last century
    val modern = Instant.parse("2026-10-16T00:00:00Z")
    assertThat(parse("Friday, 16-Oct-26 00:01:00 GMT", modern)).isEqualTo(60L)
    assertThat(parse("Sunday, 06-Nov-94 08:49:37 GMT", modern)).isEqualTo(1L)
  }

  @Test
  fun `clamp keeps the range`() {
    assertThat(FeedRetryAfter.clamp(-3)).isEqualTo(1L)
    assertThat(FeedRetryAfter.clamp(0)).isEqualTo(1L)
    assertThat(FeedRetryAfter.clamp(42)).isEqualTo(42L)
    assertThat(FeedRetryAfter.clamp(1_000_000)).isEqualTo(3600L)
  }
}
