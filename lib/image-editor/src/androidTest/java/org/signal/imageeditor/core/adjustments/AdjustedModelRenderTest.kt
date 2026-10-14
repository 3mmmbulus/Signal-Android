/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.imageeditor.core.RendererContext
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.model.ImageAdjustments
import org.signal.imageeditor.core.renderers.UriGlideRenderer
import org.thoughtcrime.securesms.mms.RegisterGlideComponents
import org.thoughtcrime.securesms.mms.SignalGlideModule
import java.io.File

/**
 * Tellomi（tellomi/tellomi#1592 E5-2）：发出时那条路——模型阻塞渲染（`ImageEditorModelRenderMediaTransform` 调的就是它）——
 * 把调色烘焙进去；调回默认后和没调过一样。
 */
@RunWith(AndroidJUnit4::class)
class AdjustedModelRenderTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val typefaceProvider = RendererContext.TypefaceProvider { _, _, _ -> Typeface.DEFAULT }

  /** 应用启动时会给 Glide 装上 Signal 自己的加载器；这里只读一张普通文件，Glide 自带的就够了。 */
  @Before
  fun setUp() {
    SignalGlideModule.registerGlideComponents = object : RegisterGlideComponents {
      override fun registerComponents(context: Context, glide: Glide, registry: Registry) = Unit
    }
  }

  @Test
  fun renderingTheModelBakesTheAdjustments() {
    val file = File(context.cacheDir, "tellomi-e52-gray.png")
    file.outputStream().use { Bitmap.createBitmap(IntArray(400 * 400) { GRAY }, 400, 400, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
    try {
      val model = EditorModel.create(0)
      model.addElement(EditorElement(UriGlideRenderer(Uri.fromFile(file), false, 1000, 1000)))
      val size = Point(400, 400)

      val plain = model.render(context, size, typefaceProvider)
      assertChannels(GRAY, plain.getPixel(200, 200), 1)

      val adjustments = ImageAdjustments(exposure = 60f, warmth = 30f)
      model.setAdjustments(adjustments, true)
      val adjusted = model.render(context, size, typefaceProvider)
      val expected = floatArrayOf(Color.red(GRAY) / 255f, Color.green(GRAY) / 255f, Color.blue(GRAY) / 255f).also { ImageAdjustmentsColor.adjust(it, adjustments) }
      assertChannels(Color.rgb((expected[0] * 255).toInt(), (expected[1] * 255).toInt(), (expected[2] * 255).toInt()), adjusted.getPixel(200, 200), 4)

      model.setAdjustments(ImageAdjustments.DEFAULT, true)
      assertChannels(GRAY, model.render(context, size, typefaceProvider).getPixel(200, 200), 1)
    } finally {
      file.delete()
    }
  }

  private fun assertChannels(expected: Int, actual: Int, tolerance: Int) {
    val message = "expected #${Integer.toHexString(expected)} actual #${Integer.toHexString(actual)}"
    assertEquals(message, Color.red(expected).toFloat(), Color.red(actual).toFloat(), tolerance.toFloat())
    assertEquals(message, Color.green(expected).toFloat(), Color.green(actual).toFloat(), tolerance.toFloat())
    assertEquals(message, Color.blue(expected).toFloat(), Color.blue(actual).toFloat(), tolerance.toFloat())
  }

  private companion object {
    val GRAY = Color.rgb(128, 128, 128)
  }
}
