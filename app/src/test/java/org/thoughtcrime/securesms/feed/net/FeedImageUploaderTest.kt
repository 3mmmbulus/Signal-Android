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

  private fun failure(status: Int, code: String = "internal") = MockResponse().setResponseCode(status).setBody("""{"error":"$code"}""")

  @Test
  fun `a 500 on a chunk asks the server for its offset and sends the chunk again`() = runBlocking {
    server.enqueue(created())
    server.enqueue(accepted(4))
    server.enqueue(failure(500))
    server.enqueue(MockResponse().setResponseCode(200).setHeader("Upload-Offset", "4"))
    server.enqueue(accepted(8))
    server.enqueue(accepted(10))

    val key = FeedImageUploader(api, chunkSize = 4).upload(ByteArray(10) { it.toByte() })

    assertThat(key).isEqualTo("m/k1")
    val requests = List(6) { server.takeRequest() }
    assertThat(requests.map { it.method }).containsExactly("POST", "PATCH", "PATCH", "HEAD", "PATCH", "PATCH")
    assertThat(requests.map { it.getHeader("Upload-Offset") }).containsExactly(null, "0", "4", null, "4", "8")
    assertThat(requests[4].bodySize).isEqualTo(4L)
    assertThat(requests[5].bodySize).isEqualTo(2L)
  }

  @Test
  fun `a 5xx that moved the offset resumes from the offset the server reports`() = runBlocking {
    server.enqueue(created())
    server.enqueue(failure(503, "unavailable"))
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
  fun `500 502 503 and 504 are all resumable`() {
    for (status in listOf(500, 502, 503, 504)) {
      val local = MockWebServer().also { it.start() }
      try {
        val localApi = FeedApi(baseUrl = local.url("/"), client = OkHttpClient(), tokens = object : FeedTokenSource { override suspend fun token(forceRefresh: Boolean) = "t" })
        local.enqueue(created())
        local.enqueue(failure(status))
        local.enqueue(MockResponse().setResponseCode(200).setHeader("Upload-Offset", "0"))
        local.enqueue(accepted(3))

        val key = runBlocking { FeedImageUploader(localApi, chunkSize = 4).upload(ByteArray(3)) }

        assertThat(key, name = "status $status").isEqualTo("m/k1")
        assertThat(local.requestCount, name = "status $status").isEqualTo(4)
      } finally {
        local.shutdown()
      }
    }
  }

  @Test
  fun `a server that keeps answering 500 is given up on after maxResumes`() {
    server.enqueue(created())
    repeat(3) {
      server.enqueue(failure(500))
      server.enqueue(MockResponse().setResponseCode(200).setHeader("Upload-Offset", "0"))
    }
    server.enqueue(failure(500))

    assertFailure { runBlocking { FeedImageUploader(api, chunkSize = 4, maxResumes = 2).upload(ByteArray(3)) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::status).isEqualTo(500)
    // POST + 3 PATCH (the first and two re-sends) + 2 HEAD
    assertThat(server.requestCount).isEqualTo(6)
  }

  @Test
  fun `other client errors still fail at once without asking for an offset`() {
    for ((status, code) in listOf(400 to "bad_request", 404 to "not_found", 413 to "too_large", 429 to "too_many_uploads", 409 to "something_else")) {
      val local = MockWebServer().also { it.start() }
      try {
        val localApi = FeedApi(baseUrl = local.url("/"), client = OkHttpClient(), tokens = object : FeedTokenSource { override suspend fun token(forceRefresh: Boolean) = "t" })
        local.enqueue(created())
        local.enqueue(failure(status, code))

        assertFailure { runBlocking { FeedImageUploader(localApi, chunkSize = 4).upload(ByteArray(3)) } }
          .isInstanceOf(FeedApiException::class)
          .prop(FeedApiException::status).isEqualTo(status)
        assertThat(local.requestCount, name = "status $status").isEqualTo(2)
      } finally {
        local.shutdown()
      }
    }
  }

  @Test
  fun `5xx codes outside 500 502 503 504 are not resumed`() {
    for (status in listOf(501, 505)) {
      val local = MockWebServer().also { it.start() }
      try {
        val localApi = FeedApi(baseUrl = local.url("/"), client = OkHttpClient(), tokens = object : FeedTokenSource { override suspend fun token(forceRefresh: Boolean) = "t" })
        local.enqueue(created())
        local.enqueue(failure(status))

        assertFailure { runBlocking { FeedImageUploader(localApi, chunkSize = 4).upload(ByteArray(3)) } }
          .isInstanceOf(FeedApiException::class)
        assertThat(local.requestCount, name = "status $status").isEqualTo(2)
      } finally {
        local.shutdown()
      }
    }
  }
}
