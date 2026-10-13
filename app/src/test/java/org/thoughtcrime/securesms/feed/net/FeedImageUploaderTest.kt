/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** tus 上传：分块、断点续传、不能续的错误直接抛。 */
class FeedImageUploaderTest {

  private lateinit var server: MockWebServer
  private lateinit var api: FeedApi

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    val tokens = object : FeedTokenSource {
      override suspend fun token(forceRefresh: Boolean) = "t"
    }
    api = FeedApi(baseUrl = server.url("/"), client = OkHttpClient(), tokens = tokens)
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun created() = MockResponse().setResponseCode(201).setHeader("Media-Key", "m/k1").setBody("""{"id":"u1","key":"m/k1"}""")
  private fun accepted(offset: Int) = MockResponse().setResponseCode(204).setHeader("Upload-Offset", offset.toString())

  @Test
  fun `uploads in chunks and returns the media key`() = runBlocking {
    server.enqueue(created())
    server.enqueue(accepted(4))
    server.enqueue(accepted(8))
    server.enqueue(accepted(10))

    val key = FeedImageUploader(api, chunkSize = 4).upload(ByteArray(10) { it.toByte() })

    assertThat(key).isEqualTo("m/k1")
    val start = server.takeRequest()
    assertThat(start.method).isEqualTo("POST")
    assertThat(start.path).isEqualTo("/v1/feed/media/uploads")
    assertThat(start.getHeader("Tus-Resumable")).isEqualTo("1.0.0")
    assertThat(start.getHeader("Upload-Length")).isEqualTo("10")
    val chunks = List(3) { server.takeRequest() }
    assertThat(chunks.map { it.method }).containsExactly("PATCH", "PATCH", "PATCH")
    assertThat(chunks.map { it.path }).containsExactly("/v1/feed/media/uploads/u1", "/v1/feed/media/uploads/u1", "/v1/feed/media/uploads/u1")
    assertThat(chunks.map { it.getHeader("Upload-Offset") }).containsExactly("0", "4", "8")
    assertThat(chunks.map { it.bodySize }).containsExactly(4L, 4L, 2L)
    assertThat(chunks.first().getHeader("Content-Type")).isEqualTo("application/offset+octet-stream")
  }

  @Test
  fun `offset mismatch asks the server and resumes from there`() = runBlocking {
    server.enqueue(created())
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"offset_mismatch"}"""))
    server.enqueue(MockResponse().setResponseCode(200).setHeader("Upload-Offset", "3"))
    server.enqueue(accepted(6))

    FeedImageUploader(api, chunkSize = 4).upload(ByteArray(6))

    server.takeRequest()
    server.takeRequest()
    assertThat(server.takeRequest().method).isEqualTo("HEAD")
    val resumed = server.takeRequest()
    assertThat(resumed.getHeader("Upload-Offset")).isEqualTo("3")
    assertThat(resumed.bodySize).isEqualTo(3L)
  }

  @Test
  fun `a rejected file is not retried`() {
    server.enqueue(created())
    server.enqueue(MockResponse().setResponseCode(415).setBody("""{"error":"unsupported_media"}"""))

    assertFailure { runBlocking { FeedImageUploader(api, chunkSize = 4).upload(ByteArray(3)) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::code).isEqualTo("unsupported_media")
    assertThat(server.requestCount).isEqualTo(2)
  }
}
