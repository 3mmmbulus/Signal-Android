/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedCapabilities
import org.thoughtcrime.securesms.feed.net.FeedCapabilitiesResponse
import org.thoughtcrime.securesms.feed.net.FeedCapabilityChecker
import java.io.IOException

class FeedEntriesTest {

  private fun caps(vararg names: String) = FeedCapabilities(names.toSet())

  @Test
  fun `no capabilities hides every entry`() {
    assertThat(FeedEntries.from(FeedCapabilities.DISABLED)).isEqualTo(FeedEntries.NONE)
  }

  @Test
  fun `sample data shows everything without asking the engine`() {
    assertThat(FeedEntries.of(sampleData = true, capabilities = FeedCapabilities.DISABLED)).isEqualTo(FeedEntries.ALL)
    assertThat(FeedEntries.of(sampleData = false, capabilities = FeedCapabilities.DISABLED)).isEqualTo(FeedEntries.NONE)
  }

  @Test
  fun `each feature maps to its entry`() {
    val all = FeedEntries.from(
      caps(
        FeedCapabilities.FEED, FeedCapabilities.POSTING, FeedCapabilities.MEDIA_UPLOAD, FeedCapabilities.POLLS, FeedCapabilities.COMMENTS,
        FeedCapabilities.VOICE_COMMENTS, FeedCapabilities.REACTIONS, FeedCapabilities.REPORTS, FeedCapabilities.BLOCKS, FeedCapabilities.SEARCH,
        FeedCapabilities.NOTIFICATIONS, FeedCapabilities.FOLLOW, FeedCapabilities.EVENTS, FeedCapabilities.PROFILES
      )
    )
    assertThat(all).isEqualTo(FeedEntries.ALL)

    val some = FeedEntries.from(caps(FeedCapabilities.FEED, FeedCapabilities.REPORTS, FeedCapabilities.REACTIONS, FeedCapabilities.SEARCH))
    assertThat(some.feed).isTrue()
    assertThat(some.reactions).isTrue()
    assertThat(some.search).isTrue()
    assertThat(some.posting).isFalse()
    assertThat(some.comments).isFalse()
    assertThat(some.notifications).isFalse()
    assertThat(some.profiles).isFalse()
    assertThat(some.lists).isFalse()
  }

  @Test
  fun `nothing without feed itself`() {
    val noFeed = FeedEntries.from(caps(FeedCapabilities.POSTING, FeedCapabilities.REACTIONS, FeedCapabilities.SEARCH, FeedCapabilities.COMMENTS))
    assertThat(noFeed).isEqualTo(FeedEntries.NONE)
  }

  @Test
  fun `no reports backend hides the whole feed`() {
    val noReports = FeedEntries.from(caps(FeedCapabilities.FEED, FeedCapabilities.POSTING, FeedCapabilities.REACTIONS, FeedCapabilities.SEARCH))
    assertThat(noReports).isEqualTo(FeedEntries.NONE)
  }

  @Test
  fun `lists need follow and blocks, author page needs profiles`() {
    val base = arrayOf(FeedCapabilities.FEED, FeedCapabilities.REPORTS)
    assertThat(FeedEntries.from(caps(*base, FeedCapabilities.FOLLOW)).lists).isFalse()
    assertThat(FeedEntries.from(caps(*base, FeedCapabilities.BLOCKS)).lists).isFalse()
    assertThat(FeedEntries.from(caps(*base, FeedCapabilities.FOLLOW, FeedCapabilities.BLOCKS)).lists).isTrue()
    assertThat(FeedEntries.from(caps(*base)).profiles).isFalse()
    assertThat(FeedEntries.from(caps(*base, FeedCapabilities.PROFILES)).profiles).isTrue()
  }

  @Test
  fun `voice needs comments and media needs posting`() {
    val voiceOnly = FeedEntries.from(caps(FeedCapabilities.FEED, FeedCapabilities.REPORTS, FeedCapabilities.VOICE_COMMENTS, FeedCapabilities.MEDIA_UPLOAD))
    assertThat(voiceOnly.voiceComments).isFalse()
    assertThat(voiceOnly.mediaUpload).isFalse()
    val both = FeedEntries.from(caps(FeedCapabilities.FEED, FeedCapabilities.REPORTS, FeedCapabilities.COMMENTS, FeedCapabilities.VOICE_COMMENTS, FeedCapabilities.POSTING, FeedCapabilities.MEDIA_UPLOAD))
    assertThat(both.voiceComments).isTrue()
    assertThat(both.mediaUpload).isTrue()
  }

  @Test
  fun `tab needs feed deployed unless forced`() {
    assertThat(TellomiFeed.isTabEnabled(remoteEnabled = true, engineVisible = true, forcedByInternalUser = false, feedDeployed = false)).isFalse()
    assertThat(TellomiFeed.isTabEnabled(remoteEnabled = true, engineVisible = true, forcedByInternalUser = false, feedDeployed = true)).isTrue()
    assertThat(TellomiFeed.isTabEnabled(remoteEnabled = false, engineVisible = false, forcedByInternalUser = true, feedDeployed = false)).isTrue()
  }

  @Test
  fun `failed check hides entries`() = runBlocking {
    val checker = FeedCapabilityChecker(fetch = { throw IOException("offline") })
    assertThat(FeedEntries.from(checker.refreshIfStale())).isEqualTo(FeedEntries.NONE)
    val notFound = FeedCapabilityChecker(fetch = { FeedCapabilitiesResponse(404, """{"error":"not_found"}""") })
    assertThat(FeedEntries.from(notFound.refreshIfStale())).isEqualTo(FeedEntries.NONE)
  }
}

class FeedCapabilityFetchTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() = server.shutdown()

  @Test
  fun `asks v1 capabilities without credentials`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1,"features":["feed","reports","reactions"]}"""))
    val response = FeedCapabilityStore.fetch(server.url("/"), OkHttpClient())
    val request = server.takeRequest()
    assertThat(request.path).isEqualTo("/v1/capabilities")
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.getHeader("Authorization")).isNull()
    val entries = FeedEntries.from(FeedCapabilities.parse(response.status, response.body))
    assertThat(entries.feed).isTrue()
    assertThat(entries.reactions).isTrue()
    assertThat(entries.posting).isFalse()
  }

  @Test
  fun `engine under a path prefix keeps the prefix`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1,"features":[]}"""))
    FeedCapabilityStore.fetch(server.url("/engine/"), OkHttpClient())
    assertThat(server.takeRequest().path).isEqualTo("/engine/v1/capabilities")
  }

  @Test
  fun `non 200 is not deployed`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(503).setBody("""{"version":1,"features":["feed"]}"""))
    val response = FeedCapabilityStore.fetch(server.url("/"), OkHttpClient())
    assertThat(FeedEntries.from(FeedCapabilities.parse(response.status, response.body))).isEqualTo(FeedEntries.NONE)
  }

  @Test
  fun `oversized body is not deployed`() = runBlocking {
    val huge = """{"version":1,"features":["feed"],"pad":"""" + "x".repeat(FeedCapabilities.MAX_BODY_CHARS) + "\"}"
    server.enqueue(MockResponse().setResponseCode(200).setBody(huge))
    val response = FeedCapabilityStore.fetch(server.url("/"), OkHttpClient())
    assertThat(FeedEntries.from(FeedCapabilities.parse(response.status, response.body))).isEqualTo(FeedEntries.NONE)
  }
}
