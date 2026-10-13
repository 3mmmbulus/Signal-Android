/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedMedia

class FeedMediaUrlTest {

  private val base = "https://media.example/feed/"

  @Test
  fun `key is appended to the media base url`() {
    assertThat(FeedMedia(key = "p/abc.jpg").remoteUrlOrNull(base)).isEqualTo("https://media.example/feed/p/abc.jpg")
  }

  @Test
  fun `no base url or a non https one shows nothing`() {
    assertThat(FeedMedia(key = "p/abc.jpg").remoteUrlOrNull(null)).isNull()
    assertThat(FeedMedia(key = "p/abc.jpg").remoteUrlOrNull("http://media.example/")).isNull()
    assertThat(FeedMedia(key = "p/abc.jpg").remoteUrlOrNull("https://media.example")).isNull()
  }

  @Test
  fun `keys that would leave the base are refused`() {
    listOf("", "https://evil.example/x.jpg", "/etc/x.jpg", "../x.jpg", "p/../../x.jpg").forEach {
      assertThat(FeedMedia(key = it).remoteUrlOrNull(base)).isNull()
    }
  }
}
