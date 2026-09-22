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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yattubhaa.app.R
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.Protocol
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
 * The screen is captured straight into a hardware H.264 encoder (see [ScreenEncoder]): whatever
 * the display compositor draws goes directly to the encoder's input surface, with no CPU bitmap
 * copy and no per-frame JPEG encode in between, the way an earlier version of this worked. A
 * still screen produces no new compositor frames at all (this is how screen mirroring generally
 * works, not a bug), so the last keyframe is resent every few seconds regardless, both so a
 * freshly connected or reconnected helper has something to decode promptly, and as a safety net.
 */
class ScreenShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var thread: HandlerThread? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var overlay: SessionOverlay? = null
    private var lastKeyframe: ByteArray? = null
    private var lastKeyframeSize = 0 to 0
    private var lastSentAt = 0L

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

        val metrics = resources.displayMetrics
        val scale = minOf(1f, TARGET_WIDTH / metrics.widthPixels.toFloat())
        val w = (metrics.widthPixels * scale).toInt() and 1.inv()
        val h = (metrics.heightPixels * scale).toInt() and 1.inv()
        val worker = HandlerThread("yattu-capture").also { it.start(); thread = it }
        val handler = Handler(worker.looper)

        val enc = try {
            ScreenEncoder(w, h, handler) { keyframe, cw, ch, bytes -> onChunk(session, keyframe, cw, ch, bytes) }
        } catch (e: Exception) {
            android.util.Log.e("ScreenShareService", "Encoder setup failed for ${w}x$h", e)
            SessionHub.endNeedy("Could not start sharing on this phone.")
            shutdown()
            return
        }
        encoder = enc
        enc.start()
        display = mp.createVirtualDisplay(
            "yattu-share", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.inputSurface, null, handler,
        )
        // A screen that never changes produces no new compositor frames, and hence nothing new
        // to encode. Resending the last keyframe periodically means a freshly connected or
        // reconnected helper — and one who missed a chunk to a network blip — is never left
        // looking at nothing for long, without needing to hook into presence events to do it.
        handler.postDelayed(object : Runnable {
            override fun run() {
                val jpeg = lastKeyframe
                val now = SystemClock.elapsedRealtime()
                if (jpeg != null && now - lastSentAt >= KEEPALIVE_MS) {
                    val (kw, kh) = lastKeyframeSize
                    lastSentAt = now
                    session.sendVideoChunk(true, kw, kh, jpeg)
                }
                handler.postDelayed(this, KEEPALIVE_MS)
            }
        }, KEEPALIVE_MS)

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

    private fun onChunk(session: NeedySession, keyframe: Boolean, w: Int, h: Int, bytes: ByteArray) {
        if (keyframe) { lastKeyframe = bytes; lastKeyframeSize = w to h }
        lastSentAt = SystemClock.elapsedRealtime()
        session.sendVideoChunk(keyframe, w, h, bytes)
    }

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

    private fun allowControl(session: NeedySession) {
        ControlCapability.setOffered(this, true)
        val grantedBefore = Prefs.accessibilityGrantedSinceLastOff
        scope.launch {
            // If he has switched it on before, Android just needs a moment to reconnect the
            // service (longer on an older phone), so wait a bit longer before concluding it is
            // genuinely gone — a slow reconnect should not feel the same as never granting it.
            val waitMs = if (grantedBefore) RECONNECT_WAIT_MS else FIRST_TIME_WAIT_MS
            withTimeoutOrNull(waitMs) { RemoteInput.connected.first { it } }
            val available = RemoteInput.isAvailable
            session.acceptControl()
            // Still not there after waiting: send him to turn it on, whether this is the first
            // time or Android lost track of an earlier grant (it can: the system can revoke an
            // accessibility service on its own, not only through "Turn off remote control").
            // This used to be skipped for a return visit, back when it cost the overlay something
            // to do this; now that sending him to Settings has no such cost any more, there is no
            // reason to ever leave him stuck on "needs a setting" with no way to actually get there.
            if (!available) sendToAccessibilitySettings()
        }
    }

    /**
     * Sends him to the one Settings screen this app cannot avoid: turning the accessibility
     * switch on for the first time, or again after "Turn off remote control". Nothing here
     * touches the overlay any more. It used to be removed first, from an earlier, wrong
     * assumption that Android would otherwise ignore the tap on "Allow" — tested since: Android
     * already hides every overlay window, ours included, for as long as its own Settings app is
     * in front (see docs/SECURITY.md), which already covers this exact dialog, and restores them
     * the instant he leaves Settings. Removing our own overlay on top of that was pure redundant
     * downtime for the Stop button and the pointer ring, for no benefit.
     */
    private fun sendToAccessibilitySettings() = ControlCapability.openAccessibilitySettings(this)

    private fun shutdown() {
        main.post {
            scope.cancel()
            overlay?.remove(); overlay = null
            display?.release(); display = null
            encoder?.release(); encoder = null
            projection?.stop(); projection = null
            thread?.quitSafely(); thread = null
            lastKeyframe = null
            ShareState.set(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        private const val ACTION_STOP = "com.yattubhaa.app.STOP_SHARING"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1
        private const val TARGET_WIDTH = 720f
        // How long the picture can go quiet before the last keyframe is resent — this is what
        // lets a helper whose first keyframe was missed (its SurfaceView not ready yet) catch up,
        // without waiting on the screen to genuinely change again. Kept short: a resend only ever
        // happens when nothing else has been sent for this long anyway, so a shorter interval
        // costs bandwidth only in exactly the situations where catching up quickly matters.
        private const val KEEPALIVE_MS = 1000L
        private const val FIRST_TIME_WAIT_MS = 3000L
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
