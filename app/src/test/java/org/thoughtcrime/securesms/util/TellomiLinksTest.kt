/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

/**
 * Tellomi：`tell.cc` 明文用户名链接的解析（[TellomiLinks.parsePlainUsernameFromLink]）。
 *
 * tellomi/tellomi#1106（ADR-0066）之后，用户名不带「.数字」：`tell.cc/kaixin` 要认成 `kaixin.01`；
 * 旧的带后缀形状照旧认；保留路径（`/u` `/g` `/call` …）和 `/.well-known` 不能被当成用户名。
 */
class TellomiLinksTest {

  @Test
  fun bareNicknameIsCompletedWithTheFixedDiscriminator() {
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/kaixin")).isEqualTo("kaixin.01")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/kaixin/")).isEqualTo("kaixin.01")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/kaixin?from=wechat")).isEqualTo("kaixin.01")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("tellomi://tell.cc/kaixin")).isEqualTo("kaixin.01")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("tellomi://tell.cc/u#u/kaixin")).isEqualTo("kaixin.01")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/_abc")).isEqualTo("_abc.01")
  }

  /** 旧账号带随机后缀：原样返回，按全名去查。 */
  @Test
  fun usernamesWithADiscriminatorAreKeptAsIs() {
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/ceshi.57")).isEqualTo("ceshi.57")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/ceshi.57?from=wechat")).isEqualTo("ceshi.57")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("tellomi://tell.cc/u#u/ceshi.57")).isEqualTo("ceshi.57")
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/kaixin.01")).isEqualTo("kaixin.01")
  }

  /** 3 位以上的保留路径要显式挡；大小写不影响。 */
  @Test
  fun reservedFirstLevelPathsAreNotUsernames() {
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/call#key=abc")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/call")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/app")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/CALL")).isNull()
  }

  /** 1–2 位的保留路径、加密的 `#eu/` 联系人链接、群 / 贴纸链接、assetlinks：长度与形状规则就挡住了。 */
  @Test
  fun otherTellCcShapesAreNotUsernames() {
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/u#eu/AAAA")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/g#CjQKIA")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/s/#pack_id=1")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/i")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/.well-known/assetlinks.json")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/ab")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://tell.cc/1abc")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink("https://example.com/kaixin")).isNull()
    assertThat(TellomiLinks.parsePlainUsernameFromLink(null)).isNull()
  }

  @Test
  fun postLinksRoundTripAndOnlyTakeAUuid() {
    val id = "0f8e2c1a-3b4d-4e5f-8a9b-0c1d2e3f4a5b"
    assertThat(TellomiLinks.postLink(id.uppercase())).isEqualTo("https://tell.cc/m#$id")
    assertThat(TellomiLinks.parsePostId(TellomiLinks.postLink(id))).isEqualTo(id)
    assertThat(TellomiLinks.parsePostId("tellomi://tell.cc/m#$id")).isEqualTo(id)
    assertThat(TellomiLinks.parsePostId("https://tell.cc/m/#${id.uppercase()}")).isEqualTo(id)

    assertThat(TellomiLinks.parsePostId("https://tell.cc/m#not-a-uuid")).isNull()
    assertThat(TellomiLinks.parsePostId("https://tell.cc/mike#$id")).isNull()
    assertThat(TellomiLinks.parsePostId("https://example.com/m#$id")).isNull()
    assertThat(TellomiLinks.parsePostId("https://tell.cc/m#$id/extra")).isNull()
    assertThat(TellomiLinks.parsePostId(null)).isNull()
  }
}
