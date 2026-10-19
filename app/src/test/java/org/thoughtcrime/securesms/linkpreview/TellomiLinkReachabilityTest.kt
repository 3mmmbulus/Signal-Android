/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0063 §4.3 本机可达性记录：只在内存，网络变化或 2 分钟后清空。 */
class TellomiLinkReachabilityTest {

  private var now = 0L
  private var network: Any? = "wifi"
  private val reachability = TellomiLinkReachability(clock = { now }, networkId = { network })

  @Test
  fun `a marked host stays unreachable for 2 minutes`() {
    reachability.markUnreachable("www.youtube.com")

    now += TellomiLinkReachability.TTL_MS - 1
    assertTrue(reachability.isUnreachable("www.youtube.com"))

    now += 1
    assertFalse(reachability.isUnreachable("www.youtube.com"))
  }

  @Test
  fun `the memo lasts 2 minutes`() {
    assertTrue(TellomiLinkReachability.TTL_MS == 2 * 60 * 1000L)
  }

  @Test
  fun `host names are case-insensitive and hosts are independent`() {
    reachability.markUnreachable("WWW.Example.org")

    assertTrue(reachability.isUnreachable("www.example.org"))
    assertFalse(reachability.isUnreachable("example.org"))
  }

  @Test
  fun `a network change forgets everything`() {
    reachability.markUnreachable("a.example.org")
    reachability.markUnreachable("b.example.org")

    network = "cellular"

    assertFalse(reachability.isUnreachable("a.example.org"))
    assertFalse(reachability.isUnreachable("b.example.org"))
  }

  @Test
  fun `losing the network also forgets`() {
    reachability.markUnreachable("a.example.org")

    network = null

    assertFalse(reachability.isUnreachable("a.example.org"))
  }
}
