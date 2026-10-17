/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

/** Tellomi（tellomi/tellomi ADR-0078 §3.4）：被暂停的号码重新注册时 422 里的说明。 */
class TellomiSuspensionNoticeTest {

  private fun body(level: String = "suspended", until: Long = 1_792_000_000, case: String? = "20261017-abcd1234"): String {
    val caseField = if (case != null) ""","case":"$case"""" else ""
    return """{"tellomiRestriction":{"level":"$level","until":$until,"appeal":"abuse@tellomi.app"$caseField}}"""
  }

  @Test
  fun `parses the suspension and turns seconds into milliseconds`() {
    assertThat(TellomiRegistration.suspensionNotice(body()))
      .isEqualTo(TellomiRegistration.SuspensionNotice(1_792_000_000_000, "abuse@tellomi.app", "20261017-abcd1234"))
  }

  @Test
  fun `no case number is fine`() {
    assertThat(TellomiRegistration.suspensionNotice(body(case = null))?.caseId).isNull()
    assertThat(TellomiRegistration.suspensionNotice(body(case = "<script>"))?.caseId).isNull()
  }

  @Test
  fun `anything else is a plain invalid request`() {
    for (bad in listOf(null, "", "Bad request", "{}", "[1]", body(level = "stranger"), body(until = 0))) {
      assertThat(TellomiRegistration.suspensionNotice(bad)).isNull()
    }
    assertThat(TellomiRegistration.suspensionNotice("""{"tellomiRestriction":{"level":"suspended","until":1,"appeal":"nope"}}""")).isNull()
  }
}
