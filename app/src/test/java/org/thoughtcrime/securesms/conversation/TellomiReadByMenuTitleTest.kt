/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.database.GroupReceiptTable

/** 审计 1.3（F19 的一部分）：群里长按自己的消息，「信息」项显示「已读 N/M」。对齐 iOS #377。 */
class TellomiReadByMenuTitleTest {

  private val format = "Read %1\$d/%2\$d"

  @Test
  fun `counts read and viewed, skips skipped`() {
    val counts = TellomiReadByMenuTitle.counts(
      listOf(
        GroupReceiptTable.STATUS_READ,
        GroupReceiptTable.STATUS_VIEWED,
        GroupReceiptTable.STATUS_DELIVERED,
        GroupReceiptTable.STATUS_UNDELIVERED,
        GroupReceiptTable.STATUS_SKIPPED,
        GroupReceiptTable.STATUS_FAILED
      )
    )
    assertEquals(TellomiReadByMenuTitle.Counts(read = 2, total = 5), counts)
  }

  @Test
  fun `counts none read`() {
    val counts = TellomiReadByMenuTitle.counts(
      listOf(
        GroupReceiptTable.STATUS_UNKNOWN,
        GroupReceiptTable.STATUS_UNDELIVERED,
        GroupReceiptTable.STATUS_DELIVERED
      )
    )
    assertEquals(TellomiReadByMenuTitle.Counts(read = 0, total = 3), counts)
  }

  @Test
  fun `only skipped gives nil title`() {
    val counts = TellomiReadByMenuTitle.counts(
      listOf(GroupReceiptTable.STATUS_SKIPPED, GroupReceiptTable.STATUS_SKIPPED)
    )
    assertEquals(0, counts.total)
    assertNull(TellomiReadByMenuTitle.title(format, counts))
  }

  @Test
  fun `title contains both numbers`() {
    val title = TellomiReadByMenuTitle.title(format, TellomiReadByMenuTitle.Counts(read = 3, total = 5))
    requireNotNull(title)
    assertTrue("title=$title", title.contains("3"))
    assertTrue("title=$title", title.contains("5"))
    assertNotEquals("Info", title)
  }

  /** M7：自己关了已读回执时不显示「已读 N/M」，退回「信息」。 */
  @Test
  fun `read receipts disabled gives nil title`() {
    assertNull(
      TellomiReadByMenuTitle.title(
        format,
        TellomiReadByMenuTitle.Counts(read = 3, total = 5),
        readReceiptsEnabled = false
      )
    )
  }
}
