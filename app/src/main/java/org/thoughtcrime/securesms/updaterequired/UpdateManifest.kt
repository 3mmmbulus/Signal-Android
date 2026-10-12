/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.signal.core.util.Hex
import org.signal.core.util.JsonUtils
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.time.Instant

/**
 * Tellomi（tellomi/tellomi#1491）：更新清单——三档更新（可选 / 推荐 / 必须）的唯一来源。
 *
 * 格式、签名和判档规则见需求 `docs/product/specs/app-update-and-version-policy.md` §3.2.1；
 * 发布方是超级仓库的 `scripts/release/update_manifest.py`（`update/v1/android-website.json` + `.sig`）。
 */
data class UpdateManifest(
  val serial: Long,
  val latest: Latest,
  val recommendedMinBuild: Long,
  val requiredMinBuild: Long,
  val requiredReason: RequiredReason?,
  /** 必须档生效时间；null = 立即。 */
  val requiredFromMillis: Long?,
  /** 「了解更新内容」：官网更新日志。 */
  val notesUrl: String?
) {

  data class Latest(
    val version: String,
    /** 与 User-Agent 的 `Build/<n>` 同一个数：安卓是 versionCode。 */
    val build: Long,
    val url: String?,
    val size: Long?
  )

  enum class RequiredReason {
    SECURITY,
    PROTOCOL,
    OTHER
  }

  enum class Tier {
    NONE,
    OPTIONAL,
    RECOMMENDED,
    REQUIRED
  }

  /**
   * 判档（§3.2.1）：
   * 1. requiredMinBuild > build，且没有 requiredFrom 或已到 → 必须；
   * 2. 否则 requiredMinBuild > build（还没到生效时间）或 recommendedMinBuild > build → 推荐；
   * 3. 否则 latest.build > build → 可选；
   * 4. 否则不提示。
   */
  fun tierFor(build: Long, nowMillis: Long): Tier {
    return when {
      requiredMinBuild > build && (requiredFromMillis == null || nowMillis >= requiredFromMillis) -> Tier.REQUIRED
      requiredMinBuild > build || recommendedMinBuild > build -> Tier.RECOMMENDED
      latest.build > build -> Tier.OPTIONAL
      else -> Tier.NONE
    }
  }

  companion object {
    private val TAG = Log.tag(UpdateManifest::class)

    const val NAME = "tellomi-update"
    const val SCHEMA = 1

    /**
     * 客户端采信规则（§3.2.1），任何一条不过就返回 null（调用方照用缓存，不拦、不提示）：
     * 签名对 → name / schema 对 → platform / channel 是自己 → serial ≥ 缓存里的。
     *
     * @param publicKey 33 字节（0x05 前缀）的发布公钥
     * @param cachedSerial 上一份采信过的清单的 serial；还没有时传 0
     */
    @JvmStatic
    fun accept(
      data: ByteArray,
      signatureHex: String,
      publicKey: ByteArray,
      platform: String,
      channel: String,
      cachedSerial: Long
    ): UpdateManifest? {
      if (!verify(data, signatureHex, publicKey)) {
        Log.w(TAG, "Rejected an update manifest: bad signature.")
        return null
      }

      val raw = try {
        JsonUtils.fromJson(data, RawManifest::class.java)
      } catch (e: Exception) {
        Log.w(TAG, "Rejected an update manifest: unreadable.", e)
        return null
      }

      if (raw.name != NAME || raw.schema != SCHEMA || raw.platform != platform || raw.channel != channel) {
        Log.w(TAG, "Rejected an update manifest: name=${raw.name} schema=${raw.schema} target=${raw.platform}-${raw.channel}.")
        return null
      }

      val manifest = raw.toManifest() ?: run {
        Log.w(TAG, "Rejected an update manifest: missing or malformed fields.")
        return null
      }

      if (manifest.serial < cachedSerial) {
        Log.w(TAG, "Rejected an update manifest: serial ${manifest.serial} is older than the cached $cachedSerial.")
        return null
      }

      return manifest
    }

    /** 只在清单已经采信过（缓存）时用：不再验签，按同一套字段规则读出来。 */
    @JvmStatic
    fun parseTrusted(data: ByteArray): UpdateManifest? {
      return try {
        JsonUtils.fromJson(data, RawManifest::class.java).toManifest()
      } catch (e: Exception) {
        Log.w(TAG, "Cached update manifest is unreadable.", e)
        null
      }
    }

    private fun verify(data: ByteArray, signatureHex: String, publicKey: ByteArray): Boolean {
      return try {
        val signature = Hex.fromStringCondensed(signatureHex.trim())
        signature.size == 64 && ECPublicKey(publicKey).verifySignature(data, signature)
      } catch (e: Exception) {
        false
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private data class RawManifest(
    @JsonProperty val name: String? = null,
    @JsonProperty val schema: Int? = null,
    @JsonProperty val platform: String? = null,
    @JsonProperty val channel: String? = null,
    @JsonProperty val serial: Long? = null,
    @JsonProperty val latest: RawLatest? = null,
    @JsonProperty val recommendedMinBuild: Long? = null,
    @JsonProperty val requiredMinBuild: Long? = null,
    @JsonProperty val requiredReason: String? = null,
    @JsonProperty val requiredFrom: String? = null,
    @JsonProperty val notesUrl: String? = null
  ) {
    fun toManifest(): UpdateManifest? {
      val latest = latest ?: return null
      val version = latest.version ?: return null
      val build = latest.build?.takeIf { it > 0 } ?: return null
      val serial = serial?.takeIf { it > 0 } ?: return null
      val required = requiredMinBuild ?: 0
      val reason = when (requiredReason) {
        "security" -> RequiredReason.SECURITY
        "protocol" -> RequiredReason.PROTOCOL
        "other" -> RequiredReason.OTHER
        null -> null
        else -> RequiredReason.OTHER
      }
      val requiredFromMillis = requiredFrom?.let { from ->
        try {
          Instant.parse(from).toEpochMilli()
        } catch (e: Exception) {
          // 时间读不懂就当已经生效：宁可按发布方的意思拦，也不因为格式问题放过一个「必须」
          0L
        }
      }

      return UpdateManifest(
        serial = serial,
        latest = Latest(version = version, build = build, url = latest.url, size = latest.size),
        recommendedMinBuild = recommendedMinBuild ?: 0,
        requiredMinBuild = required,
        requiredReason = if (required > 0) reason ?: RequiredReason.OTHER else null,
        requiredFromMillis = if (required > 0) requiredFromMillis else null,
        notesUrl = notesUrl?.takeIf { it.startsWith("https://") }
      )
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private data class RawLatest(
    @JsonProperty val version: String? = null,
    @JsonProperty val build: Long? = null,
    @JsonProperty val url: String? = null,
    @JsonProperty val size: Long? = null
  )
}
