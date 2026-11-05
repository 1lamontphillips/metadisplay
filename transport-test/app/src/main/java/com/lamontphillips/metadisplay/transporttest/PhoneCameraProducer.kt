package com.lamontphillips.metadisplay.transporttest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.MuxerUtil
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.function.Consumer
import kotlin.math.abs

/** Foreground-only, silent rear-camera experiment with an exact twenty-slot response budget. */
@SuppressLint("UnsafeOptInUsageError")
class PhoneCameraProducer(
    context: Context,
    private val buffer: BoundedMediaBuffer,
    private val displayRotation: Int,
    private val listener: Consumer<Event>,
) : AutoCloseable {
  data class ViewSnapshot(
      val captureWidth: Int = 0,
      val captureHeight: Int = 0,
      val sensorOrientationDegrees: Int = 0,
      val displayRotationDegrees: Int = 0,
      val userRotationDegrees: Int = 0,
      val fillFrame: Boolean = true,
      val surfaceM00: Double = 0.0,
      val surfaceM01: Double = 0.0,
      val surfaceM10: Double = 0.0,
      val surfaceM11: Double = 0.0,
      val effectiveSourceAspect: Double = 0.0,
      val cropFractionX: Double = 1.0,
      val cropFractionY: Double = 1.0,
      val vertexScaleX: Double = 1.0,
      val vertexScaleY: Double = 1.0,
      val surfaceAxesSwapped: Boolean = false,
  )

  data class PacingSnapshot(
      val submittedFrames: Long = 0,
      val freshTextureUpdates: Long = 0,
      val repeatedFrames: Long = 0,
      val skippedTicks: Long = 0,
      val maxFreshFrameGapUs: Long = 0,
      val maxRenderLatenessUs: Long = 0,
      val maxRenderWorkUs: Long = 0,
      val maxCodecPtsGapUs: Long = 0,
      val maxKeyframePtsGapUs: Long = 0,
      val maxFragmentGapUs: Long = 0,
      val maxDrainWorkUs: Long = 0,
      val maxMuxWriteUs: Long = 0,
  )

  data class Event(
      val kind: String,
      val code: String,
      val capturedFrames: Long,
      val encodedFrames: Long,
      val completedFragments: Int,
      val width: Int,
      val height: Int,
      val pacing: PacingSnapshot = PacingSnapshot(),
      val view: ViewSnapshot = ViewSnapshot(),
  )

  data class Snapshot(
      val state: String,
      val capturedFrames: Long,
      val encodedFrames: Long,
      val completedFragments: Int,
      val width: Int,
      val height: Int,
      val pacing: PacingSnapshot = PacingSnapshot(),
      val view: ViewSnapshot = ViewSnapshot(),
  )

  private companion object {
    const val MIME = "video/avc"
    const val CLOSE_WAIT_MS = 3_000L
    const val OPEN_WAIT_MS = 2_000L
    const val FRAME_TIMEOUT_NS = 2_000_000_000L
    const val INIT_TIMEOUT_NS = 4_000_000_000L
    const val FRAGMENT_TIMEOUT_NS = 5_000_000_000L
    const val TOTAL_TIMEOUT_NS = 35_000_000_000L
    const val PACING_STALL_NS = 500_000_000L
  }

  private class CameraFailure(val code: String) : RuntimeException(code)

  private data class CameraChoice(
      val identifier: String,
      val size: Size,
      val sensorOrientation: Int,
      val fpsRange: Range<Int>?,
  )

  private data class EncoderChoice(val codec: MediaCodec, val surface: Surface)
  private data class ViewOptions(val quarterTurns: Int = 0, val fillFrame: Boolean = true)

  private class PacingTimings {
    var maxRenderWorkNs = 0L
    var maxCodecPtsGapUs = 0L
    var maxKeyframePtsGapUs = 0L
    var maxFragmentGapNs = 0L
    var maxDrainWorkNs = 0L
    var maxMuxWriteNs = 0L
  }

  private val app = context.applicationContext
  private val lifecycle = Any()
  private val stopping = AtomicBoolean()
  private val releasing = AtomicBoolean()
  private val cleanupFailed = AtomicBoolean()
  private val failureSent = AtomicBoolean()
  private val status = AtomicReference("IDLE")
  private val captured = AtomicLong()
  private val encoded = AtomicLong()
  private val completed = AtomicInteger()
  private val pacing = AtomicReference(PacingSnapshot())
  private val view = AtomicReference(ViewSnapshot())
  private val viewOptions = AtomicReference(ViewOptions())
  private val asynchronousFailure = AtomicReference<String?>(null)
  private val camera = AtomicReference<CameraDevice?>(null)
  private val cameraSession = AtomicReference<CameraCaptureSession?>(null)
  private val cameraClosed = CountDownLatch(1)
  private val cameraOpenResolved = CountDownLatch(1)
  private val cameraOpenRequested = AtomicBoolean()
  private val lateCameraCleanup = AtomicBoolean()
  private val callbackThreadReference = AtomicReference<HandlerThread?>(null)
  @Volatile private var worker: Thread? = null
  private var started = false

  /** Returns immediately. Camera, EGL, codec and muxer work runs on one dedicated worker. */
  fun start() {
    synchronized(lifecycle) {
      check(!stopping.get()) { "CAMERA_STOPPED" }
      if (started) return
      started = true
      worker = Thread({ produce() }, "phone-camera-producer").also { it.start() }
    }
  }

  fun snapshot(): Snapshot = Snapshot(status.get(), captured.get(), encoded.get(), completed.get(),
      LiveVideoConfig.WIDTH, LiveVideoConfig.HEIGHT, pacing.get(), view.get())

  /** Changes composition at the next draw tick, without touching camera/session timing. */
  fun setViewOptions(manualQuarterTurns: Int, fillFrame: Boolean) {
    viewOptions.set(ViewOptions(Math.floorMod(manualQuarterTurns, 4), fillFrame))
  }

  private fun event(kind: String, code: String) {
    val value = snapshot()
    try {
      listener.accept(Event(kind, code, value.capturedFrames, value.encodedFrames,
          value.completedFragments, value.width, value.height, value.pacing, value.view))
    } catch (_: RuntimeException) {
      // The caller's UI callback cannot interrupt camera cleanup or expose frame data.
    }
  }

  private fun publishPacing(
      renderer: PhoneCameraEglRenderer?,
      pacer: CameraFramePacer,
      timings: PacingTimings,
  ) {
    renderer?.let { view.set(it.appliedViewSnapshot) }
    pacing.set(PacingSnapshot(
        submittedFrames = renderer?.submittedFrames ?: 0,
        freshTextureUpdates = renderer?.freshTextureUpdates ?: 0,
        repeatedFrames = renderer?.repeatedFrames ?: 0,
        skippedTicks = pacer.skippedTicks,
        maxFreshFrameGapUs = renderer?.maxFreshFrameGapUs ?: 0,
        maxRenderLatenessUs = pacer.maxLatenessNs / 1000,
        maxRenderWorkUs = timings.maxRenderWorkNs / 1000,
        maxCodecPtsGapUs = timings.maxCodecPtsGapUs,
        maxKeyframePtsGapUs = timings.maxKeyframePtsGapUs,
        maxFragmentGapUs = timings.maxFragmentGapNs / 1000,
        maxDrainWorkUs = timings.maxDrainWorkNs / 1000,
        maxMuxWriteUs = timings.maxMuxWriteNs / 1000,
    ))
  }

  private fun fail(code: String) {
    if (!failureSent.compareAndSet(false, true)) return
    status.set("FAILED")
    try { buffer.fail(code) } catch (_: RuntimeException) { /* Already closed by owner. */ }
    event("FAILED", code)
  }

  @SuppressLint("MissingPermission")
  private fun produce() {
    var callbackThread: HandlerThread? = null
    var renderer: PhoneCameraEglRenderer? = null
    var encoder: MediaCodec? = null
    var inputSurface: Surface? = null
    var parser: FixedBudgetMp4? = null
    var muxer: FragmentedMp4Muxer? = null
    var quotaComplete = false
    var encoderStarted = false
    val pacer = CameraFramePacer(LiveVideoConfig.FPS, PACING_STALL_NS)
    val timings = PacingTimings()
    var stage = "CAMERA_FAILED"
    try {
      if (stopping.get()) return
      status.set("STARTING")
      event("STATE", "CAMERA_STARTING")
      if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        throw CameraFailure("CAMERA_PERMISSION_REQUIRED")
      }
      val manager = app.getSystemService(CameraManager::class.java)
      val selected = chooseCamera(manager)
      if (stopping.get()) return
      stage = "CAMERA_CODEC_FAILED"
      val selectedEncoder = createEncoder()
      encoder = selectedEncoder.codec
      inputSurface = selectedEncoder.surface
      callbackThread = HandlerThread("phone-camera-callbacks").also { it.start() }
      callbackThreadReference.set(callbackThread)
      val callbackHandler = Handler(callbackThread.looper)
      stage = "CAMERA_EGL_FAILED"
      renderer = PhoneCameraEglRenderer(inputSurface, LiveVideoConfig.WIDTH, LiveVideoConfig.HEIGHT,
          selected.size.width, selected.size.height, selected.sensorOrientation,
          displayRotation, callbackHandler)
      val cameraInput = renderer.initialize()
      stage = "CAMERA_MUXER_FAILED"
      val activeParser = FixedBudgetMp4(LiveVideoConfig.FRAGMENTS, LiveVideoConfig.SLOT_BYTES, buffer)
      parser = activeParser
      muxer = FragmentedMp4Muxer.Builder(activeParser).setFragmentDurationMs(500)
          .setSampleCopyingEnabled(true).build()
      stage = "CAMERA_CODEC_FAILED"
      encoder.start()
      encoderStarted = true
      if (stopping.get()) return
      stage = "CAMERA_OPEN_FAILED"
      val device = openCamera(manager, selected.identifier, callbackHandler)
      if (stopping.get()) return
      stage = "CAMERA_CONFIGURE_FAILED"
      val session = configureCamera(device, cameraInput, callbackHandler)
      if (stopping.get()) return
      val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
        addTarget(cameraInput)
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        selected.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
      }.build()
      session.setRepeatingRequest(request, null, callbackHandler)
      status.set("RUNNING")
      event("STATE", "CAMERA_RUNNING")
      val began = System.nanoTime()
      var lastFrame = began
      var lastProgress = began
      var lastFragment = began
      var previousCameraFrames = 0L
      var previousFragments = 0
      var track = -1
      var firstPts: Long? = null
      var lastPts = -1L
      var lastKeyframePts: Long? = null
      fun observeFragmentRelease() {
        val slots = activeParser.emittedSlots
        completed.set(slots)
        if (slots != previousFragments) {
          val releasedAt = System.nanoTime()
          if (previousFragments > 0) {
            timings.maxFragmentGapNs = maxOf(timings.maxFragmentGapNs, releasedAt - lastFragment)
          }
          lastFragment = releasedAt
          previousFragments = slots
        }
      }
      val info = MediaCodec.BufferInfo()
      while (!stopping.get() && !parser.isComplete) {
        asynchronousFailure.get()?.let { throw CameraFailure(it) }
        val now = System.nanoTime()
        val cameraFrames = renderer.capturedFrameCount
        captured.set(cameraFrames)
        if (cameraFrames != previousCameraFrames) {
          lastFrame = now
          previousCameraFrames = cameraFrames
        }
        if (now - lastFrame > FRAME_TIMEOUT_NS) throw CameraFailure("CAMERA_FRAME_STALLED")
        if (now - began > INIT_TIMEOUT_NS && track < 0) throw CameraFailure("CAMERA_INIT_TIMEOUT")
        if (now - lastFragment > FRAGMENT_TIMEOUT_NS || now - began > TOTAL_TIMEOUT_NS) {
          throw CameraFailure("CAMERA_SLOT_TIMEOUT")
        }
        stage = "CAMERA_EGL_FAILED"
        // The first real image establishes the clock. Warmup never produces black frames
        // or counts as a missed render deadline.
        if (!pacer.isStarted && renderer.hasImageAvailable) pacer.start(now)
        val tick = pacer.poll(now)
        if (tick != null) {
          if (tick.stalled) throw CameraFailure("CAMERA_PACING_STALLED")
          val renderBegan = System.nanoTime()
          try {
            val options = viewOptions.get()
            renderer.setViewOptions(options.quarterTurns, options.fillFrame)
            renderer.renderNextFrame(tick.presentationTimeNs)
          } catch (_: CameraViewTransform.InvalidTransformException) {
            throw CameraFailure("CAMERA_VIEW_INVALID")
          } finally {
            timings.maxRenderWorkNs = maxOf(timings.maxRenderWorkNs, System.nanoTime() - renderBegan)
          }
        }
        stage = "CAMERA_CODEC_FAILED"
        var drained = 0
        val encodedBeforeDrain = encoded.get()
        val drainBegan = System.nanoTime()
        try {
          while (!stopping.get() && !parser.isComplete && drained++ < 64) {
            when (val index = encoder.dequeueOutputBuffer(info, 0)) {
              MediaCodec.INFO_TRY_AGAIN_LATER -> break
              MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                if (track >= 0) throw CameraFailure("CAMERA_CODEC_FAILED")
                val format = encoder.outputFormat
                if (format.containsKey(MediaFormat.KEY_MAX_B_FRAMES) &&
                    format.getInteger(MediaFormat.KEY_MAX_B_FRAMES) > 0) {
                  throw CameraFailure("CAMERA_CODEC_FAILED")
                }
                stage = "CAMERA_MUXER_FAILED"
                track = muxer.addTrack(muxerFormat(format))
                stage = "CAMERA_CODEC_FAILED"
              }
              else -> if (index >= 0) {
                try {
                  if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    if (track < 0) throw CameraFailure("CAMERA_CODEC_FAILED")
                    val bytes = encoder.getOutputBuffer(index)
                        ?: throw CameraFailure("CAMERA_CODEC_FAILED")
                    bytes.position(info.offset)
                    bytes.limit(info.offset + info.size)
                    val origin = firstPts ?: info.presentationTimeUs.also { firstPts = it }
                    val pts = info.presentationTimeUs - origin
                    if (pts < 0 || pts <= lastPts) throw CameraFailure("CAMERA_CODEC_FAILED")
                    if (lastPts >= 0) timings.maxCodecPtsGapUs = maxOf(timings.maxCodecPtsGapUs, pts - lastPts)
                    lastPts = pts
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                      lastKeyframePts?.let { previous ->
                        timings.maxKeyframePtsGapUs = maxOf(timings.maxKeyframePtsGapUs, pts - previous)
                      }
                      lastKeyframePts = pts
                    }
                    val normalized = MediaCodec.BufferInfo().apply {
                      set(0, info.size, pts, info.flags)
                    }
                    stage = "CAMERA_MUXER_FAILED"
                    val muxBegan = System.nanoTime()
                    try {
                      muxer.writeSampleData(track, bytes.slice(),
                          MuxerUtil.getMuxerBufferInfoFromMediaCodecBufferInfo(normalized))
                    } catch (_: Exception) {
                      throw CameraFailure("CAMERA_MUXER_FAILED")
                    } finally {
                      timings.maxMuxWriteNs = maxOf(timings.maxMuxWriteNs, System.nanoTime() - muxBegan)
                    }
                    encoded.incrementAndGet()
                    observeFragmentRelease()
                  }
                } finally {
                  stage = "CAMERA_CODEC_FAILED"
                  encoder.releaseOutputBuffer(index, false)
                }
              }
            }
          }
        } finally {
          timings.maxDrainWorkNs = maxOf(timings.maxDrainWorkNs, System.nanoTime() - drainBegan)
        }
        observeFragmentRelease()
        if (tick != null || encoded.get() != encodedBeforeDrain || now - lastProgress >= 1_000_000_000L) {
          publishPacing(renderer, pacer, timings)
        }
        if (now - lastProgress >= 1_000_000_000L) {
          event("PROGRESS", "CAMERA_PROGRESS")
          lastProgress = now
        }
        if (!parser.isComplete && !stopping.get()) {
          val untilNext = if (pacer.isStarted) pacer.nextDeadlineNs - System.nanoTime() else 2_000_000L
          LockSupport.parkNanos(minOf(2_000_000L, untilNext.coerceAtLeast(1L)))
          if (Thread.interrupted() && !stopping.get()) throw CameraFailure("CAMERA_INTERRUPTED")
        }
      }
      quotaComplete = parser.isComplete
      completed.set(parser.emittedSlots)
    } catch (problem: CameraFailure) {
      publishPacing(renderer, pacer, timings)
      if (!stopping.get()) fail(problem.code)
    } catch (_: InterruptedException) {
      publishPacing(renderer, pacer, timings)
      if (!stopping.get()) fail("CAMERA_INTERRUPTED")
    } catch (_: Exception) {
      publishPacing(renderer, pacer, timings)
      if (!stopping.get()) fail(stage)
    } finally {
      // A stop interrupt wakes waits; it must not skip the bounded native-resource cleanup.
      Thread.interrupted()
      releasing.set(true)
      closeCaptureResources()
      val openSettled = !cameraOpenRequested.get() || awaitLatch(cameraOpenResolved, 1_500)
      if (!openSettled) {
        // Keep the callback looper alive so a late onOpened is immediately closed. The
        // owner receives cleanup failure and cannot start another test in this process.
        lateCameraCleanup.set(true)
        cleanupFailed.set(true)
      }
      closeCaptureResources()
      renderer?.let {
        captured.set(it.capturedFrameCount)
        releaseResource { it.close() }
      }
      // All encoded samples were copied by the muxer before codec buffers were released.
      encoder?.let { codec ->
        if (encoderStarted) releaseResource { codec.stop() }
        releaseResource { codec.release() }
      }
      releaseResource { inputSurface?.release() }
      try {
        muxer?.close()
      } catch (_: Exception) {
        if (!stopping.get() && !failureSent.get()) fail("CAMERA_MUXER_FAILED")
      }
      try {
        parser?.close()
      } catch (_: IOException) {
        if (!stopping.get() && !failureSent.get()) fail("CAMERA_MUXER_FAILED")
      }
      if (camera.get() != null && !awaitLatch(cameraClosed, 500)) cleanupFailed.set(true)
      callbackThread?.let { thread ->
        if (openSettled || cameraClosed.count == 0L) {
          thread.quitSafely()
          joinCallbackThread(thread)
        }
      }
      publishPacing(renderer, pacer, timings)
      if (cleanupFailed.get()) {
        fail("CAMERA_CLEANUP_FAILED")
      } else if (quotaComplete && !failureSent.get()) {
        status.set("COMPLETE")
        event("COMPLETE", "CAMERA_COMPLETE")
      } else if (!failureSent.get()) {
        status.set("STOPPED")
        event("STATE", "CAMERA_STOPPED")
      }
    }
  }

  private fun chooseCamera(manager: CameraManager): CameraChoice {
    for (id in manager.cameraIdList) {
      val characteristics = manager.getCameraCharacteristics(id)
      if (characteristics[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) continue
      val map = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: continue
      val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
      if (sizes.isEmpty()) continue
      val modest = sizes.filter { it.width.toLong() * it.height <= 1280L * 720 &&
          it.width >= LiveVideoConfig.WIDTH && it.height >= LiveVideoConfig.HEIGHT }
      val candidates = modest.ifEmpty { sizes }
      val desiredAspect = LiveVideoConfig.WIDTH.toDouble() / LiveVideoConfig.HEIGHT
      val size = candidates.minBy { abs(it.width.toDouble() / it.height - desiredAspect) * 4 +
          it.width.toDouble() * it.height / (1280 * 720) }
      val ranges = characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
      val range = ranges?.filter { it.upper >= LiveVideoConfig.FPS }
          ?.minByOrNull { abs(it.upper - LiveVideoConfig.FPS) * 2 + abs(it.lower - LiveVideoConfig.FPS) }
      return CameraChoice(id, size, characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0, range)
    }
    throw CameraFailure("NO_REAR_CAMERA")
  }

  private fun createEncoder(): EncoderChoice {
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, true) } }
        .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
    for (info in infos) {
      if (stopping.get()) throw InterruptedException()
      var codec: MediaCodec? = null
      var surface: Surface? = null
      try {
        val capabilities = info.getCapabilitiesForType(MIME)
        if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
        if (capabilities.videoCapabilities?.areSizeAndRateSupported(LiveVideoConfig.WIDTH,
                LiveVideoConfig.HEIGHT, LiveVideoConfig.FPS.toDouble()) != true) continue
        val format = MediaFormat.createVideoFormat(MIME, LiveVideoConfig.WIDTH, LiveVideoConfig.HEIGHT).apply {
          setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
          setInteger(MediaFormat.KEY_BIT_RATE, LiveVideoConfig.BITRATE)
          setInteger(MediaFormat.KEY_FRAME_RATE, LiveVideoConfig.FPS)
          setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
          setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
          if (capabilities.encoderCapabilities?.isBitrateModeSupported(
                  MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
          }
        }
        codec = MediaCodec.createByCodecName(info.name)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        return EncoderChoice(codec, surface)
      } catch (_: Exception) {
        try { surface?.release() } catch (_: Exception) { /* Try the next public encoder. */ }
        try { codec?.release() } catch (_: Exception) { cleanupFailed.set(true) }
      }
    }
    throw CameraFailure("NO_AVC_ENCODER")
  }

  @SuppressLint("MissingPermission")
  private fun openCamera(manager: CameraManager, id: String, handler: Handler): CameraDevice {
    val opened = CountDownLatch(1)
    cameraOpenRequested.set(true)
    try {
      manager.openCamera(id, object : CameraDevice.StateCallback() {
      override fun onOpened(device: CameraDevice) {
        camera.set(device)
        if (stopping.get() || releasing.get()) {
          device.close()
        }
        cameraOpenResolved.countDown()
        opened.countDown()
      }
      override fun onDisconnected(device: CameraDevice) = cameraFailure(device)
      override fun onError(device: CameraDevice, error: Int) = cameraFailure(device)
      override fun onClosed(device: CameraDevice) {
        cameraClosed.countDown()
        if (lateCameraCleanup.get()) callbackThreadReference.get()?.quitSafely()
      }
      private fun cameraFailure(device: CameraDevice) {
        if (!stopping.get() && !releasing.get()) asynchronousFailure.set("CAMERA_OPEN_FAILED")
        device.close()
        cameraOpenResolved.countDown()
        opened.countDown()
      }
      }, handler)
    } catch (failure: Exception) {
      cameraOpenResolved.countDown()
      throw failure
    }
    if (!opened.await(OPEN_WAIT_MS, TimeUnit.MILLISECONDS)) throw CameraFailure("CAMERA_OPEN_FAILED")
    return camera.get() ?: throw CameraFailure("CAMERA_OPEN_FAILED")
  }

  @Suppress("DEPRECATION")
  private fun configureCamera(device: CameraDevice, surface: Surface, handler: Handler): CameraCaptureSession {
    val configured = CountDownLatch(1)
    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(session: CameraCaptureSession) {
        if (stopping.get() || releasing.get()) session.close() else cameraSession.set(session)
        configured.countDown()
      }
      override fun onConfigureFailed(session: CameraCaptureSession) {
        asynchronousFailure.set("CAMERA_CONFIGURE_FAILED")
        session.close()
        configured.countDown()
      }
    }, handler)
    if (!configured.await(OPEN_WAIT_MS, TimeUnit.MILLISECONDS)) throw CameraFailure("CAMERA_CONFIGURE_FAILED")
    return cameraSession.get() ?: throw CameraFailure("CAMERA_CONFIGURE_FAILED")
  }

  private fun muxerFormat(format: MediaFormat): Format {
    val converted = MediaFormatUtil.createFormatFromMediaFormat(format)
    val units = converted.initializationData.flatMap(::annexBUnits)
    val sps = units.firstOrNull { it.isNotEmpty() && it[0].toInt() and 31 == 7 }
    val pps = units.firstOrNull { it.isNotEmpty() && it[0].toInt() and 31 == 8 }
    if (sps == null || pps == null) throw CameraFailure("CAMERA_CODEC_FAILED")
    val prefix = byteArrayOf(0, 0, 0, 1)
    return converted.buildUpon().setInitializationData(listOf(prefix + sps, prefix + pps)).build()
  }

  private fun annexBUnits(data: ByteArray): List<ByteArray> {
    val starts = ArrayList<Pair<Int, Int>>()
    var offset = 0
    while (offset + 3 <= data.size) {
      val prefix = if (offset + 4 <= data.size && data[offset] == 0.toByte() &&
          data[offset + 1] == 0.toByte() && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) 4
          else if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte()) 3 else 0
      if (prefix > 0) { starts.add(offset to prefix); offset += prefix } else offset++
    }
    if (starts.isEmpty()) return emptyList()
    return starts.mapIndexed { index, (position, prefix) ->
      data.copyOfRange(position + prefix, starts.getOrNull(index + 1)?.first ?: data.size)
    }
  }

  private fun releaseResource(action: () -> Unit) {
    try { action() } catch (_: Exception) { cleanupFailed.set(true) }
  }

  private fun awaitLatch(latch: CountDownLatch, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (latch.count > 0L && System.nanoTime() < deadline) {
      try {
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(1L)
        if (latch.await(remaining, TimeUnit.NANOSECONDS)) return true
      } catch (_: InterruptedException) {
        // Continue cleanup after an expected stop interrupt, within the same deadline.
      }
    }
    return latch.count == 0L
  }

  private fun closeCaptureResources() {
    releaseResource { cameraSession.get()?.close() }
    releaseResource { camera.get()?.close() }
  }

  private fun joinCallbackThread(thread: HandlerThread) {
    var interrupted = false
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800)
    while (thread.isAlive && System.nanoTime() < deadline) {
      try { thread.join(100) } catch (_: InterruptedException) { interrupted = true }
    }
    if (thread.isAlive) {
      thread.quit()
      cleanupFailed.set(true)
    }
    if (interrupted) Thread.currentThread().interrupt()
  }

  /** Call off Main. A failed bounded stop is surfaced so the owner blocks another test. */
  override fun close() {
    stopping.set(true)
    val thread = synchronized(lifecycle) { worker }
    if (thread == null) { status.set("STOPPED"); return }
    thread.interrupt()
    LockSupport.unpark(thread)
    // Framework camera close can block. It belongs to worker finally, never this
    // caller's bounded wait; an unresponsive worker is reported as unconfirmed cleanup.
    if (thread === Thread.currentThread()) return
    var interrupted = false
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MS)
    while (thread.isAlive && System.nanoTime() < deadline) {
      val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
      try { thread.join(remaining) } catch (_: InterruptedException) { interrupted = true }
    }
    if (interrupted) Thread.currentThread().interrupt()
    if (thread.isAlive || cleanupFailed.get()) throw IllegalStateException("CAMERA_CLEANUP_FAILED")
  }
}
