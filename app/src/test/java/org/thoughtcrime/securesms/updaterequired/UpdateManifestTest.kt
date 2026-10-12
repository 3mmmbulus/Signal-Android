/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.Test
import org.signal.core.util.Hex
import org.signal.libsignal.protocol.ecc.ECKeyPair
import java.time.Instant

/**
 * Tellomi（tellomi/tellomi#1491）：更新清单的采信与判档（需求 app-update-and-version-policy.md §3.2.1）。
 */
class UpdateManifestTest {

  companion object {
    /**
     * 跨语言夹具 `updaterequired/android-website-fixture.json(.sig)`：超级仓库 `scripts/release/update_manifest.py` 组装、
     * `scripts/links/xeddsa.py` 签的，私钥是测试专用的 0x5c × 32（不是发布密钥）。这把是它的公钥。
     */
    private const val FIXTURE_PUBLIC_KEY = "05d3baf56cb00bfcbb41a459bd640f05a77ed6110809bf5f21be1a08e51dff7409"

    private const val DAY = 24L * 60 * 60 * 1000
  }

  private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/updaterequired/$name")!!.use { it.readBytes() }

  private val fixture: ByteArray get() = resource("android-website-fixture.json")
  private val fixtureSignature: String get() = String(resource("android-website-fixture.json.sig"))
  private val fixtureKey: ByteArray get() = Hex.fromStringCondensed(FIXTURE_PUBLIC_KEY)

  private fun accept(data: ByteArray, signature: String, key: ByteArray = fixtureKey, cachedSerial: Long = 0): UpdateManifest? {
    return UpdateManifest.accept(data, signature, key, "android", "website", cachedSerial)
  }

  /** 用 libsignal 现生成的钥匙签一份手写的清单。 */
  private fun signed(json: String): Triple<ByteArray, String, ByteArray> {
    val keyPair = ECKeyPair.generate()
    val data = json.toByteArray()
    return Triple(data, Hex.toStringCondensed(keyPair.privateKey.calculateSignature(data)), keyPair.publicKey.serialize())
  }

  private fun manifestJson(
    name: String = "tellomi-update",
    schema: Int = 1,
    platform: String = "android",
    channel: String = "website",
    serial: Long = 20261013120000,
    extra: String = ""
  ): String {
    return """{"name":"$name","schema":$schema,"platform":"$platform","channel":"$channel","serial":$serial,""" +
      """"latest":{"version":"0.1.5","build":175401,"releaseDate":"2026-10-13T12:00:00Z"},"recommendedMinBuild":0,"requiredMinBuild":0$extra}"""
  }

  @Test
  fun `a manifest signed by the release script is accepted with every field`() {
    val manifest = accept(fixture, fixtureSignature)

    assertThat(manifest).isNotNull()
    manifest!!
    assertThat(manifest.serial).isEqualTo(20261013120000)
    assertThat(manifest.latest.version).isEqualTo("0.1.5")
    assertThat(manifest.latest.build).isEqualTo(175401L)
    assertThat(manifest.latest.size).isEqualTo(123456789L)
    assertThat(manifest.latest.url).isEqualTo("https://updates.tellomi.app/android/Tellomi-website-prod-universal-release-0.1.5.apk")
    assertThat(manifest.recommendedMinBuild).isEqualTo(175301L)
    assertThat(manifest.requiredMinBuild).isEqualTo(175201L)
    assertThat(manifest.requiredReason).isEqualTo(UpdateManifest.RequiredReason.SECURITY)
    assertThat(manifest.requiredFromMillis).isEqualTo(Instant.parse("2026-10-20T00:00:00Z").toEpochMilli())
    assertThat(manifest.notesUrl).isEqualTo("https://www.tellomi.app/changelog/#android")
  }

  @Test
  fun `one changed byte is rejected`() {
    val tampered = fixture.copyOf()
    val index = String(tampered).indexOf("175401")
    tampered[index + 5] = '2'.code.toByte()

    assertThat(accept(tampered, fixtureSignature)).isNull()
  }

  @Test
  fun `a signature from another key is rejected, including the production key against the test fixture`() {
    assertThat(accept(fixture, fixtureSignature, key = UpdateManifests.publicKey)).isNull()
    assertThat(accept(fixture, fixtureSignature, key = ECKeyPair.generate().publicKey.serialize())).isNull()
  }

