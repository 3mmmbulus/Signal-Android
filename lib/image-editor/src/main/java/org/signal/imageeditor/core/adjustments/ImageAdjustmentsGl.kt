/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import org.signal.core.util.logging.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Tellomi（tellomi/tellomi#1592 E5-2）：用 GLES 2.0 离屏把调色查找表套到一张位图上。
 *
 * 只在 [ImageAdjustmentsRenderer] 的 GL 线程上用：EGL 上下文建在那条线程上，一直是它的当前上下文。
 * 图比 `GL_MAX_TEXTURE_SIZE`（再封顶 [MAX_TILE]）大时分块画，逐像素的工具分块不影响结果；以后和位置有关的 pass 要加重叠边。
 * 着色器是自己写的（标准的 2D 排布查找表 + 两片之间插值），和 Telegram 的着色器无关。
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

  private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
    put(QUAD)
    position(0)
  }

  /** 把 [lut]（[ImageAdjustmentsColor.buildLut] 的排布）套到 [source] 上，返回一张新位图（同尺寸、同颜色空间）。 */
  fun render(source: Bitmap, lut: ByteArray, lutSize: Int): Bitmap {
    check(lutSize * lutSize <= maxTile) { "LUT does not fit in a texture" }
    val output = ImageAdjustmentsRenderer.createOutput(source)

    val textures = IntArray(3)
    val framebuffers = IntArray(1)
    GLES20.glGenTextures(3, textures, 0)
    GLES20.glGenFramebuffers(1, framebuffers, 0)
    val (lutTexture, imageTexture, targetTexture) = Triple(textures[0], textures[1], textures[2])
    try {
      GLES20.glUseProgram(program)

      GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
      bindTexture(lutTexture, GLES20.GL_LINEAR)
      val lutBuffer = ByteBuffer.allocateDirect(lut.size).order(ByteOrder.nativeOrder()).put(lut).apply { position(0) }
      GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lutSize * lutSize, lutSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lutBuffer)
      GLES20.glUniform1i(lutHandle, 1)
      GLES20.glUniform1f(lutSizeHandle, lutSize.toFloat())

      GLES20.glUniform1i(imageHandle, 0)
      quad.position(0)
      GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
      GLES20.glEnableVertexAttribArray(positionHandle)
      quad.position(2)
      GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
      GLES20.glEnableVertexAttribArray(texCoordHandle)

      val canvas = if (source.width <= maxTile && source.height <= maxTile) null else Canvas(output)
      var top = 0
      while (top < source.height) {
        val height = minOf(maxTile, source.height - top)
        var left = 0
        while (left < source.width) {
          val width = minOf(maxTile, source.width - left)
          val tile = if (canvas == null) source else Bitmap.createBitmap(source, left, top, width, height)
          val pixels = renderTile(tile, imageTexture, targetTexture, framebuffers[0])
          if (canvas == null) {
            output.copyPixelsFromBuffer(pixels)
          } else {
            val tileOutput = ImageAdjustmentsRenderer.createOutput(tile)
            tileOutput.copyPixelsFromBuffer(pixels)
            canvas.drawBitmap(tileOutput, left.toFloat(), top.toFloat(), null)
            tileOutput.recycle()
            tile.recycle()
          }
          left += width
        }
        top += height
      }
      checkGl("render")
      return output
    } finally {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
      GLES20.glDeleteFramebuffers(1, framebuffers, 0)
      GLES20.glDeleteTextures(3, textures, 0)
    }
  }

  private fun renderTile(tile: Bitmap, imageTexture: Int, targetTexture: Int, framebuffer: Int): ByteBuffer {
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    bindTexture(imageTexture, GLES20.GL_NEAREST)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, tile, 0)

    bindTexture(targetTexture, GLES20.GL_NEAREST)
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tile.width, tile.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targetTexture, 0)
    check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }

    // 采样的是原图纹理；目标纹理只挂在 framebuffer 上。
    bindTexture(imageTexture, GLES20.GL_NEAREST)
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

    // 位图上传后是预乘过 alpha 的：先除掉 alpha 查表，再乘回去。
    private const val FRAGMENT_SHADER = """
      #ifdef GL_FRAGMENT_PRECISION_HIGH
      precision highp float;
      #else
      precision mediump float;
      #endif
      varying vec2 vTexCoord;
      uniform sampler2D uImage;
      uniform sampler2D uLut;
      uniform float uLutSize;

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
        vec3 rgb = color.a > 0.0 ? color.rgb / color.a : vec3(0.0);
        gl_FragColor = vec4(lookup(clamp(rgb, 0.0, 1.0)) * color.a, color.a);
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
