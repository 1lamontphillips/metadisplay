package com.lamontphillips.metadisplay.transporttest

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** All GL calls, including close, belong to the producer's one worker thread. */
internal class PhoneCameraEglRenderer(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val sensorOrientation: Int,
    private val displayRotation: Int,
    private val callbackHandler: Handler,
) : AutoCloseable {
  private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var context: EGLContext = EGL14.EGL_NO_CONTEXT
  private var window: EGLSurface = EGL14.EGL_NO_SURFACE
  private var texture: SurfaceTexture? = null
  private var cameraSurface: Surface? = null
  private var textureId = 0
  private var program = 0
  private var positionAttribute = -1
  private var textureAttribute = -1
  private var textureMatrixUniform = -1
  private var vertexMatrixUniform = -1
  private val pending = AtomicBoolean()
  private val frames = AtomicLong()
  private var hasTextureImage = false
  private var lastTextureTimeNs = 0L
  var freshTextureUpdates: Long = 0
    private set
  var repeatedFrames: Long = 0
    private set
  var submittedFrames: Long = 0
    private set
  var maxFreshFrameGapUs: Long = 0
    private set
  private val textureMatrix = FloatArray(16)
  private val vertexMatrix = FloatArray(16)
  private val surfaceMatrix = FloatArray(16)
  private val lastSurfaceMatrix = FloatArray(16)
  private var geometryDirty = true
  private var userQuarterTurns = 0
  private var fillFrame = true
  private var nextViewSnapshot = PhoneCameraProducer.ViewSnapshot()
  var appliedViewSnapshot = PhoneCameraProducer.ViewSnapshot()
    private set
  private val displayRotationDegrees = when (displayRotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
  }
  private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
      .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
  private val coordinates = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
      .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

  val capturedFrameCount: Long get() = frames.get()
  val hasImageAvailable: Boolean get() = hasTextureImage || pending.get()

  fun initialize(): Surface {
    try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(display != EGL14.EGL_NO_DISPLAY) { "CAMERA_EGL_FAILED" }
      check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "CAMERA_EGL_FAILED" }
      val attributes = intArrayOf(
          EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
          EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
          EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE)
      val configs = arrayOfNulls<EGLConfig>(1)
      val count = IntArray(1)
      check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
        "CAMERA_EGL_FAILED"
      }
      val config = checkNotNull(configs[0]) { "CAMERA_EGL_FAILED" }
      context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
          intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
      check(context != EGL14.EGL_NO_CONTEXT) { "CAMERA_EGL_FAILED" }
      window = EGL14.eglCreateWindowSurface(display, config, encoderSurface,
          intArrayOf(EGL14.EGL_NONE), 0)
      check(window != EGL14.EGL_NO_SURFACE) { "CAMERA_EGL_FAILED" }
      check(EGL14.eglMakeCurrent(display, window, window, context)) { "CAMERA_EGL_FAILED" }
      program = createProgram()
      positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
      textureAttribute = GLES20.glGetAttribLocation(program, "aTexture")
      textureMatrixUniform = GLES20.glGetUniformLocation(program, "uTextureMatrix")
      vertexMatrixUniform = GLES20.glGetUniformLocation(program, "uVertexMatrix")
      val names = IntArray(1)
      GLES20.glGenTextures(1, names, 0)
      textureId = names[0]
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      val source = SurfaceTexture(textureId)
      texture = source
      source.setDefaultBufferSize(captureWidth, captureHeight)
      source.setOnFrameAvailableListener({
        frames.incrementAndGet()
        pending.set(true)
      }, callbackHandler)
      return Surface(source).also { cameraSurface = it }
    } catch (failure: Exception) {
      try { close() } catch (_: Exception) { /* Producer reports a static failure code. */ }
      throw failure
    }
  }

  fun setViewOptions(manualQuarterTurns: Int, fillFrame: Boolean) {
    val normalized = Math.floorMod(manualQuarterTurns, 4)
    if (userQuarterTurns != normalized || this.fillFrame != fillFrame) {
      userQuarterTurns = normalized
      this.fillFrame = fillFrame
      geometryDirty = true
    }
  }

  private fun updateGeometry() {
    if (!geometryDirty) return
    val result = CameraViewTransform.calculate(surfaceMatrix, captureWidth, captureHeight,
        width, height, displayRotationDegrees, userQuarterTurns, fillFrame)
    result.textureMatrix.copyInto(textureMatrix)
    result.vertexMatrix.copyInto(vertexMatrix)
    nextViewSnapshot = PhoneCameraProducer.ViewSnapshot(
        captureWidth = captureWidth,
        captureHeight = captureHeight,
        sensorOrientationDegrees = sensorOrientation,
        displayRotationDegrees = displayRotationDegrees,
        userRotationDegrees = userQuarterTurns * 90,
        fillFrame = fillFrame,
        surfaceM00 = surfaceMatrix[0].toDouble(),
        surfaceM01 = surfaceMatrix[4].toDouble(),
        surfaceM10 = surfaceMatrix[1].toDouble(),
        surfaceM11 = surfaceMatrix[5].toDouble(),
        effectiveSourceAspect = result.effectiveSourceAspect,
        cropFractionX = result.cropFractionX,
        cropFractionY = result.cropFractionY,
        vertexScaleX = result.vertexScaleX,
        vertexScaleY = result.vertexScaleY,
        surfaceAxesSwapped = result.surfaceAxesSwapped,
    )
    geometryDirty = false
  }

  fun renderNextFrame(presentationTimeNs: Long): Boolean {
    val source = checkNotNull(texture) { "CAMERA_EGL_FAILED" }
    var fresh = false
    if (pending.getAndSet(false)) {
      source.updateTexImage()
      source.getTransformMatrix(surfaceMatrix)
      if (!surfaceMatrix.contentEquals(lastSurfaceMatrix)) {
        surfaceMatrix.copyInto(lastSurfaceMatrix)
        geometryDirty = true
      }
      val timestamp = source.timestamp
      if (!hasTextureImage || timestamp != lastTextureTimeNs) {
        if (hasTextureImage && timestamp > lastTextureTimeNs) {
          maxFreshFrameGapUs = maxOf(maxFreshFrameGapUs, (timestamp - lastTextureTimeNs) / 1000)
        }
        lastTextureTimeNs = timestamp
        freshTextureUpdates++
        fresh = true
      }
      hasTextureImage = true
    }
    // Reuse the latest real camera texture between arrivals, never an uninitialized image.
    if (!hasTextureImage) return false
    updateGeometry()
    GLES20.glViewport(0, 0, width, height)
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    GLES20.glUseProgram(program)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uImage"), 0)
    GLES20.glUniformMatrix4fv(textureMatrixUniform, 1, false, textureMatrix, 0)
    GLES20.glUniformMatrix4fv(vertexMatrixUniform, 1, false, vertexMatrix, 0)
    GLES20.glEnableVertexAttribArray(positionAttribute)
    GLES20.glEnableVertexAttribArray(textureAttribute)
    GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices)
    GLES20.glVertexAttribPointer(textureAttribute, 2, GLES20.GL_FLOAT, false, 0, coordinates)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(positionAttribute)
    GLES20.glDisableVertexAttribArray(textureAttribute)
    check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "CAMERA_EGL_FAILED" }
    check(EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)) { "CAMERA_EGL_FAILED" }
    check(EGL14.eglSwapBuffers(display, window)) { "CAMERA_EGL_FAILED" }
    appliedViewSnapshot = nextViewSnapshot
    submittedFrames++
    if (!fresh) repeatedFrames++
    return true
  }

  override fun close() {
    texture?.setOnFrameAvailableListener(null)
    cameraSurface?.release()
    cameraSurface = null
    texture?.release()
    texture = null
    if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT &&
        window != EGL14.EGL_NO_SURFACE) {
      EGL14.eglMakeCurrent(display, window, window, context)
      if (program != 0) GLES20.glDeleteProgram(program)
      if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }
    program = 0
    textureId = 0
    if (display != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
      if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
      if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
      EGL14.eglReleaseThread()
      EGL14.eglTerminate(display)
    }
    window = EGL14.EGL_NO_SURFACE
    context = EGL14.EGL_NO_CONTEXT
    display = EGL14.EGL_NO_DISPLAY
  }

  private fun shader(type: Int, source: String): Int {
    val id = GLES20.glCreateShader(type)
    GLES20.glShaderSource(id, source)
    GLES20.glCompileShader(id)
    val status = IntArray(1)
    GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] != GLES20.GL_TRUE) {
      GLES20.glDeleteShader(id)
      error("CAMERA_EGL_FAILED")
    }
    return id
  }

  private fun createProgram(): Int {
    var vertex = 0
    var fragment = 0
    var linked = 0
    try {
      vertex = shader(GLES20.GL_VERTEX_SHADER, """
          attribute vec4 aPosition;
          attribute vec4 aTexture;
          uniform mat4 uTextureMatrix;
          uniform mat4 uVertexMatrix;
          varying vec2 vTexture;
          void main() {
            gl_Position = uVertexMatrix * aPosition;
            vTexture = (uTextureMatrix * aTexture).xy;
          }
      """.trimIndent())
      fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
          #extension GL_OES_EGL_image_external : require
          precision mediump float;
          uniform samplerExternalOES uImage;
          varying vec2 vTexture;
          void main() { gl_FragColor = texture2D(uImage, vTexture); }
      """.trimIndent())
      linked = GLES20.glCreateProgram()
      GLES20.glAttachShader(linked, vertex)
      GLES20.glAttachShader(linked, fragment)
      GLES20.glLinkProgram(linked)
      val status = IntArray(1)
      GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
      check(status[0] == GLES20.GL_TRUE) { "CAMERA_EGL_FAILED" }
      return linked
    } catch (failure: Exception) {
      if (linked != 0) GLES20.glDeleteProgram(linked)
      throw failure
    } finally {
      if (vertex != 0) GLES20.glDeleteShader(vertex)
      if (fragment != 0) GLES20.glDeleteShader(fragment)
    }
  }
}
