/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.VisibleForTesting
import org.signal.core.util.logging.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * Tellomi（tellomi/tellomi#1592 E5-2、#1595 E5-5）：用 GLES 2.0 离屏把调色画进一张位图：锐化 → 查找表 → 颗粒 → 暗角（[ImageAdjustmentsDetail]）。
 *
 * 只在 [ImageAdjustmentsRenderer] 的 GL 线程上用：EGL 上下文建在那条线程上，一直是它的当前上下文。
 * 图比 `GL_MAX_TEXTURE_SIZE`（再封顶 [MAX_TILE]）大时分块画；锐化要取旁边的像素，每块四周多传一圈重叠边（[apron]），只取中间那块写回。
 * 颗粒、暗角按像素在整张图里的位置算（每块传自己的左上角），分块不影响结果。
 * 着色器是自己写的（标准的 2D 排布查找表 + 两片之间插值、3×3 帐篷核、噪声纹理双线性），和 Telegram 的着色器无关。
 */
internal class ImageAdjustmentsGl private constructor(
  private val display: EGLDisplay,
  private val context: EGLContext,
  private val surface: EGLSurface,
  private val program: Int,
  private val maxTile: Int
) {

  private val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
  private val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
  private val imageHandle = GLES20.glGetUniformLocation(program, "uImage")
  private val lutHandle = GLES20.glGetUniformLocation(program, "uLut")
  private val lutSizeHandle = GLES20.glGetUniformLocation(program, "uLutSize")
  private val useLutHandle = GLES20.glGetUniformLocation(program, "uUseLut")
  private val noiseHandle = GLES20.glGetUniformLocation(program, "uNoise")
  private val texelHandle = GLES20.glGetUniformLocation(program, "uTexel")
  private val stepHandle = GLES20.glGetUniformLocation(program, "uStep")
  private val sharpenHandle = GLES20.glGetUniformLocation(program, "uSharpen")
  private val grainHandle = GLES20.glGetUniformLocation(program, "uGrain")
  private val grainCellHandle = GLES20.glGetUniformLocation(program, "uGrainCell")
  private val vignetteHandle = GLES20.glGetUniformLocation(program, "uVignette")
  private val originHandle = GLES20.glGetUniformLocation(program, "uOrigin")
  private val imageSizeHandle = GLES20.glGetUniformLocation(program, "uImageSize")
  private val frameXHandle = GLES20.glGetUniformLocation(program, "uFrameX")
  private val frameYHandle = GLES20.glGetUniformLocation(program, "uFrameY")

  /** 这一次怎么画：[lut] 为 null 时不查表；[sharpen] / [grain] / [vignette] 是着色器里直接用的量（倍数、振幅、值/100），0 = 不做。 */
  class Pass(
    val lut: ByteArray?,
    val lutSize: Int,
    val sharpen: Float,
    val grain: Float,
    val vignette: Float,
    val frame: ImageAdjustmentsDetail.VignetteFrame
  )

  private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
    put(QUAD)
    position(0)
  }

  /** 按 [pass] 把调色画进 [source]，返回一张新位图（同尺寸、同颜色空间）。 */
  fun render(source: Bitmap, pass: Pass): Bitmap {
    check(pass.lutSize * pass.lutSize <= maxTile) { "LUT does not fit in a texture" }
    val output = ImageAdjustmentsRenderer.createOutput(source)
    val unit = ImageAdjustmentsDetail.unit(maxOf(source.width, source.height))

    val textures = IntArray(4)
    val framebuffers = IntArray(1)
    GLES20.glGenTextures(4, textures, 0)
    GLES20.glGenFramebuffers(1, framebuffers, 0)
    val lutTexture = textures[0]
    val imageTexture = textures[1]
    val targetTexture = textures[2]
    val noiseTexture = textures[3]
    try {
      GLES20.glUseProgram(program)
      GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

      GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
      bindTexture(lutTexture, GLES20.GL_LINEAR)
      if (pass.lut != null) {
        val lutBuffer = ByteBuffer.allocateDirect(pass.lut.size).order(ByteOrder.nativeOrder()).put(pass.lut).apply { position(0) }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, pass.lutSize * pass.lutSize, pass.lutSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lutBuffer)
      } else {
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
      }
      GLES20.glUniform1i(lutHandle, 1)
      GLES20.glUniform1f(lutSizeHandle, pass.lutSize.toFloat())
      GLES20.glUniform1f(useLutHandle, if (pass.lut != null) 1f else 0f)

      // 噪声：256 是 2 的幂，GLES 2.0 才能用 GL_REPEAT 让它四边循环；线性过滤就是格子之间的双线性插值。
      GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexture)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
      val noise = ImageAdjustmentsDetail.NOISE
      val noiseBuffer = ByteBuffer.allocateDirect(noise.size).order(ByteOrder.nativeOrder()).put(noise).apply { position(0) }
      val noiseSize = ImageAdjustmentsDetail.NOISE_SIZE
      GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, noiseSize, noiseSize, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, noiseBuffer)
      GLES20.glUniform1i(noiseHandle, 2)

      GLES20.glUniform1i(imageHandle, 0)
      GLES20.glUniform1f(stepHandle, unit)
      GLES20.glUniform1f(sharpenHandle, pass.sharpen)
      GLES20.glUniform1f(grainHandle, pass.grain)
      GLES20.glUniform1f(grainCellHandle, ImageAdjustmentsDetail.GRAIN_CELL * unit)
      GLES20.glUniform1f(vignetteHandle, pass.vignette)
      GLES20.glUniform2f(imageSizeHandle, source.width.toFloat(), source.height.toFloat())
      GLES20.glUniform3f(frameXHandle, pass.frame.xx, pass.frame.xy, pass.frame.x0)
      GLES20.glUniform3f(frameYHandle, pass.frame.yx, pass.frame.yy, pass.frame.y0)

      quad.position(0)
      GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
      GLES20.glEnableVertexAttribArray(positionHandle)
      quad.position(2)
      GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
      GLES20.glEnableVertexAttribArray(texCoordHandle)

      if (source.width <= maxTile && source.height <= maxTile) {
        output.copyPixelsFromBuffer(renderTile(source, 0, 0, imageTexture, targetTexture, framebuffers[0]))
      } else {
        val apron = if (pass.sharpen > 0f) apron(unit) else 0
        val stride = maxTile - 2 * apron
        val canvas = Canvas(output)
        var top = 0
        while (top < source.height) {
          val height = minOf(stride, source.height - top)
          var left = 0
          while (left < source.width) {
            val width = minOf(stride, source.width - left)
            // 这一块连同四周的重叠边（图边上没有就不加，着色器在图边外取最靠边的像素，和不分块时一样）。
            val tileLeft = maxOf(0, left - apron)
            val tileTop = maxOf(0, top - apron)
            val tileRight = minOf(source.width, left + width + apron)
            val tileBottom = minOf(source.height, top + height + apron)
            val tile = Bitmap.createBitmap(source, tileLeft, tileTop, tileRight - tileLeft, tileBottom - tileTop)
            val pixels = renderTile(tile, tileLeft, tileTop, imageTexture, targetTexture, framebuffers[0])
            val tileOutput = ImageAdjustmentsRenderer.createOutput(tile)
            tileOutput.copyPixelsFromBuffer(pixels)
            val inner = Rect(left - tileLeft, top - tileTop, left - tileLeft + width, top - tileTop + height)
            canvas.drawBitmap(tileOutput, inner, Rect(left, top, left + width, top + height), null)
            tileOutput.recycle()
            if (tile !== source) tile.recycle()
            left += width
          }
          top += height
        }
      }
      checkGl("render")
      return output
    } finally {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
      GLES20.glDeleteFramebuffers(1, framebuffers, 0)
      GLES20.glDeleteTextures(4, textures, 0)
    }
  }

  /** [tile] 的左上角在整张图里是 ([originX], [originY])。 */
  private fun renderTile(tile: Bitmap, originX: Int, originY: Int, imageTexture: Int, targetTexture: Int, framebuffer: Int): ByteBuffer {
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    // 锐化在格点之间取样（u 不是整数时），要线性过滤；取格子中心时线性过滤就是原值。
    bindTexture(imageTexture, GLES20.GL_LINEAR)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, tile, 0)

    bindTexture(targetTexture, GLES20.GL_NEAREST)
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tile.width, tile.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targetTexture, 0)
    check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }

    // 采样的是原图纹理；目标纹理只挂在 framebuffer 上。
    bindTexture(imageTexture, GLES20.GL_LINEAR)
    GLES20.glUniform2f(texelHandle, 1f / tile.width, 1f / tile.height)
    GLES20.glUniform2f(originHandle, originX.toFloat(), originY.toFloat())
    GLES20.glViewport(0, 0, tile.width, tile.height)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

    // 纹理第 0 行是位图第 0 行，四边形把它放在 framebuffer 第 0 行，glReadPixels 也从第 0 行读：不用翻转。
    val pixels = ByteBuffer.allocateDirect(tile.width * tile.height * 4).order(ByteOrder.nativeOrder())
    GLES20.glReadPixels(0, 0, tile.width, tile.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
    pixels.position(0)
    return pixels
  }

  fun release() {
    GLES20.glDeleteProgram(program)
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    EGL14.eglDestroySurface(display, surface)
    EGL14.eglDestroyContext(display, context)
    EGL14.eglTerminate(display)
  }

  companion object {
    private val TAG = Log.tag(ImageAdjustmentsGl::class.java)

    /** 一块最大边长：再大的纹理也分块，glReadPixels 的缓冲不超过 16 MB。 */
    private const val MAX_TILE = 2048

    /** 锐化取样最远到 u 个像素外，再加一个像素给线性插值。 */
    @VisibleForTesting
    internal fun apron(unit: Float): Int = ceil(unit).toInt() + 1

    /** 两个三角形铺满视口：x, y, u, v；v = 0 在 y = −1（framebuffer 第 0 行）。 */
    private val QUAD = floatArrayOf(
      -1f, -1f, 0f, 0f,
      1f, -1f, 1f, 0f,
      -1f, 1f, 0f, 1f,
      1f, 1f, 1f, 1f
    )

    private const val VERTEX_SHADER = """
      attribute vec2 aPosition;
      attribute vec2 aTexCoord;
      varying vec2 vTexCoord;
      void main() {
        vTexCoord = aTexCoord;
        gl_Position = vec4(aPosition, 0.0, 1.0);
      }
    """

    // 位图上传后是预乘过 alpha 的：锐化在预乘的值上做（结果夹在 0…alpha），之后除掉 alpha 查表、加颗粒、压暗角，再乘回去。
    // vTexCoord 在像素中心是 (i + 0.5) / 宽，所以 uOrigin + vTexCoord / uTexel 就是这个像素中心在整张图里的位置（左上为原点）。
    private const val FRAGMENT_SHADER = """
      #ifdef GL_FRAGMENT_PRECISION_HIGH
      precision highp float;
      #else
      precision mediump float;
      #endif
      varying vec2 vTexCoord;
      uniform sampler2D uImage;
      uniform sampler2D uLut;
      uniform sampler2D uNoise;
      uniform float uLutSize;
      uniform float uUseLut;
      uniform vec2 uTexel;
      uniform float uStep;
      uniform float uSharpen;
      uniform float uGrain;
      uniform float uGrainCell;
      uniform float uVignette;
      uniform vec2 uOrigin;
      uniform vec2 uImageSize;
      uniform vec3 uFrameX;
      uniform vec3 uFrameY;

      vec3 lookup(vec3 color) {
        float last = uLutSize - 1.0;
        float slice = color.b * last;
        float slice0 = min(floor(slice), last - 1.0);
        float x = color.r * last + 0.5;
        float y = (color.g * last + 0.5) / uLutSize;
        float width = uLutSize * uLutSize;
        vec3 low = texture2D(uLut, vec2((slice0 * uLutSize + x) / width, y)).rgb;
        vec3 high = texture2D(uLut, vec2(((slice0 + 1.0) * uLutSize + x) / width, y)).rgb;
        return mix(low, high, slice - slice0);
      }

      void main() {
        vec4 color = texture2D(uImage, vTexCoord);
        if (uSharpen > 0.0) {
          vec2 dx = vec2(uStep * uTexel.x, 0.0);
          vec2 dy = vec2(0.0, uStep * uTexel.y);
          vec4 edges = texture2D(uImage, vTexCoord - dx) + texture2D(uImage, vTexCoord + dx)
            + texture2D(uImage, vTexCoord - dy) + texture2D(uImage, vTexCoord + dy);
          vec4 corners = texture2D(uImage, vTexCoord - dx - dy) + texture2D(uImage, vTexCoord + dx - dy)
            + texture2D(uImage, vTexCoord - dx + dy) + texture2D(uImage, vTexCoord + dx + dy);
          vec4 blur = (color * 4.0 + edges * 2.0 + corners) / 16.0;
          color.rgb = clamp(color.rgb + uSharpen * (color.rgb - blur.rgb), 0.0, color.a);
        }

        vec3 rgb = color.a > 0.0 ? clamp(color.rgb / color.a, 0.0, 1.0) : vec3(0.0);
        if (uUseLut > 0.5) {
          rgb = lookup(rgb);
        }

        vec2 pixel = uOrigin + vTexCoord / uTexel;
        if (uGrain > 0.0) {
          float n = texture2D(uNoise, pixel / (uGrainCell * 256.0)).r * (255.0 / 127.5) - 1.0;
          float t = abs(2.0 * dot(rgb, vec3(0.2126, 0.7152, 0.0722)) - 1.0);
          rgb = clamp(rgb + uGrain * (1.0 - t * t * t) * n, 0.0, 1.0);
        }
        if (uVignette > 0.0) {
          vec3 unit = vec3(pixel / uImageSize, 1.0);
          vec2 d = vec2(dot(uFrameX, unit), dot(uFrameY, unit));
          float rho = min(length(d) * 0.70710678, 1.0);
          float k = uVignette * rho * rho * rho;
          rgb = pow(rgb, vec3(1.0 + 1.2 * k)) * (1.0 - 0.35 * k);
        }

        gl_FragColor = vec4(rgb * color.a, color.a);
      }
    """

    /** 在当前线程建 EGL 上下文并设为当前；建不起来（没有 GPU、模拟环境）返回 null，调用方走 CPU。 */
    fun create(): ImageAdjustmentsGl? {
      return try {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != null && display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val configAttributes = intArrayOf(
          EGL14.EGL_RED_SIZE, 8,
          EGL14.EGL_GREEN_SIZE, 8,
          EGL14.EGL_BLUE_SIZE, 8,
          EGL14.EGL_ALPHA_SIZE, 8,
          EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
          EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
          EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        val config = configs[0]!!

        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != null && context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(surface != null && surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        val program = linkProgram()
        val maxTextureSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        val maxViewport = IntArray(2)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewport, 0)
        val maxTile = minOf(MAX_TILE, maxTextureSize[0], maxViewport[0], maxViewport[1])
        checkGl("create")
        check(maxTile >= ImageAdjustmentsColor.LUT_SIZE * ImageAdjustmentsColor.LUT_SIZE) { "Max texture size $maxTile too small" }
        ImageAdjustmentsGl(display, context, surface, program, maxTile)
      } catch (e: Throwable) {
        Log.w(TAG, "GL unavailable for adjustments, using the CPU", e)
        null
      }
    }

    private fun linkProgram(): Int {
      val program = GLES20.glCreateProgram()
      GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
      GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
      GLES20.glLinkProgram(program)
      val status = IntArray(1)
      GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
      check(status[0] == GLES20.GL_TRUE) { "Link failed: ${GLES20.glGetProgramInfoLog(program)}" }
      return program
    }

    private fun compileShader(type: Int, source: String): Int {
      val shader = GLES20.glCreateShader(type)
      GLES20.glShaderSource(shader, source.trimIndent())
      GLES20.glCompileShader(shader)
      val status = IntArray(1)
      GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
      check(status[0] == GLES20.GL_TRUE) { "Compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
      return shader
    }

    private fun bindTexture(texture: Int, filter: Int) {
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun checkGl(step: String) {
      val error = GLES20.glGetError()
      check(error == GLES20.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(error)} in $step" }
    }
  }
}
