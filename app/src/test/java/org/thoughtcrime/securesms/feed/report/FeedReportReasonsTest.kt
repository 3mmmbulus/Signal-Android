/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class FeedReportReasonsTest {

  @Test
  fun `no table from the engine uses the built-in four groups`() {
    val groups = FeedReportReasons.groups(emptyMap())

    assertThat(groups.map { it.code }).containsExactly("harm_others", "illegal", "false_or_impersonation", "my_rights")
    assertThat(groups.flatMap { it.reasons }.size == 17).isTrue()
  }

  @Test
  fun `engine order is kept, unknown codes and empty groups are left out`() {
    val groups = FeedReportReasons.groups(mapOf("illegal" to listOf("drugs", "fraud", "brand_new"), "my_rights" to listOf("nope")))

    assertThat(groups.map { it.code }).containsExactly("illegal")
    assertThat(groups.single().reasons.map { it.code }).containsExactly("drugs", "fraud")
  }

  @Test
  fun `screenshots, relationship and emergency follow item 32`() {
    val all = FeedReportReasons.groups(emptyMap()).flatMap { it.reasons }.associateBy { it.code }

    assertThat(all.getValue("fraud").needsEvidence).isTrue()
    assertThat(all.getValue("gambling").needsEvidence).isFalse()
    assertThat(all.getValue("privacy").needsEvidence).isTrue()
    assertThat(all.getValue("privacy").needsRelationship).isTrue()
    assertThat(all.getValue("fraud").needsRelationship).isFalse()
    assertThat(all.getValue("self_harm").isEmergency).isTrue()
    assertThat(all.getValue("spam").isEmergency).isFalse()
  }
}
