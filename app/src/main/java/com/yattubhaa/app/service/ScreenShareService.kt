package com.yattubhaa.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

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
 */
class ScreenShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var thread: HandlerThread? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: SessionOverlay? = null
    private var lastFrameAt = 0L
    private var lastJpeg: ByteArray? = null
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
        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { reader = it }
        display = mp.createVirtualDisplay(
            "yattu-share", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler,
        )
        imageReader.setOnImageAvailableListener({ onFrame(session, it, w, h) }, handler)
        // A perfectly still screen produces no new pictures, so resend the last one now and then.
        handler.post(object : Runnable {
            override fun run() {
                val jpeg = lastJpeg
                val now = SystemClock.elapsedRealtime()
                if (jpeg != null && now - lastSentAt >= KEEPALIVE_MS) {
                    lastSentAt = now
                    session.sendFrame(w, h, jpeg)
                }
                handler.postDelayed(this, KEEPALIVE_MS)
            }
        })

        overlay = SessionOverlay(this) {
            SessionHub.endNeedy("You stopped sharing.")
            shutdown()
        }.also { it.show() }
        ShareState.set(true)
        session.sharing = true

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
            ControlState.Blocked -> overlay?.setBanner("Taps are paused while a bank or payment app is open.")
            ControlState.Unavailable -> overlay?.hideQuestion()
            ControlState.Off -> {
                overlay?.hideQuestion()
                overlay?.setBanner(null)
            }
        }
    }

    private fun allowControl(session: NeedySession) {
        ControlCapability.setOffered(this, true)
        scope.launch {
            // If he switched it on in Settings before, Android reconnects it a moment after it is offered.
            withTimeoutOrNull(3000) { RemoteInput.connected.first { it } }
            val available = RemoteInput.isAvailable
            session.acceptControl()
            if (!available) sendToAccessibilitySettings()
        }
    }

    /**
     * Android ignores taps on its "allow full control" dialog while any other app is drawing over
     * it, and our red Stop button and pointer layer are exactly that. So they are lifted while he
     * is in Settings, and put back once the setting is on (or after a couple of minutes).
     */
    private fun sendToAccessibilitySettings() {
        overlay?.remove()
        ControlCapability.openAccessibilitySettings(this)
        scope.launch {
            withTimeoutOrNull(SETTINGS_TRIP_MS) { RemoteInput.connected.first { it } }
            if (ShareState.active.value) overlay?.show()
        }
    }

    private fun onFrame(session: NeedySession, r: ImageReader, w: Int, h: Int) {
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (rowPixels == w) padded else Bitmap.createBitmap(padded, 0, 0, w, h)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val jpeg = out.toByteArray()
            lastJpeg = jpeg
            lastSentAt = now
            session.sendFrame(w, h, jpeg)
        } finally {
            image.close()
        }
    }

    private fun shutdown() {
        main.post {
            scope.cancel()
            overlay?.remove(); overlay = null
            display?.release(); display = null
            reader?.close(); reader = null
            projection?.stop(); projection = null
            thread?.quitSafely(); thread = null
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
        private const val TARGET_WIDTH = 540f
        private const val JPEG_QUALITY = 50
        private const val FRAME_INTERVAL_MS = 250L // about four pictures a second
        private const val KEEPALIVE_MS = 2000L
        private const val SETTINGS_TRIP_MS = 2 * 60 * 1000L

        /** [resultCode] and [data] are what Android's screen-capture consent dialog returned. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
