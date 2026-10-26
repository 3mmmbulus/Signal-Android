/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tellomi（审计 7.2 / F03b）：月历缩略图取数辅助。对齐 iOS `TellomiConversationDayThumbnailsTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class TellomiConversationDayThumbnailsTest {

  private val zone = ZoneId.of("Asia/Shanghai")

  @Test
  fun dayIntervalIsOneLocalDay() {
    val noon = LocalDate.of(2026, 10, 18)
    val (start, end) = TellomiConversationDayThumbnails.dayInterval(noon, zone)

    assertThat(start).isEqualTo(
      LocalDate.of(2026, 10, 18).atStartOfDay(zone).toInstant().toEpochMilli()
    )
    assertThat(end).isEqualTo(
      LocalDate.of(2026, 10, 19).atStartOfDay(zone).toInstant().toEpochMilli()
    )
  }

  @Test
  fun textOnlyDaysHaveNoThumbnail() {
    val day = LocalDate.of(2026, 10, 18)
    val result = TellomiConversationDayThumbnails.firstVisualMediaUris(
      threadId = 1L,
      days = listOf(day),
      zoneId = zone,
      lookup = { _, _ -> null }
    )
    assertThat(result).isEmpty()
  }

  @Test
  fun firstPerLocalDayKeepsEarliestInEachDay() {
    val day = LocalDate.of(2026, 10, 18)
    val morning = day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
    val evening = day.atStartOfDay(zone).plusHours(20).toInstant().toEpochMilli()
    val nextMorning = day.plusDays(1).atStartOfDay(zone).plusHours(8).toInstant().toEpochMilli()

    val result = TellomiConversationDayThumbnails.firstPerLocalDay(
      items = listOf(
        morning to "a",
        evening to "b",
        nextMorning to "c"
      ),
      days = setOf(day, day.plusDays(1)),
      zoneId = zone
    )

    assertThat(result).isEqualTo(
      mapOf(
        day to "a",
        day.plusDays(1) to "c"
      )
    )
  }

  @Test
  fun displayUriPrefersDataUriThenThumbnail() {
    val dataUri = mockk<Uri>()
    val thumbUri = mockk<Uri>()

    val withData = mockk<DatabaseAttachment>()
    every { withData.uri } returns dataUri
    every { withData.thumbnailUri } returns thumbUri
    assertThat(TellomiConversationDayThumbnails.displayUri(withData)).isEqualTo(dataUri)

    val thumbOnly = mockk<DatabaseAttachment>()
    every { thumbOnly.uri } returns null
    every { thumbOnly.thumbnailUri } returns thumbUri
    assertThat(TellomiConversationDayThumbnails.displayUri(thumbOnly)).isEqualTo(thumbUri)

    val none = mockk<DatabaseAttachment>()
    every { none.uri } returns null
    every { none.thumbnailUri } returns null
    assertThat(TellomiConversationDayThumbnails.displayUri(none)).isNull()
  }

  @Test
  fun firstVisualMediaUrisMapsLookupResult() {
    val day = LocalDate.of(2026, 10, 18)
    val uri = mockk<Uri>()
    val attachment = mockk<DatabaseAttachment>()
    every { attachment.uri } returns uri
    every { attachment.thumbnailUri } returns null

    val result = TellomiConversationDayThumbnails.firstVisualMediaUris(
      threadId = 42L,
      days = listOf(day),
      zoneId = zone,
      lookup = { start, end ->
        val expectedStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertThat(start).isEqualTo(expectedStart)
        assertThat(end).isEqualTo(expectedEnd)
        attachment
      }
    )

    assertThat(result).isEqualTo(mapOf(day to uri))
  }
}
