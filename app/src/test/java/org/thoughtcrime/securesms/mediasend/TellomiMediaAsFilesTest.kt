/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1263 P-5「以文件发送」，ADR-0068）：选好的照片 / 视频变成按文件发的样子——原文件、原文件名，
 * 编辑过的照片用编辑后的图（文件名沿用、后缀 .jpg），视频的剪辑不带，没有文件名的按时间起名；HEIC 转不了就原样发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiMediaAsFilesTest {

  /** PartAuthority 认本机的 uri（相机刚拍的、编辑出来的）要用到。 */
  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }

    private const val TAKEN_AT = 1_791_000_000_000L
  }

  private val context: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun `an untouched photo is the original file with its name, sent as a file`() {
    val photo = media("content://media/external/images/1", "image/jpeg", fileName = "beach.jpg")

    val prepared = TellomiMediaAsFiles.prepare(context, listOf(photo), mapOf(photo to photo), convertHeicToJpeg = false).single()

    assertThat(prepared.uri).isEqualTo(photo.uri)
    assertThat(prepared.contentType).isEqualTo("image/jpeg")
    assertThat(prepared.fileName).isEqualTo("beach.jpg")
    assertThat(prepared.size).isEqualTo(photo.size)
    assertThat(prepared.transformProperties).isEqualTo(TransformProperties.forTellomiAsFile())
  }

  @Test
  fun `an edited photo is the edited picture, keeping the original name as a jpg`() {
    val photo = media("content://media/external/images/2", "image/heic", fileName = "IMG_0001.HEIC")
    val edited = media("content://blob/edited", "image/jpeg", fileName = null, width = 640, height = 480, size = 4096)

    val prepared = TellomiMediaAsFiles.prepare(context, listOf(photo), mapOf(photo to edited), convertHeicToJpeg = false).single()

    assertThat(prepared.uri).isEqualTo(edited.uri)
    assertThat(prepared.contentType).isEqualTo("image/jpeg")
    assertThat(prepared.fileName).isEqualTo("IMG_0001.jpg")
    assertThat(prepared.size).isEqualTo(4096L)
    assertThat(prepared.width).isEqualTo(640)
    assertThat(prepared.transformProperties!!.tellomiAsFile).isTrue()
  }

  @Test
  fun `a trimmed video is the original video, without the trim`() {
    val video = media("content://media/external/video/3", "video/mp4", fileName = "clip.mp4")
    val trimmed = video.copy(transformProperties = TransformProperties.forVideoTrim(0, 1_000_000))

    val prepared = TellomiMediaAsFiles.prepare(context, listOf(video), mapOf(video to trimmed), convertHeicToJpeg = false).single()

    assertThat(prepared.uri).isEqualTo(video.uri)
    assertThat(prepared.transformProperties).isEqualTo(TransformProperties.forTellomiAsFile())
  }

  @Test
  fun `a capture without a name is named after the time it was taken`() {
    val photo = media("content://media/external/images/4", "image/jpeg", fileName = null)
    val video = media("content://media/external/video/5", "video/mp4", fileName = null)

    val prepared = TellomiMediaAsFiles.prepare(context, listOf(photo, video), emptyMap(), convertHeicToJpeg = false)

    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(TAKEN_AT))
    assertThat(prepared.map { it.fileName }).containsExactly("IMG_$time.jpg", "VID_$time.mp4")
  }

  @Test
  fun `a HEIC that cannot be read for converting is sent as it is`() {
    val heic = media("content://media/external/images/6", "image/heic", fileName = "IMG_0002.HEIC")

    val prepared = TellomiMediaAsFiles.prepare(context, listOf(heic), mapOf(heic to heic), convertHeicToJpeg = true).single()

    assertThat(prepared.uri).isEqualTo(heic.uri)
    assertThat(prepared.contentType).isEqualTo("image/heic")
    assertThat(prepared.fileName).isEqualTo("IMG_0002.HEIC")
    assertThat(prepared.transformProperties!!.tellomiAsFile).isTrue()
  }

  @Test
  fun `the order stays the order they were picked in`() {
    val first = media("content://media/external/images/7", "image/jpeg", fileName = "1.jpg")
    val second = media("content://media/external/video/8", "video/mp4", fileName = "2.mp4")
    val third = media("content://media/external/images/9", "image/png", fileName = "3.png")

    val prepared = TellomiMediaAsFiles.prepare(context, listOf(first, second, third), emptyMap(), convertHeicToJpeg = false)

    assertThat(prepared.map { it.uri }).containsExactly(first.uri, second.uri, third.uri)
  }

  @Test
  fun `the extension is swapped, or added when there is none`() {
    assertThat(TellomiMediaAsFiles.withExtension("IMG_0001.HEIC", "jpg")).isEqualTo("IMG_0001.jpg")
    assertThat(TellomiMediaAsFiles.withExtension("holiday.photo.heif", "jpg")).isEqualTo("holiday.photo.jpg")
    assertThat(TellomiMediaAsFiles.withExtension("photo", "jpg")).isEqualTo("photo.jpg")
    assertThat(TellomiMediaAsFiles.withExtension(".hidden", "jpg")).isEqualTo(".hidden.jpg")
  }

  private fun media(
    uri: String,
    contentType: String,
    fileName: String?,
    width: Int = 4032,
    height: Int = 3024,
    size: Long = 2_000_000
  ): Media {
    val parsed: Uri = uri.toUri()
    return Media(
      uri = parsed,
      contentType = contentType,
      date = TAKEN_AT,
      width = width,
      height = height,
      size = size,
      duration = 0,
      isBorderless = false,
      isVideoGif = false,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      caption = null,
      transformProperties = null,
      fileName = fileName
    )
  }
}
