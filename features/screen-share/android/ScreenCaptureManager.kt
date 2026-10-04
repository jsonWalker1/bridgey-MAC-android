package dev.bridgey.android

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ScreenCapture"

/**
 * M2: adapts the verified ~/screen-poc-android capture+encode pipeline (MediaProjection ->
 * zero-copy Surface -> hardware MediaCodec H.264 encoder) onto the frozen M1 VideoChannelManager/
 * VideoTransport, in place of the PoC's raw Socket. Same encoder parameters as the PoC (COLOR_
 * FormatSurface, 6 Mbps, 30 fps, 2s I-frame interval, half display resolution) - functional parity
 * first, no tuning. Owns its own dedicated thread (frozen M1 threading requirement): the encoder's
 * dequeue loop never touches the shared CoroutineScope and never blocks on network I/O, since
 * VideoChannelManager.sendVideoFrame() is a non-blocking bounded-queue enqueue.
 */
internal class ScreenCaptureManager(
    private val context: Context,
    private val videoChannel: VideoChannelManager,
) {
    private val mutableActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = mutableActive.asStateFlow()

    @Volatile private var running = false
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var lastConfigFrame: EncodedVideoFrame? = null
    @Volatile private var currentWidth = 0
    @Volatile private var currentHeight = 0
    /** Set by displayListener (main thread), consumed only on the pipeline thread (see runPipeline)
     *  so the encoder/VirtualDisplay swap never races the dequeue loop that owns them. */
    @Volatile private var pendingResize: IntArray? = null
    /** Set when a reconfigure completes, cleared once the first post-reconfigure frame is actually
     *  sent - lets the main loop log how long video was interrupted for, per the orientation
     *  robustness logging requirement. 0 means "no reconfigure pending completion". */
    @Volatile private var reconfigureCompletedAtMs = 0L
    private var pipelineThread: Thread? = null

    // BRIDGEY VIDEO REGRESSION WAKELOCK EXPERIMENT (2026-09-24): hypothesis, not yet confirmed - the
    // phone's screen timing out/locking during an otherwise-idle Screen Share session destabilizes
    // the pairing TCP connection (Wi-Fi power-save/Doze transitions tied to screen state, already
    // observed independently this session with other triggers). SCREEN_DIM_WAKE_LOCK, not
    // PARTIAL_WAKE_LOCK: PARTIAL only keeps the CPU running and explicitly still allows the screen to
    // turn off and lock - exactly the device state this experiment needs to rule out, not permit.
    // SCREEN_DIM_WAKE_LOCK is deprecated (API 17) in favor of Activity-level FLAG_KEEP_SCREEN_ON, but
    // this capture session is owned by a bare Service (ScreenCaptureService) with no guaranteed
    // foreground Activity/window to attach that flag to, so the deprecated PowerManager API remains
    // the only mechanism that keeps the screen on (dimmed, not full brightness) from here. Scope is
    // strictly this file: acquire/release only, no other Screen Share/KVM/video/reconnect behavior
    // touched.
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return
        val lock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Bridgey:ScreenShare")
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
            .onSuccess {
                wakeLock = lock
                Log.i(TAG, "WakeLock acquired")
            }
            .onFailure { Log.e(TAG, "WakeLock acquire failed: ${it.message}") }
    }

    private fun releaseWakeLockIfNeeded() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) {
            runCatching { lock.release() }
            Log.i(TAG, "WakeLock released")
        }
    }

    private fun orientationLabel(width: Int, height: Int) = if (width >= height) "landscape" else "portrait"

    private val displayManager by lazy { context.getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            val (width, height) = currentEncodeDimensions()
            if (width != currentWidth || height != currentHeight) pendingResize = intArrayOf(width, height)
        }
    }

    init {
        videoChannel.onVideoFrame = { frame ->
            if (frame.type == VideoFrameType.KEYFRAME_REQUEST) resendConfigAndRequestKeyframe()
        }
    }

    /** Same halving + even-alignment the PoC/M2 baseline always used, just re-derived from the
     *  CURRENT display bounds instead of the ones passed in at start() - so a rotation is reflected
     *  even though MediaProjection itself never tells us about it directly. */
    private fun currentEncodeDimensions(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        }
        val width = (metrics.widthPixels / 2) and 0xFFFFFFFE.toInt()
        val height = (metrics.heightPixels / 2) and 0xFFFFFFFE.toInt()
        return width to height
    }

    /** Called once, right after MediaProjectionManager.createScreenCaptureIntent() was granted. */
    fun start(projectionManager: MediaProjectionManager, resultCode: Int, data: Intent, displayWidthPx: Int, displayHeightPx: Int, densityDpi: Int) {
        if (running) return
        Log.i(TAG, "Screen Share started")
        val proj = projectionManager.getMediaProjection(resultCode, data) ?: return
        projection = proj
        acquireWakeLockIfNeeded()
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system")
                stop()
            }
        }, Handler(Looper.getMainLooper()))

        // Capture at half resolution, even-aligned - identical to the verified PoC.
        val width = (displayWidthPx / 2) and 0xFFFFFFFE.toInt()
        val height = (displayHeightPx / 2) and 0xFFFFFFFE.toInt()

        running = true
        mutableActive.value = true
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        pipelineThread = Thread({ runPipeline(proj, width, height, densityDpi) }, "bridgey-screen-encode").apply {
            isDaemon = true
            start()
        }
    }

    /** Only ever flips `running` off and tears down what's safe to touch from any thread - the
     *  encoder/VirtualDisplay/projection themselves are released on the pipeline thread's own exit
     *  path (see runPipeline), never here. `stop()` is called from wherever asks for it (a UI tap,
     *  MediaProjection's own system callback, or now also a Remote Start peer's screenshare.
     *  remoteStop arriving on PairingCoordinator's IO thread) - MediaCodec is not safe to
     *  stop()/release() concurrently with dequeueOutputBuffer()/releaseOutputBuffer() being called
     *  on it from a different thread, which is exactly the crash this used to be able to hit. */
    fun stop() {
        // Released unconditionally, ahead of the `running` guard below (not gated by it) - stop() is
        // called from several independent places (UI tap, MediaProjection.Callback.onStop(), a Remote
        // Stop message) and may run more than once for the same session; releaseWakeLockIfNeeded()
        // is itself idempotent, so this guarantees the lock never outlives the session regardless of
        // which caller's stop() actually "wins" the running-guard below.
        releaseWakeLockIfNeeded()
        if (!running) return
        running = false
        Log.i(TAG, "Screen Share stopped")
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        pendingResize = null
        reconfigureCompletedAtMs = 0L
        videoChannel.stopVideo()
        mutableActive.value = false
        Log.i(TAG, "capture stop requested")
    }

    /** Builds a MediaCodec encoder configured at the given dimensions plus the Surface it wants fed,
     *  without touching any VirtualDisplay. Shared by the initial setup (which additionally creates
     *  the one-and-only VirtualDisplay around this Surface) and orientation reconfigure (which
     *  instead re-points the EXISTING VirtualDisplay at this new Surface - see reconfigureEncoder). */
    private fun configureEncoder(width: Int, height: Int): Pair<MediaCodec, android.view.Surface> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // BRIDGEY VIDEO STUTTER FIX (2026-09-24): KEY_FRAME_RATE above is only a bitrate-control
            // hint - it does NOT throttle how often SurfaceFlinger actually feeds this Surface-input
            // encoder. Real-hardware logs showed bursts up to 121 fps during fast scrolling/animation
            // (vs. the 30 fps target), instantly overflowing TcpVideoTransport's 8-frame send queue and
            // forcing it to evict DELTA frames - since a keyframe only comes every KEY_I_FRAME_INTERVAL
            // (2s), the decoder was stuck on stale content until the next one, reading as bad stutter.
            // KEY_MAX_FPS_TO_ENCODER (API 31+) is the real fix for exactly this Surface-input scenario:
            // frames arriving faster than this are dropped by the platform BEFORE they ever reach the
            // encoder or our send queue, so a burst degrades to "capped at 60fps" instead of "queue
            // overflow drops frames essentially at random relative to the network's actual capacity."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 60)
            }
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = codec.createInputSurface()
        codec.start()
        return codec to inputSurface
    }

    /** Called once, at pipeline startup only: creates the encoder AND the one-and-only
     *  VirtualDisplay for this MediaProjection instance. Modern Android (verified on this device)
     *  hard-rejects a second MediaProjection#createVirtualDisplay call on the same token - even
     *  after releasing the first VirtualDisplay - and revokes the whole projection when that
     *  happens, so this must never run more than once per session. */
    private fun configureEncoderAndDisplay(proj: MediaProjection, width: Int, height: Int, dpi: Int): MediaCodec {
        val (codec, inputSurface) = configureEncoder(width, height)
        virtualDisplay = proj.createVirtualDisplay(
            "BridgeyScreen", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )
        currentWidth = width
        currentHeight = height
        return codec
    }

    /** Orientation reconfigure path: builds a new encoder at the new dimensions, then resizes and
     *  re-surfaces the SAME VirtualDisplay created at startup (VirtualDisplay.resize +
     *  VirtualDisplay.setSurface are public APIs for exactly this) instead of ever creating a new
     *  VirtualDisplay - see configureEncoderAndDisplay's doc comment for why. */
    private fun reconfigureEncoder(width: Int, height: Int, dpi: Int): MediaCodec {
        val (codec, inputSurface) = configureEncoder(width, height)
        virtualDisplay?.resize(width, height, dpi)
        virtualDisplay?.setSurface(inputSurface)
        currentWidth = width
        currentHeight = height
        return codec
    }

    /** Runs on the pipeline thread only, at the top of the dequeue loop - the only place the
     *  encoder/VirtualDisplay are ever touched, so this can never race a concurrent dequeue call.
     *  Builds the replacement encoder BEFORE releasing the original, so a failed reconfigure
     *  (encoder busy, transient resource shortage, etc.) leaves the still-working original running
     *  untouched rather than killing the stream. Only the encoder is ever recreated - the
     *  VirtualDisplay is reused via resize()/setSurface() for the whole capture session's lifetime
     *  (see reconfigureEncoder's doc comment for why a second createVirtualDisplay call is unsafe). */
    private fun applyPendingResizeIfNeeded(dpi: Int, codec: MediaCodec): MediaCodec {
        val (newWidth, newHeight) = pendingResize ?: return codec
        pendingResize = null
        if (newWidth == currentWidth && newHeight == currentHeight) return codec
        val oldWidth = currentWidth
        val oldHeight = currentHeight
        val oldCodec = encoder
        val videoChannelStateBefore = videoChannel.currentVideoState
        Log.i(
            TAG,
            "orientation change detected: ${orientationLabel(oldWidth, oldHeight)} (${oldWidth}x$oldHeight) -> " +
                "${orientationLabel(newWidth, newHeight)} (${newWidth}x$newHeight); " +
                "projectionAlive=${projection != null} videoChannelState=$videoChannelStateBefore",
        )
        val newCodec = try {
            reconfigureEncoder(newWidth, newHeight, dpi)
        } catch (t: Throwable) {
            Log.e(TAG, "orientation reconfigure failed, keeping previous encoder untouched: ${t.message}")
            return codec
        }
        runCatching { oldCodec?.stop() }
        runCatching { oldCodec?.release() }
        encoder = newCodec
        lastConfigFrame = null
        reconfigureCompletedAtMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "orientation change applied: encoder recreated, VirtualDisplay resized+re-surfaced in place " +
                "(same MediaProjection token, no TCP reconnect, no channel renegotiation - videoChannelState " +
                "now ${videoChannel.currentVideoState}); waiting for first post-reconfigure CONFIG frame to " +
                "measure recovery time",
        )
        return newCodec
    }

    private fun runPipeline(proj: MediaProjection, width: Int, height: Int, dpi: Int) {
        if (!negotiateChannel(width, height)) {
            Log.e(TAG, "video channel did not become active in time")
            stop()
            return
        }

        Log.i(TAG, "resolution: ${width}x$height")
        var codec = try {
            configureEncoderAndDisplay(proj, width, height, dpi)
        } catch (t: Throwable) {
            Log.e(TAG, "encoder configure failed: ${t.message}")
            stop()
            return
        }
        encoder = codec
        Log.i(TAG, "codec: ${codec.name}")

        val bufferInfo = MediaCodec.BufferInfo()
        var lastReconnectAttempt = 0L
        while (running) {
            if (videoChannel.currentVideoState == ChannelState.IDLE && System.currentTimeMillis() - lastReconnectAttempt > 2_000) {
                lastReconnectAttempt = System.currentTimeMillis()
                videoChannel.offerVideo("android_to_mac", width, height, bitrateKbps = 6_000, fps = 30)
            }
            codec = applyPendingResizeIfNeeded(dpi, codec)
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (t: Throwable) {
                Log.e(TAG, "dequeueOutputBuffer failed: ${t.message}")
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.i(TAG, "encoder output format changed: ${codec.outputFormat}")
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val type = when {
                            isConfig -> VideoFrameType.CONFIG
                            isKeyFrame -> VideoFrameType.KEYFRAME
                            else -> VideoFrameType.DELTA
                        }
                        val frame = EncodedVideoFrame(type, streamId = 0, captureTimestampMs = System.currentTimeMillis(), payload = bytes)
                        if (type == VideoFrameType.CONFIG) {
                            lastConfigFrame = frame
                            if (reconfigureCompletedAtMs != 0L) {
                                val recoveryMs = System.currentTimeMillis() - reconfigureCompletedAtMs
                                Log.i(TAG, "video resumed after orientation reconfigure in ${recoveryMs}ms")
                                reconfigureCompletedAtMs = 0L
                            }
                        }
                        videoChannel.sendVideoFrame(frame, droppable = type == VideoFrameType.DELTA)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        // The only place the encoder/VirtualDisplay/projection are ever stopped/released - see
        // stop()'s doc comment for why this must happen here, on the same thread that just called
        // dequeueOutputBuffer()/releaseOutputBuffer() on this exact encoder, rather than from
        // whatever thread called stop().
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { proj.stop() }
        encoder = null
        virtualDisplay = null
        projection = null
        lastConfigFrame = null
        currentWidth = 0
        currentHeight = 0
        Log.i(TAG, "encode loop ended")
    }

    /** Blocks (on this dedicated pipeline thread only, never the caller of start()) until the video
     * channel reaches ACTIVE, so the encoder's first CONFIG buffer is never produced before there is
     * an active channel to carry it. */
    private fun negotiateChannel(width: Int, height: Int): Boolean {
        videoChannel.offerVideo("android_to_mac", width, height, bitrateKbps = 6_000, fps = 30)
        val deadline = System.currentTimeMillis() + 10_000
        while (running && videoChannel.currentVideoState != ChannelState.ACTIVE && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return running && videoChannel.currentVideoState == ChannelState.ACTIVE
    }

    /** Mac asks for this (VideoFrameType.KEYFRAME_REQUEST) right after a fresh channel establishes
     * or whenever it cannot decode what it has received - MediaCodec only emits its codec-config
     * buffer once at encoder startup, so a reconnect must explicitly resend the cached one. */
    private fun resendConfigAndRequestKeyframe() {
        lastConfigFrame?.let { videoChannel.sendVideoFrame(it, droppable = false) }
        val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
        runCatching { encoder?.setParameters(params) }
    }
}
