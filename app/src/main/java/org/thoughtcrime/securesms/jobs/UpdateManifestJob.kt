/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.updaterequired.UpdateManifests
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi#1491）：拉更新清单 `update/v1/android-website.json` 和它的签名，交给 [UpdateManifests.onFetched] 采信、调只读状态。
 *
 * 和 [ApkUpdateJob] 一样用一个干净的 OkHttpClient：不经过服务端连接上的那串拦截器——尤其是只读时伪造 499 的
 * `DeprecatedClientPreventionInterceptor`，否则只读状态下永远拉不到撤销。
 * 清单还没发布（404）不算失败：照用缓存（没有缓存就什么都不做）。
 */
class UpdateManifestJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    const val KEY = "TellomiUpdateManifestJob"
    private val TAG = Log.tag(UpdateManifestJob::class)
    private const val MAX_BYTES = 64L * 1024
  }

  constructor() : this(
    Parameters.Builder()
      .setQueue(KEY)
      .setMaxInstancesForFactory(1)
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(2)
      .setLifespan(TimeUnit.HOURS.toMillis(6))
      .build()
  )

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  @Throws(IOException::class)
  override fun onRun() {
    if (!UpdateManifests.isEnabled) {
      Log.w(TAG, "Not an app-updating build. Skipping.")
      return
    }

    val url = UpdateManifests.manifestUrl()
    val client = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()

    val data = fetch(client, url) ?: return
    val signature = fetch(client, "$url.sig") ?: return

    val accepted = UpdateManifests.onFetched(data, String(signature, Charsets.UTF_8))
    Log.i(TAG, "Update manifest fetched. Accepted: $accepted")
  }

  /** 404 返回 null（还没发布）；其它失败抛 IOException，由 [onShouldRetry] 决定要不要重试。 */
  private fun fetch(client: OkHttpClient, url: String): ByteArray? {
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      if (response.code == 404) {
        Log.i(TAG, "No update manifest published yet (404).")
        return null
      }
      if (!response.isSuccessful) {
        throw IOException("Update manifest request failed: ${response.code}")
      }
      val body = response.body ?: throw IOException("Empty update manifest response")
      // 清单只有几百字节；给个上限，读到上限还没完就不要了（不先整个读进内存）
      val source = body.source()
      if (source.request(MAX_BYTES + 1)) {
        throw IOException("Update manifest is larger than $MAX_BYTES bytes")
      }
      return source.readByteArray()
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = e is IOException

  override fun onFailure() {
    Log.w(TAG, "Couldn't fetch the update manifest. Keeping the cached one.")
  }

  class Factory : Job.Factory<UpdateManifestJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): UpdateManifestJob {
      return UpdateManifestJob(parameters)
    }
  }
}
