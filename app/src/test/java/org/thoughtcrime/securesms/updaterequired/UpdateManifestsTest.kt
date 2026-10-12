/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1491）：更新清单怎么改只读状态（需求 §3.2.1「撤销」、§3.3「现状」），以及多久拉一次。
 */
class UpdateManifestsTest {

  companion object {
    private const val BUILD = 175301L
    private const val NOW = 1_760_000_000_000L
    private const val HOUR = 60L * 60 * 1000
  }

  private fun manifest(serial: Long, required: Long = 0, recommended: Long = 0) = UpdateManifest(
    serial = serial,
    latest = UpdateManifest.Latest(version = "0.1.5", build = 175401, url = null, size = null),
    recommendedMinBuild = recommended,
    requiredMinBuild = required,
    requiredReason = if (required > 0) UpdateManifest.RequiredReason.SECURITY else null,
    requiredFromMillis = null,
    notesUrl = null
  )

  private fun reconcile(manifest: UpdateManifest?, readOnly: Boolean, readOnlySince: Long, buildExpired: Boolean = false): UpdateManifests.Reconciled {
    return UpdateManifests.reconcile(manifest, BUILD, NOW, readOnly, buildExpired, readOnlySince)
  }

  @Test
  fun `a required manifest makes this version read-only and remembers which manifest did it`() {
    val result = reconcile(manifest(serial = 10, required = 175401), readOnly = false, readOnlySince = -1)

    assertThat(result).isEqualTo(UpdateManifests.Reconciled(readOnly = true, readOnlySinceSerial = 10, released = false))
  }

  @Test
  fun `a newer manifest that no longer requires the update releases the block`() {
    val result = reconcile(manifest(serial = 11), readOnly = true, readOnlySince = 10)

    assertThat(result).isEqualTo(UpdateManifests.Reconciled(readOnly = false, readOnlySinceSerial = -1, released = true))
  }

  @Test
  fun `the same manifest that was cached when the server blocked us does not release it`() {
    // 499 置了只读：第一次看到时补记当时的清单
    val first = reconcile(manifest(serial = 10), readOnly = true, readOnlySince = -1)
    assertThat(first).isEqualTo(UpdateManifests.Reconciled(readOnly = true, readOnlySinceSerial = 10, released = false))

    // 还是那份清单：不放行（服务端在拒，清单没说撤）
    val again = reconcile(manifest(serial = 10), readOnly = true, readOnlySince = 10)
    assertThat(again.released).isFalse()
    assertThat(again.readOnly).isTrue()
  }

  @Test
  fun `a server block with no manifest at all waits for the first manifest`() {
    val noManifest = reconcile(null, readOnly = true, readOnlySince = -1)
    assertThat(noManifest).isEqualTo(UpdateManifests.Reconciled(readOnly = true, readOnlySinceSerial = 0, released = false))

    val firstManifest = reconcile(manifest(serial = 1), readOnly = true, readOnlySince = 0)
    assertThat(firstManifest.released).isTrue()
  }

  @Test
  fun `a newer manifest that still requires the update keeps the block`() {
    val result = reconcile(manifest(serial = 12, required = 175401), readOnly = true, readOnlySince = 10)

    assertThat(result).isEqualTo(UpdateManifests.Reconciled(readOnly = true, readOnlySinceSerial = 10, released = false))
  }

  @Test
  fun `an expired build stays read-only whatever the manifest says`() {
    val result = reconcile(manifest(serial = 99), readOnly = true, readOnlySince = 10, buildExpired = true)

    assertThat(result.readOnly).isTrue()
    assertThat(result.released).isFalse()
  }

  @Test
  fun `recommended and optional tiers never touch the read-only state`() {
    assertThat(reconcile(manifest(serial = 5, recommended = 175401), readOnly = false, readOnlySince = -1))
      .isEqualTo(UpdateManifests.Reconciled(readOnly = false, readOnlySinceSerial = -1, released = false))
    assertThat(reconcile(manifest(serial = 5), readOnly = false, readOnlySince = 3))
      .isEqualTo(UpdateManifests.Reconciled(readOnly = false, readOnlySinceSerial = -1, released = false))
  }

  @Test
  fun `checks are throttled to six hours, except while read-only`() {
    assertThat(UpdateManifests.shouldCheck(NOW, lastCheckedMillis = NOW - 5 * HOUR, isReadOnly = false)).isFalse()
    assertThat(UpdateManifests.shouldCheck(NOW, lastCheckedMillis = NOW - 6 * HOUR, isReadOnly = false)).isTrue()
    assertThat(UpdateManifests.shouldCheck(NOW, lastCheckedMillis = NOW - 1, isReadOnly = true)).isTrue()
    assertThat(UpdateManifests.shouldCheck(NOW, lastCheckedMillis = 0, isReadOnly = false)).isTrue()
    // 时钟往回拨过：上次检查在「未来」，也要查
    assertThat(UpdateManifests.shouldCheck(NOW, lastCheckedMillis = NOW + HOUR, isReadOnly = false)).isTrue()
  }

  @Test
  fun `the manifest url follows the region's updates host`() {
    assertThat(UpdateManifests.manifestUrl("updates.tellomi.app")).isEqualTo("https://updates.tellomi.app/update/v1/android-website.json")
    assertThat(UpdateManifests.manifestUrl("updates.tellomi.cn")).isEqualTo("https://updates.tellomi.cn/update/v1/android-website.json")
  }

  @Test
  fun `the embedded public key is the release key every client trusts`() {
    // 与 clients/desktop/config/default.json 的 updatesPublicKey、超级仓库 update_manifest.py 用的同一把；换钥匙要三端一起换
    assertThat(org.signal.core.util.Hex.toStringCondensed(UpdateManifests.publicKey))
      .isEqualTo("0569dca6fde7f7dd8e03eee459d54b385b4067230e4c0452eb0c6fc3d2644f914c")
  }
}
