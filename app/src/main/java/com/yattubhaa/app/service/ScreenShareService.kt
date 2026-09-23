package com.yattubhaa.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yattubhaa.app.R
import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.VideoCodec
import com.yattubhaa.app.session.NeedyPhase
import com.yattubhaa.app.session.NeedySession
import com.yattubhaa.app.session.SessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Whether a screen is being shared right now, for the screens that need to show it. */
object ShareState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    internal fun set(value: Boolean) { _active.value = value }
}

/**
 * Sends a picture of the screen to the helper, for the length of one session only. Runs as a
 * visible foreground service, and shuts everything down (capture, overlay, notification) the
 * moment the session ends or either person taps Stop.
 *
 * The screen is captured straight into a hardware video encoder (see [ScreenEncoder]). Frames are
 * numbered and timestamped, and the helper reports back four times a second on what actually
 * arrived and how late. From that, [SendGate] pauses the capture whenever the link is backed up
 * (rather than throwing away encoded frames, which corrupts the picture), [CongestionController]
 * sets the bitrate, the picture size and the frame rate, and [OvershootCorrector] makes the
 * encoder's real output match the bitrate asked for. The helper asks for a keyframe whenever it
 * needs one (a missed frame, a decoder just created), rather than this phone guessing.
 *
 * All the video work happens on one "capture" thread — the encoder's callbacks, the helper's
 * reports, the once-a-second sample — so none of it needs locking, and none of it (in particular
 * releasing an encoder, which can take seconds on some phones) ever runs on the main thread.
 */
class ScreenShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: SessionOverlay? = null
    private var thread: HandlerThread? = null
    private var capture: Handler? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile private var projection: MediaProjection? = null
    private var shuttingDown = false

    // Owned by the capture thread once sharing has started.
    private var display: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    /** Bumped for every encoder created, so late output from a replaced one is ignored. */
    private var generation = 0
    private var codec = VideoCodec.Avc
    private var hevcFailed = false
    private var encoderFailures = 0
    private var densityDpi = 0
    private var controller: CongestionController? = null
    private var gate: SendGate? = null
    private val sendTracker = SendTracker()
    private val corrector = OvershootCorrector()
    private var lastKeyframeSentAt = 0L
    private var lastForcedKeyframeAt = 0L
    private var droppedFrames = 0
    // What the encoder produced since the last sample, and for how much of that time it was
    // actually running (not paused), for the OvershootCorrector.
    private var producedBytes = 0L
    private var pausedMs = 0L
    private var pausedAt: Long? = null
    private var windowStart = 0L
    private var keyframeSentInWindow = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SessionHub.endNeedy("You stopped sharing.")
            shutdown()
            return START_NOT_STICKY
        }
        val session = SessionHub.needy
        val data = intent?.let { if (android.os.Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") it.getParcelableExtra(EXTRA_DATA) }
        if (session == null || session.ended || data == null || projection != null) {
            if (projection == null) shutdown()
            return START_NOT_STICKY
        }
        val name = session.state.value.helperName
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        startCapture(session, intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
        return START_NOT_STICKY
    }

    /**
     * The app being swiped away from Recents does **not** stop a foreground service on its own —
     * deliberate Android behaviour, the same that lets a music player keep playing. Left alone,
     * an accidental swipe-away would keep sharing his screen invisibly, and the helper would only
     * find out once the relay noticed a dead connection. This sends a proper "stopped" message
     * while the connection is still open, the same as tapping Stop.
     *
     * An earlier version then blocked here for 400ms, on the theory that the process could be
     * killed before that message left. Measured since: the message goes out within a couple of
     * milliseconds, and the process lives on well past this point anyway (it is still running a
     * foreground service when this is called). The slow notice that theory was meant to explain
     * turned out to be the helper's own decoder teardown blocking its UI, fixed separately.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        SessionHub.endNeedy("You closed the app.")
        shutdown()
    }

    private fun startCapture(session: NeedySession, resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data)
        projection = mp
        // Required from Android 14: a callback must exist before the display is created.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                SessionHub.endNeedy("Screen sharing was stopped.")
                shutdown()
            }
        }, main)

        val worker = HandlerThread("yattu-capture").also { it.start(); thread = it }
        val handler = Handler(worker.looper)
        capture = handler
        handler.post { beginCapture(session, mp, handler) }

        overlay = SessionOverlay(this) {
            SessionHub.endNeedy("You stopped sharing.")
            shutdown()
        }.also { it.show() }
        ShareState.set(true)
        session.sharing = true
        session.notifySharingStarted()

        scope.launch {
            session.state.collect { if (it.phase == NeedyPhase.Ended) shutdown() }
        }
        scope.launch {
            session.incoming.collect { m -> if (m is Protocol.Message.Pointer) overlay?.point(m.x, m.y) }
        }
        scope.launch {
            session.state.map { it.controlState }.distinctUntilChanged().collect { onControlState(session, it) }
        }
    }

    // ---- Everything from here to shutdown() runs on the capture thread. ----

    private fun beginCapture(session: NeedySession, mp: MediaProjection, handler: Handler) {
        val c = CongestionController(
            onBitrateChanged = { encoder?.setBitrate(encoderBitrate()) },
            onTierChanged = { restartEncoder(session, handler, "quality step") },
        )
        controller = c
        gate = SendGate(
            requestKeyframe = { forceKeyframe(KEYFRAME_MIN_GAP_MS) },
            setSourcePaused = { paused -> setSourcePaused(paused) },
        )
        windowStart = SystemClock.elapsedRealtime()
        densityDpi = ScreenSize.real(this).densityDpi
        val (chosen, size) = plan(session, c.tier)
        codec = chosen
        val (w, h) = size
        val enc = createEncoder(session, handler, w, h) ?: return fail("Could not start sharing on this phone.")
        encoder = enc
        display = try {
            mp.createVirtualDisplay(
                "yattu-share", w, h, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.inputSurface, null, handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "could not create the capture display", e)
            return fail("Could not start sharing on this phone.")
        }
        session.videoFeedback = { m -> handler.post { onFeedback(session, handler, m) } }
        session.onReconnected = { handler.post { sendTracker.onReconnected(SystemClock.elapsedRealtime()) } }

        // Turning the phone round changes the screen's shape: capture at the new shape rather
        // than squeeze a sideways picture into an upright frame.
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val current = encoder ?: return
                val tier = controller?.tier ?: return
                if (plan(session, tier).second != current.width to current.height) restartEncoder(session, handler, "screen turned")
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(listener, handler)
        main.post { if (shuttingDown) unregister(listener) else displayListener = listener }

        handler.postDelayed(object : Runnable {
            var ticks = 0
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                pausedAt?.let { pausedMs += now - it; pausedAt = now }
                val window = (now - windowStart).coerceAtLeast(1)
                controller?.onSample(
                    sendTracker.sample(now, session.outgoingBacklogBytes()).copy(
                        pausedFraction = pausedMs.toDouble() / window,
                        keyframeSent = keyframeSentInWindow,
                    ),
                )
                keyframeSentInWindow = false
                correctOvershoot(now)
                ticks++
                if (ticks % STATS_EVERY_SAMPLES == 0) sendStats(session)
                if (ticks % LOG_EVERY_SAMPLES == 0) logStats()
                handler.postDelayed(this, SAMPLE_MS)
            }
        }, SAMPLE_MS)
        // While capture is paused no frames arrive to prompt the gate, so it is also asked
        // several times a second whether the link has room again.
        handler.postDelayed(object : Runnable {
            override fun run() {
                recheckGate(session)
                handler.postDelayed(this, RECHECK_MS)
            }
        }, RECHECK_MS)
    }

    private fun recheckGate(session: NeedySession) {
        val c = controller ?: return
        val now = SystemClock.elapsedRealtime()
        sendTracker.giveUpOnLostFrames(now)
        gate?.check(session.outgoingBacklogBytes(), c.bitrate, sendTracker.pathClear(now, c.bitrate), now)
    }

    private fun setSourcePaused(paused: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (paused) {
            pausedAt = now
        } else {
            pausedAt?.let { pausedMs += now - it }
            pausedAt = null
        }
        encoder?.setPaused(paused)
    }

    /** The bitrate actually handed to the encoder: the controller's, scaled for overshoot. */
    private fun encoderBitrate(): Int = ((controller?.bitrate ?: 0) * corrector.factor).toInt()

    /** Also closes the sample window: call once per sample, after the controller has seen it. */
    private fun correctOvershoot(now: Long) {
        val c = controller ?: return
        val before = encoderBitrate()
        corrector.onSample(producedBytes, activeMs = (now - windowStart) - pausedMs, targetBps = c.bitrate)
        producedBytes = 0
        pausedMs = 0
        windowStart = now
        val after = encoderBitrate()
        if (after != before) encoder?.setBitrate(after)
    }

    /** Tries the chosen codec, falling back to H.264 if H.265 will not start on this phone. */
    private fun createEncoder(session: NeedySession, handler: Handler, w: Int, h: Int): ScreenEncoder? {
        val c = controller ?: return null
        for (attempt in listOf(codec, VideoCodec.Avc).distinct()) {
            val gen = ++generation
            try {
                val enc = ScreenEncoder(
                    attempt, w, h, encoderBitrate(), c.tier.maxFps, handler,
                    onChunk = { keyframe, bytes -> if (gen == generation) onChunk(session, keyframe, bytes) },
                    onFailed = { handler.post { if (gen == generation) onEncoderFailed(session, handler) } },
                )
                enc.start()
                if (gate?.sourcePaused == true) enc.setPaused(true)
                codec = attempt
                Log.i(TAG, "encoding ${attempt.label} ${w}x$h up to ${c.tier.maxFps}fps at ${encoderBitrate()}bps, setup level ${enc.setupLevel}")
                return enc
            } catch (e: Exception) {
                Log.e(TAG, "${attempt.label} encoder setup failed for ${w}x$h", e)
                if (attempt == VideoCodec.Hevc) hevcFailed = true
            }
        }
        return null
    }

    /**
     * Swaps in a fresh encoder at the current tier's size and frame rate (or the new shape after
     * rotation, or another codec), without touching the capture itself: the virtual display is
     * pointed at the new encoder's surface and the old one released. The new encoder's first
     * frame is a keyframe, and the helper's decoder restarts on its own when the size changes.
     */
    private fun restartEncoder(session: NeedySession, handler: Handler, reason: String) {
        val d = display ?: return
        val c = controller ?: return
        val (chosen, size) = plan(session, c.tier)
        codec = chosen
        val (w, h) = size
        Log.i(TAG, "restarting the encoder: $reason")
        val next = createEncoder(session, handler, w, h) ?: return fail("Could not keep sharing on this phone.")
        val old = encoder
        encoder = next
        d.surface = null
        d.resize(w, h, densityDpi)
        d.surface = next.inputSurface
        old?.release()
    }

    private fun onEncoderFailed(session: NeedySession, handler: Handler) {
        if (encoder?.codec == VideoCodec.Hevc) hevcFailed = true
        if (++encoderFailures > MAX_ENCODER_FAILURES) return fail("Could not keep sharing on this phone.")
        restartEncoder(session, handler, "encoder error")
    }

    private fun onChunk(session: NeedySession, keyframe: Boolean, bytes: ByteArray) {
        val enc = encoder ?: return
        val c = controller ?: return
        val g = gate ?: return
        val now = SystemClock.elapsedRealtime()
        producedBytes += bytes.size
        if (!g.shouldSend(keyframe, session.outgoingBacklogBytes(), c.bitrate, sendTracker.pathClear(now, c.bitrate), now)) {
            droppedFrames++
            return
        }
        val seq = sendTracker.nextSeq
        if (session.sendVideoFrame(keyframe, enc.codec, enc.width, enc.height, seq, now.toInt(), bytes)) {
            sendTracker.onSent(seq, now, bytes.size)
            if (keyframe) {
                lastKeyframeSentAt = now
                keyframeSentInWindow = true
            }
        } else {
            g.onSendFailed()
        }
    }

    private fun onFeedback(session: NeedySession, handler: Handler, m: Protocol.Message) {
        val now = SystemClock.elapsedRealtime()
        when (m) {
            is Protocol.Message.ReceiverReport -> {
                sendTracker.onReport(m, now)
                recheckGate(session)
            }
            Protocol.Message.KeyframeRequest -> {
                // It may have asked before a keyframe already on its way got there; a round trip
                // after the last one went out, it has had time to arrive.
                val minGap = maxOf(KEYFRAME_MIN_GAP_MS, (sendTracker.rttMs ?: 0) + 250L)
                if (now - lastKeyframeSentAt >= minGap) forceKeyframe(minGap)
            }
            is Protocol.Message.Decoders -> {
                val tier = controller?.tier ?: return
                if (plan(session, tier).first != encoder?.codec) restartEncoder(session, handler, "codec")
            }
            else -> Unit
        }
    }

    private fun forceKeyframe(minGapMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastForcedKeyframeAt < minGapMs) return
        lastForcedKeyframeAt = now
        encoder?.requestKeyframe()
    }

    private fun sendStats(session: NeedySession) {
        val c = controller ?: return
        val (steps, failed, slowest) = session.takeInputStats()
        session.sendStats(
            Protocol.Message.SenderStats(
                quality = c.quality,
                bitrateKbps = c.bitrate / 1000,
                tier = c.tierIndex,
                rttMs = sendTracker.rttMs ?: 0,
                droppedFrames = droppedFrames,
                encoderSetup = encoder?.setupLevel ?: 0,
                inputSteps = steps,
                inputFailed = failed,
                inputSlowestMs = slowest,
                inputCancelled = RemoteInput.cancelledSteps,
                inputResumed = RemoteInput.resumedDrags,
            ),
        )
    }

    private fun logStats() {
        val c = controller ?: return
        val e = encoder ?: return
        Log.i(
            TAG,
            "stats: ${e.codec.label} ${e.width}x${e.height} tier ${c.tierIndex} ${c.bitrate / 1000}kbps " +
                "(encoder asked for ${encoderBitrate() / 1000}) ${c.quality} rtt ${sendTracker.rttMs}ms " +
                "sent ${sendTracker.nextSeq} dropped $droppedFrames paused ${gate?.sourcePaused}",
        )
    }

    /** The codec to use for this tier and the screen's current shape, and the size to capture
     *  at: the whole screen scaled so its shorter side is the tier's, in multiples of 8 (some
     *  hardware encoders misbehave on sizes that are not), shrunk further only if the encoder
     *  cannot take it (see [VideoCodecs.fitSize]). */
    private fun plan(session: NeedySession, tier: QualityTier): Pair<VideoCodec, Pair<Int, Int>> {
        val m = ScreenSize.real(this)
        val scale = minOf(1.0, tier.shortSide / minOf(m.widthPixels, m.heightPixels).toDouble())
        // Rounded, not truncated: 224/1080 x 1080 comes out as 223.99998 in floating point.
        fun multipleOf8(v: Double) = (v.roundToInt() / 8 * 8).coerceAtLeast(16)
        val w = multipleOf8(m.widthPixels * scale)
        val h = multipleOf8(m.heightPixels * scale)
        val chosen = VideoCodecs.choose(session.helperDecoders, hevcFailed, w, h)
        return chosen to VideoCodecs.fitSize(chosen, w, h)
    }

    private fun fail(message: String) {
        SessionHub.endNeedy(message)
        shutdown()
    }

    // ---- Back on the main thread. ----

    /** Keeps what is on his screen in step with whether the helper may tap for him. */
    private fun onControlState(session: NeedySession, state: ControlState) {
        val name = session.state.value.helperName
        when (state) {
            ControlState.Asked -> overlay?.askQuestion(
                text = "$name would like to tap and swipe on your screen for you.",
                yes = "Allow",
                no = "Not now",
                onYes = { allowControl(session) },
                onNo = { session.declineControl() },
            )
            ControlState.On -> {
                overlay?.hideQuestion()
                overlay?.setBanner("$name can tap on your screen. Tap STOP to end it.")
            }
            ControlState.Blocked ->
                overlay?.setBanner("You're in a secure app, so $name cannot tap or swipe here right now.")
            ControlState.Unavailable -> overlay?.hideQuestion()
            ControlState.Off -> {
                overlay?.hideQuestion()
                overlay?.setBanner(null)
            }
        }
    }

    /**
     * He said yes. Whether he needs sending to Settings is read from Android's own record of the
     * accessibility switch ([ControlCapability.isSwitchedOn]), not guessed from how long the
     * service takes to connect: an earlier version waited a few seconds and, on an older phone
     * slow to reconnect the service, sent him to Settings for a switch that was already on — or,
     * the first time, made him wait three seconds for a trip to Settings that was always needed.
     */
    private fun allowControl(session: NeedySession) {
        ControlCapability.setOffered(this, true)
        scope.launch {
            val switchedOn = ControlCapability.isSwitchedOn(this@ScreenShareService)
            if (switchedOn && !RemoteInput.isAvailable) {
                // On in Settings, and Android is still (re)connecting it: wait for that, not Settings.
                withTimeoutOrNull(RECONNECT_WAIT_MS) { RemoteInput.connected.first { it } }
            }
            // On, or Unavailable for now — which recovers by itself the moment the service
            // connects (see NeedySession.recheckAvailability).
            session.acceptControl()
            if (!switchedOn) ControlCapability.openAccessibilitySettings(this@ScreenShareService)
        }
    }

    private fun unregister(listener: DisplayManager.DisplayListener) {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(listener)
    }

    private fun shutdown() {
        main.post {
            if (shuttingDown) return@post
            shuttingDown = true
            scope.cancel()
            overlay?.remove(); overlay = null
            displayListener?.let { unregister(it) }; displayListener = null
            val mp = projection
            projection = null
            val worker = thread
            val handler = capture
            thread = null
            capture = null
            if (worker != null && handler != null) {
                // Releasing an encoder can take seconds on some phones: never on the main thread.
                handler.post {
                    handler.removeCallbacksAndMessages(null)
                    releaseCapture(mp)
                    worker.quitSafely()
                }
            } else {
                runCatching { mp?.stop() }
            }
            ShareState.set(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** On the capture thread, as the very last thing it does. */
    private fun releaseCapture(mp: MediaProjection?) {
        generation++
        runCatching { display?.release() }
        display = null
        encoder?.release()
        encoder = null
        runCatching { mp?.stop() }
    }

    override fun onDestroy() {
        ShareState.set(false)
        super.onDestroy()
    }

    private fun notification(helperName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$helperName can see your screen")
            .setContentText("Tap Stop to end it.")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "ScreenShare"
        private const val ACTION_STOP = "com.yattubhaa.app.STOP_SHARING"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1
        // How often the connection is judged (CongestionController); the helper reports twice
        // as often, so each sample has fresh information.
        private const val SAMPLE_MS = 1000L
        private const val STATS_EVERY_SAMPLES = 2
        private const val LOG_EVERY_SAMPLES = 5
        private const val RECHECK_MS = 100L
        private const val KEYFRAME_MIN_GAP_MS = 500L
        private const val MAX_ENCODER_FAILURES = 5
        private const val RECONNECT_WAIT_MS = 8000L

        /** [resultCode] and [data] are what Android's screen-capture consent dialog returned. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
