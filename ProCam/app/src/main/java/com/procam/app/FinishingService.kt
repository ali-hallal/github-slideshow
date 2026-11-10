package com.procam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the app alive while a photo is still being made.
 *
 * A merged photo takes seconds after the shutter. If the user leaves the app in that time,
 * Android may stop it before the photo is saved. While this service runs (with its
 * "Finishing your photo" notification) the work is allowed to finish. It does no work itself:
 * [begin] when a photo starts being made, [end] when it is saved.
 */
class FinishingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always, and first: a service started this way must show its notification promptly.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // The photo may already be done by the time the service is up.
        if (synchronized(lock) { jobs == 0 }) stopSelf()
        return START_NOT_STICKY
    }

    /** Android's limit for this kind of service is up (about 3 minutes): bow out. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Finishing photos", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gallery)
            .setContentTitle("Finishing your photo…")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ProCam"
        private const val CHANNEL = "finishing"
        private const val NOTIFICATION_ID = 7
        private val lock = Any()
        private var jobs = 0
        @Volatile private var running: FinishingService? = null

        /** Photos being made right now (tests). */
        val active get() = synchronized(lock) { jobs }

        /** A photo starts being made. */
        fun begin(context: Context) {
            val first = synchronized(lock) { jobs++ == 0 }
            if (!first) return
            try {
                context.applicationContext.startForegroundService(Intent(context.applicationContext, FinishingService::class.java))
            } catch (e: Exception) {
                // Not allowed right now (the app is already in the background): the photo is
                // still made if Android lets the app live, and its safety copy is kept either way.
                Log.w(TAG, "could not start the finishing service", e)
            }
        }

        /** A photo is saved (or given up on). */
        fun end() {
            val last = synchronized(lock) {
                if (jobs > 0) jobs--
                jobs == 0
            }
            if (last) running?.stopSelf()
        }
    }
}
