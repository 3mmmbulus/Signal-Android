/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v2

import android.app.Application
import androidx.core.net.toUri
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties

/**
 * Tellomi（tellomi/tellomi#1263 P-5，ADR-0068 §4.4）：罩住的那几张带进附件——编辑过的换成了渲染后的新 URI 也跟着；只有图片 / 视频罩；
 * 标记是 [TransformProperties.tellomiSpoiler]，别的属性不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiMediaSpoilersTest {

  @Test
  fun `covered originals map to what is actually sent, and only images and videos count`() {
    val photo = media("content://media/1", "image/jpeg")
    val edited = media("content://blob/edited-1", "image/jpeg")
    val video = media("content://media/2", "video/mp4")
    val document = media("content://media/3", "application/pdf")
    val plain = media("content://media/4", "image/jpeg")
    val oldToNew = mapOf(photo to edited, video to video, document to document, plain to plain)

    val rendered = TellomiMediaSpoilers.renderedUris(listOf(photo, video, document, plain), oldToNew, setOf(photo.uri, video.uri, document.uri))

    assertThat(rendered).containsExactlyInAnyOrder(edited.uri, video.uri)
    assertThat(TellomiMediaSpoilers.renderedUris(listOf(photo), oldToNew, emptySet())).isEmpty()
  }

  @Test
  fun `marking keeps the other transform properties and leaves the rest alone`() {
    val trimmed = media("content://media/2", "video/mp4").copy(transformProperties = TransformProperties.forVideoTrim(0, 3_000_000))
    val marked = TellomiMediaSpoilers.mark(trimmed, setOf(trimmed.uri))

    assertThat(marked.transformProperties?.tellomiSpoiler).isEqualTo(true)
    assertThat(marked.transformProperties?.tellomiSpoilerRevealed).isEqualTo(false)
    assertThat(marked.transformProperties?.videoTrim).isEqualTo(true)
    assertThat(marked.transformProperties?.videoTrimEndTimeUs).isEqualTo(3_000_000L)

    val photo = media("content://media/1", "image/jpeg")
    val markedPhoto = TellomiMediaSpoilers.mark(photo, setOf(photo.uri))
    assertThat(markedPhoto.transformProperties?.tellomiSpoiler == true).isTrue()
    assertThat(markedPhoto.transformProperties?.isTellomiSpoilerCovered == true).isTrue()

    val other = media("content://media/9", "image/jpeg")
    assertThat(TellomiMediaSpoilers.mark(other, setOf(photo.uri))).isSameInstanceAs(other)
    assertThat(other.transformProperties).isNull()
    assertThat(other.transformProperties?.tellomiSpoiler == true).isFalse()
  }

  private fun media(uri: String, contentType: String) = Media(
    uri = uri.toUri(),
    contentType = contentType,
    date = 0,
    width = 100,
    height = 100,
    size = 1024,
    duration = 0,
    isBorderless = false,
    isVideoGif = false,
    bucketId = Media.ALL_MEDIA_BUCKET_ID,
    caption = null,
    transformProperties = null,
    fileName = null
  )
}
