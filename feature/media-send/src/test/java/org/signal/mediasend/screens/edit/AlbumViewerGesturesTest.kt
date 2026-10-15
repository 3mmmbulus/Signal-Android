/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AlbumViewerGesturesTest {

  private val density = Density(density = 2f)
  private val page = IntSize(width = 1000, height = 2000)

  @Test
  fun `Given a swipe down, then it closes on 700 dp per second or 128 dp, whichever comes first`() {
    // 2x density: 700 dp/s = 1400 px/s, 128 dp = 256 px.
    assertThat(AlbumViewerGestures.shouldDismiss(offsetPx = 255f, velocityPxPerSecond = 1399f, density = density)).isFalse()
    assertThat(AlbumViewerGestures.shouldDismiss(offsetPx = 256f, velocityPxPerSecond = 0f, density = density)).isTrue()
    assertThat(AlbumViewerGestures.shouldDismiss(offsetPx = -256f, velocityPxPerSecond = 0f, density = density)).isTrue()
    assertThat(AlbumViewerGestures.shouldDismiss(offsetPx = 10f, velocityPxPerSecond = -1400f, density = density)).isTrue()
  }

  @Test
  fun `Given a swipe down, then the chrome fades with the distance and stays gone past the fade distance`() {
    assertThat(AlbumViewerGestures.dismissProgress(offsetPx = 0f, density = density)).isEqualTo(0f)
    assertThat(AlbumViewerGestures.dismissProgress(offsetPx = -256f, density = density)).isCloseTo(0.5f, 0.0001f)
    assertThat(AlbumViewerGestures.dismissProgress(offsetPx = 5000f, density = density)).isEqualTo(1f)
  }

  @Test
  fun `Given an unzoomed page, when it is double tapped, then it zooms to three times around the tap and the tapped point stays put`() {
    val tap = Offset(800f, 500f)

    val (scale, offset) = AlbumViewerGestures.doubleTapTarget(scale = 1f, tap = tap, size = page)

    assertThat(scale).isEqualTo(AlbumViewerGestures.MAX_SCALE)
    assertThat(screenPointOf(contentPoint = tap, scale = scale, offset = offset)).isEqualTo(tap)
  }

  @Test
  fun `Given a zoomed page, when it is double tapped, then it goes back to fitting the page`() {
    val (scale, offset) = AlbumViewerGestures.doubleTapTarget(scale = 2f, tap = Offset(10f, 10f), size = page)

    assertThat(scale).isEqualTo(1f)
    assertThat(offset).isEqualTo(Offset.Zero)
  }

  @Test
  fun `Given a pinch, then the point between the fingers stays put and the scale stays within one to three`() {
    val centroid = Offset(300f, 1200f)

    val (scale, offset) = AlbumViewerGestures.transform(scale = 1f, offset = Offset.Zero, zoom = 2f, pan = Offset.Zero, centroid = centroid, size = page)
    assertThat(scale).isEqualTo(2f)
    assertThat(screenPointOf(contentPoint = centroid, scale = scale, offset = offset)).isEqualTo(centroid)

    val (tooFar, _) = AlbumViewerGestures.transform(scale = 2f, offset = offset, zoom = 5f, pan = Offset.Zero, centroid = centroid, size = page)
    assertThat(tooFar).isEqualTo(AlbumViewerGestures.MAX_SCALE)

    val (backToFit, backOffset) = AlbumViewerGestures.transform(scale = 2f, offset = offset, zoom = 0.1f, pan = Offset.Zero, centroid = centroid, size = page)
    assertThat(backToFit).isEqualTo(1f)
    assertThat(backOffset).isEqualTo(Offset.Zero)
  }

  @Test
  fun `Given a zoomed page, when it is panned past an edge, then the picture stops at that edge`() {
    val (_, offset) = AlbumViewerGestures.transform(scale = 2f, offset = Offset.Zero, zoom = 1f, pan = Offset(5000f, -5000f), centroid = Offset(500f, 1000f), size = page)

    // At 2x the picture is one page wider and taller than the page, so it can move half of that each way.
    assertThat(offset).isEqualTo(Offset(500f, -1000f))
  }

  @Test
  fun `Only still pictures get the crop, draw and adjust shortcuts`() {
    assertThat(AlbumViewerGestures.isEditableImage("image/jpeg")).isTrue()
    assertThat(AlbumViewerGestures.isEditableImage("image/heic")).isTrue()
    assertThat(AlbumViewerGestures.isEditableImage("image/png")).isTrue()
    assertThat(AlbumViewerGestures.isEditableImage("image/gif")).isFalse()
    assertThat(AlbumViewerGestures.isEditableImage("video/mp4")).isFalse()
    assertThat(AlbumViewerGestures.isEditableImage(null)).isFalse()
  }

  /** 对齐 iOS `TellomiPhotoPickerAlbumViewer`：动图（`isAnimatedImage`）不给工具键；安卓只看得到类型，WebP 一律当可能会动。 */
  @Test
  fun `Pictures that may be animated get no shortcuts`() {
    assertThat(AlbumViewerGestures.isEditableImage("image/webp")).isFalse()
    assertThat(AlbumViewerGestures.isEditableImage("image/apng")).isFalse()
    assertThat(AlbumViewerGestures.isEditableImage("image/vnd.mozilla.apng")).isFalse()
  }

  /** Where a point of the unscaled page ends up on screen: `graphicsLayer` scales around the centre, then translates. */
  private fun screenPointOf(contentPoint: Offset, scale: Float, offset: Offset): Offset {
    val center = Offset(page.width / 2f, page.height / 2f)
    return center + (contentPoint - center) * scale + offset
  }
}
