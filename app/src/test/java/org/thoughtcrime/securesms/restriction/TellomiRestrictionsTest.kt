/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.restriction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.TellomiRestrictionValues
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.concurrent.TimeUnit

/** Tellomi（tellomi/tellomi ADR-0078 §3.4）：服务端处置说明的解析、存取和到期。 */
class TellomiRestrictionsTest {

  @get:Rule
  val signalStore = MockSignalStoreRule()

  private var storedJson: String? = null

  @Before
  fun setUp() {
    storedJson = null
    val values = mockk<TellomiRestrictionValues>(relaxUnitFun = true)
    every { values.currentJson } answers { storedJson }
    every { values.currentJson = any() } answers { storedJson = firstArg() }
    every { values.lastRefreshAt } returns 0L
    every { SignalStore.tellomiRestriction } returns values
  }

  private fun body(level: String = "stranger", untilSeconds: Long = future(), case: String? = "20261017-abcd1234"): String {
    val caseField = if (case != null) ""","case":"$case"""" else ""
    return """{"tellomiRestriction":{"level":"$level","until":$untilSeconds,"appeal":"abuse@tellomi.app"$caseField,"extra":1}}"""
  }

  private fun future(): Long = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + TimeUnit.DAYS.toSeconds(7)

  @Test
  fun `parses the server body and turns seconds into milliseconds`() {
    val until = future()
    val restriction = TellomiRestriction.parse(body(untilSeconds = until))
    assertThat(restriction).isEqualTo(TellomiRestriction(TellomiRestriction.Level.STRANGER, TimeUnit.SECONDS.toMillis(until), "abuse@tellomi.app", "20261017-abcd1234"))
  }

  @Test
  fun `a missing case number is fine, anything malformed is ignored`() {
    assertThat(TellomiRestriction.parse(body(case = null))?.caseId).isNull()
    assertThat(TellomiRestriction.parse(body(case = "has spaces"))?.caseId).isNull()
    for (bad in listOf(null, "", "Forbidden", "{}", """{"tellomiRestriction":null}""", body(level = "terminated"), body(untilSeconds = -1))) {
      assertThat(TellomiRestriction.parse(bad)).isNull()
    }
    assertThat(TellomiRestriction.parse("""{"tellomiRestriction":{"level":"all","until":1,"appeal":"not an address"}}""")).isNull()
  }

  @Test
  fun `round-trips through storage`() {
    val restriction = TellomiRestriction.parse(body(level = "all"))!!
    assertThat(TellomiRestriction.parse(TellomiRestriction.toJson(restriction))).isEqualTo(restriction)
  }

  @Test
  fun `asks every six hours, every fifteen minutes while restricted, and after a failed attempt`() {
    val now = System.currentTimeMillis()
    assertThat(TellomiRestrictions.shouldRefresh(now, lastRefreshAt = 0, restricted = false)).isTrue()
    assertThat(TellomiRestrictions.shouldRefresh(now, now - TimeUnit.HOURS.toMillis(5), restricted = false)).isFalse()
    assertThat(TellomiRestrictions.shouldRefresh(now, now - TimeUnit.HOURS.toMillis(6), restricted = false)).isTrue()
    assertThat(TellomiRestrictions.shouldRefresh(now, now - TimeUnit.MINUTES.toMillis(16), restricted = true)).isTrue()
    assertThat(TellomiRestrictions.shouldRefresh(now, now - TimeUnit.MINUTES.toMillis(5), restricted = true)).isFalse()
    assertThat(TellomiRestrictions.shouldRefresh(now, now + TimeUnit.HOURS.toMillis(1), restricted = false)).isTrue()
  }

  @Test
  fun `a forbidden send with the explanation is remembered and stops retries`() {
    assertThat(TellomiRestrictions.onForbidden(body(level = "all"))).isTrue()
    assertThat(TellomiRestrictions.active()?.level).isEqualTo(TellomiRestriction.Level.ALL)
    assertThat(storedJson).isNotNull()
  }

  @Test
  fun `an expired restriction is cleared and does not stop retries`() {
    val past = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - 60
    storedJson = TellomiRestriction.toJson(TellomiRestriction.parse(body(untilSeconds = future()))!!.copy(untilMillis = TimeUnit.SECONDS.toMillis(past)))
    assertThat(TellomiRestrictions.active()).isNull()
    assertThat(storedJson).isNull()
    assertThat(TellomiRestriction.parse(body(untilSeconds = past))!!.isActive(System.currentTimeMillis())).isFalse()
  }
}
