/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

/** tellomi/tellomi#1263 P-4: which photos are Motion Photos, and where their video starts. */
class TellomiMotionPhotoTest {

  @Test
  fun `motion photo v1 container, attributes`() {
    val xmp = """
      <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
      <rdf:Description GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="1500000">
      <Container:Directory><rdf:Seq>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="2345678" Item:Padding="0"/></rdf:li>
      </rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>
    """.trimIndent()
    assertThat(TellomiMotionPhoto.videoLength(xmp)).isEqualTo(2_345_678L)
    assertThat(TellomiMotionPhoto.videoRange(xmp, fileLength = 5_000_000L)).isEqualTo(TellomiMotionPhoto.VideoRange(2_654_322L, 2_345_678L))
  }

  @Test
  fun `motion photo v1 container, elements`() {
    val xmp = """
      <rdf:Description><GCamera:MotionPhoto>1</GCamera:MotionPhoto>
      <Container:Directory><rdf:Seq>
        <rdf:li><Container:Item><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>Primary</Item:Semantic></Container:Item></rdf:li>
        <rdf:li><Container:Item><Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>1000</Item:Length></Container:Item></rdf:li>
      </rdf:Seq></Container:Directory></rdf:Description>
    """.trimIndent()
    assertThat(TellomiMotionPhoto.videoLength(xmp)).isEqualTo(1_000L)
  }

  @Test
  fun `motion photo flag without the G prefix`() {
    val xmp = """<rdf:Description Camera:MotionPhoto="1"><Container:Directory><rdf:Seq>
      <rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>
      <rdf:li><Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="777"/></rdf:li>
      </rdf:Seq></Container:Directory></rdf:Description>"""
    assertThat(TellomiMotionPhoto.videoLength(xmp)).isEqualTo(777L)
  }

  @Test
  fun `only JPEG and HEIC photos can be motion photos`() {
    assertThat(TellomiMotionPhoto.canBeMotionPhoto("image/jpeg")).isEqualTo(true)
    assertThat(TellomiMotionPhoto.canBeMotionPhoto("image/heic")).isEqualTo(true)
    assertThat(TellomiMotionPhoto.canBeMotionPhoto("IMAGE/HEIF")).isEqualTo(true)
    assertThat(TellomiMotionPhoto.canBeMotionPhoto("image/png")).isEqualTo(false)
    assertThat(TellomiMotionPhoto.canBeMotionPhoto("video/mp4")).isEqualTo(false)
    assertThat(TellomiMotionPhoto.canBeMotionPhoto(null)).isEqualTo(false)
  }

  @Test
  fun `legacy micro video offset`() {
    val xmp = """<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="4096" GCamera:MicroVideoPresentationTimestampUs="0"/>"""
    assertThat(TellomiMotionPhoto.videoRange(xmp, fileLength = 10_000L)).isEqualTo(TellomiMotionPhoto.VideoRange(5_904L, 4_096L))
  }

  @Test
  fun `ordinary photos and nonsense are not motion photos`() {
    assertThat(TellomiMotionPhoto.videoLength(null)).isNull()
    assertThat(TellomiMotionPhoto.videoLength("")).isNull()
    assertThat(TellomiMotionPhoto.videoLength("""<rdf:Description xmp:CreatorTool="Camera"/>""")).isNull()
    assertThat(TellomiMotionPhoto.videoLength("""<rdf:Description GCamera:MotionPhoto="0" Item:Semantic="MotionPhoto" Item:Length="10"/>""")).isNull()
    assertThat(TellomiMotionPhoto.videoLength("""<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="-5"/>""")).isNull()
    // The video can't be the whole file or longer.
    val xmp = """<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="100"/>"""
    assertThat(TellomiMotionPhoto.videoRange(xmp, fileLength = 100L)).isNull()
    assertThat(TellomiMotionPhoto.videoRange(xmp, fileLength = 0L)).isNull()
  }
}