  @Test
  fun `a malformed signature is rejected instead of crashing`() {
    assertThat(accept(fixture, "not hex")).isNull()
    assertThat(accept(fixture, "abcd")).isNull()
    assertThat(accept(fixture, "")).isNull()
  }

  @Test
  fun `a manifest for another platform, channel, name or schema is rejected`() {
    listOf(
      manifestJson(platform = "ios"),
      manifestJson(channel = "play"),
      manifestJson(name = "links"),
      manifestJson(schema = 2)
    ).forEach { json ->
      val (data, signature, key) = signed(json)
      assertThat(accept(data, signature, key)).isNull()
    }
  }

  @Test
  fun `an older serial than the cached one is rejected, the same serial is accepted`() {
    val (data, signature, key) = signed(manifestJson(serial = 100))

    assertThat(accept(data, signature, key, cachedSerial = 101)).isNull()
    assertThat(accept(data, signature, key, cachedSerial = 100)).isNotNull()
    assertThat(accept(data, signature, key, cachedSerial = 0)).isNotNull()
  }

  @Test
  fun `unknown fields are ignored, missing required fields are rejected`() {
    val (data, signature, key) = signed(manifestJson(extra = ""","futureField":{"a":1}"""))
    assertThat(accept(data, signature, key)).isNotNull()

    val noLatest = """{"name":"tellomi-update","schema":1,"platform":"android","channel":"website","serial":5}"""
    val (data2, signature2, key2) = signed(noLatest)
    assertThat(accept(data2, signature2, key2)).isNull()
  }

  @Test
  fun `an unknown reason is treated as other, and no reason is kept below the required tier`() {
    val (data, signature, key) = signed(manifestJson(extra = ""","requiredMinBuild":175401,"requiredReason":"brand-new"""").replace(""""requiredMinBuild":0,""", ""))
    assertThat(accept(data, signature, key)!!.requiredReason).isEqualTo(UpdateManifest.RequiredReason.OTHER)

    val (data2, signature2, key2) = signed(manifestJson(extra = ""","requiredReason":"security""""))
    assertThat(accept(data2, signature2, key2)!!.requiredReason).isNull()
  }

  @Test
  fun `tiers follow the spec order`() {
    val now = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
    fun manifest(recommended: Long = 0, required: Long = 0, requiredFrom: Long? = null) = UpdateManifest(
      serial = 1,
      latest = UpdateManifest.Latest(version = "0.1.5", build = 175401, url = null, size = null),
      recommendedMinBuild = recommended,
      requiredMinBuild = required,
      requiredReason = if (required > 0) UpdateManifest.RequiredReason.SECURITY else null,
      requiredFromMillis = requiredFrom,
      notesUrl = null
    )

    // 必须：没有生效时间 = 立即；生效时间已到
    assertThat(manifest(required = 175301).tierFor(175201, now)).isEqualTo(UpdateManifest.Tier.REQUIRED)
    assertThat(manifest(required = 175301, requiredFrom = now - DAY).tierFor(175201, now)).isEqualTo(UpdateManifest.Tier.REQUIRED)
    // 必须但还没到生效时间 → 先推荐（需求 3.1「先推荐、默认 7 天后转必须」）
    assertThat(manifest(required = 175301, requiredFrom = now + DAY).tierFor(175201, now)).isEqualTo(UpdateManifest.Tier.RECOMMENDED)
    // 推荐
    assertThat(manifest(recommended = 175301).tierFor(175201, now)).isEqualTo(UpdateManifest.Tier.RECOMMENDED)
    // 门槛都够了、只是有更新的版本 → 可选
    assertThat(manifest(recommended = 175301, required = 175201).tierFor(175301, now)).isEqualTo(UpdateManifest.Tier.OPTIONAL)
    // 已是最新
    assertThat(manifest().tierFor(175401, now)).isEqualTo(UpdateManifest.Tier.NONE)
    // 构建号等于门槛不算低于门槛
    assertThat(manifest(required = 175301).tierFor(175301, now)).isEqualTo(UpdateManifest.Tier.OPTIONAL)
  }
}
