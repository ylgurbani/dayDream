package com.yattubhaa.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yattubhaa.app.MainActivity
import com.yattubhaa.app.R
import com.yattubhaa.app.service.ShareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a help session's connection alive while this app is not on screen, for as long as the
 * session lasts and no longer — with a notification saying so, and a Stop button.
 *
 * Needed because newer Android versions cut the network off from any app that has been in the
 * background for about five seconds without a foreground service, and close its open
 * connections. Found in a real test: on his phone, the trip to Android's own "Display over other
 * apps" screen (before sharing starts, so before the screen-sharing service exists) dropped the
 * session every time. Reproduced on an emulator — Android logged "Destroyed live tcp sockets" for
 * this app five seconds after it left the screen — and the helper's phone was just as exposed:
 * switching to a WhatsApp call mid-session for more than a few seconds would have ended it.
 *
 * On his phone it steps aside once screen sharing starts: the sharing service is a foreground
 * service too, with its own notification, and two notifications would only confuse him.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var following = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go foreground first: a service started as a foreground service must, even if it
        // is about to stop again.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(describe() ?: "Help session"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (intent?.action == ACTION_STOP) {
            SessionHub.endNeedy("You stopped the session.")
            SessionHub.endHelper("You stopped the session.")
        }
        if (!following) {
            following = true
            scope.launch {
                combine(SessionHub.changes, ShareState.active) { _, _ -> describe() }.collect { text ->
                    if (text == null) {
                        ServiceCompat.stopForeground(this@SessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** What the notification says, or null once there is nothing left to keep alive. */
    private fun describe(): String? {
        val helper = SessionHub.helper?.takeIf { !it.ended }
        val needy = SessionHub.needy?.takeIf { !it.ended && !ShareState.active.value }
        return when {
            helper != null -> "Help session with ${helper.state.value.name} is open."
            needy != null -> "Help session with ${needy.state.value.helperName} is open."
            else -> null
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Help session", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Yattu Bhaa")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.yattubhaa.app.STOP_SESSION"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 2

        /** Only ever called right after he or the helper tapped something, so this app is on
         *  screen: Android does not let a foreground service start from the background. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }
    }
}
