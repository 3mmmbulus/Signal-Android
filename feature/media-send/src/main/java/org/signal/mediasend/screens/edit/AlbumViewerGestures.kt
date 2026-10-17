/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.signal.core.util.ContentTypeUtil
import kotlin.math.abs

/**
 * 相册查看器（tellomi/tellomi#1518 V-2）的下滑关闭与缩放。门槛与 iOS 查看器（Signal-iOS#160）一致：速度 ≥ 700 或位移 ≥ 128 即关，
 * 否则回弹；双击放大到 3 倍、再双击还原，照 Telegram 安卓 `PhotoViewer.onDoubleTap`。
 */
internal object AlbumViewerGestures {

  const val MAX_SCALE = 3f

  private val dismissVelocity = 700.dp
  private val dismissDistance = 128.dp
  private val fadeDistance = 256.dp

  fun shouldDismiss(offsetPx: Float, velocityPxPerSecond: Float, density: Density): Boolean = with(density) {
    abs(velocityPxPerSecond) >= dismissVelocity.toPx() || abs(offsetPx) >= dismissDistance.toPx()
  }

  /** 拖得越远，背景与按钮越淡：0 = 没拖，1 = 拖满 [fadeDistance]。 */
  fun dismissProgress(offsetPx: Float, density: Density): Float = with(density) {
    (abs(offsetPx) / fadeDistance.toPx()).coerceAtMost(1f)
  }

  /**
   * 缩放 [zoom] 倍、平移 [pan] 之后的倍数与位移（`graphicsLayer` 的 scale / translation，变换中心在页面中心）。
   * [centroid] 下面的那一点不动；倍数限在 1–[MAX_SCALE]；位移不让图边离开页面；回到 1 倍时位移清零。
   */
  fun transform(scale: Float, offset: Offset, zoom: Float, pan: Offset, centroid: Offset, size: IntSize): Pair<Float, Offset> {
    val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
    if (newScale == 1f) {
      return 1f to Offset.Zero
    }
    val center = Offset(size.width / 2f, size.height / 2f)
    val effectiveZoom = newScale / scale
    val moved = (centroid - center) * (1 - effectiveZoom) + offset * effectiveZoom + pan
    return newScale to clamp(moved, newScale, size)
  }

  /** 双击：没放大就围着点击处放大到 [MAX_SCALE]，放大了就还原。 */
  fun doubleTapTarget(scale: Float, tap: Offset, size: IntSize): Pair<Float, Offset> {
    return if (scale > 1f) 1f to Offset.Zero else transform(1f, Offset.Zero, MAX_SCALE, Offset.Zero, tap, size)
  }

  /**
   * 底部「裁剪」「绘制」「调整」只给静态图：视频、动图不走图片编辑器（GIF 上游本来就不给编辑；动态 WebP 进了编辑器只剩第一帧，
   * 发出去就不动了）。
   * 对齐 iOS `TellomiPhotoPickerAlbumViewer`：视频或动图（`item.isVideo || item.isAnimatedImage`）不显示工具键。
   *
   * iOS 的「动图」来自相册自带的标记（`PHAsset.playbackStyle == .imageAnimated`），安卓这边拿不到对应的东西：
   * [org.signal.core.models.media.Media] 没有动画标记，MediaStore 也不记一张 WebP 动不动，要知道就得在这里读文件头，
   * 查看器翻一页读一次文件，不值。所以只按内容类型判断，能当场知道的都算上：
   * - GIF：一律当动图（和以前一样）；
   * - WebP：静态、动态同一个类型（`image/webp`），一律当可能会动，宁可静态 WebP 少了快捷键（选上以后在编辑页照样能裁剪、
   *   绘制、调整），也不让动态 WebP 被编辑器压成一张静图；
   * - APNG：类型明说是 APNG（`image/apng`、`image/vnd.mozilla.apng`）的当动图；相册里的 APNG 多半报成 `image/png`，
   *   和普通 PNG（截图等）分不开，这种照普通图片显示工具键。
   */
  /** Tellomi（#1714）：能设计时的：照片、视频（一次性查看只给可视媒体）。 */
  fun isPhotoOrVideo(contentType: String?): Boolean = ContentTypeUtil.isImageType(contentType) || ContentTypeUtil.isVideo(contentType)

  fun isVideo(contentType: String?): Boolean = ContentTypeUtil.isVideo(contentType)

  fun isEditableImage(contentType: String?): Boolean {
    return ContentTypeUtil.isImageType(contentType) && !isPossiblyAnimatedImage(contentType)
  }

  private val animatedPngContentTypes = setOf("image/apng", "image/vnd.mozilla.apng")

  private fun isPossiblyAnimatedImage(contentType: String?): Boolean {
    return ContentTypeUtil.isGif(contentType) ||
      ContentTypeUtil.isWebpType(contentType) ||
      contentType?.trim() in animatedPngContentTypes
  }

  private fun clamp(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = (scale - 1) * size.width / 2f
    val maxY = (scale - 1) * size.height / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
  }
}
