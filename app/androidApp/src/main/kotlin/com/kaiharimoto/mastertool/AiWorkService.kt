package com.kaiharimoto.mastertool

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
import android.os.PowerManager

/**
 * Ai at work, kept alive out of sight (1.0.61, kai: "I want to be able to [switch apps] without
 * the conversation cutting off"). Android freezes a background app's process within seconds and
 * some phones (Xiaomi's among them) cut its network, so an answer being written died the moment
 * another app came up — "Unable to resolve host". While Ai works, this foreground service holds
 * the process at the foreground's standing, with a partial wake lock for a screen that goes off,
 * under an ongoing notification that says so. It stops the moment Ai does; nothing else runs it.
 */
class AiWorkService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(TITLE) ?: "Ai is working"
        val line = intent?.getStringExtra(LINE).orEmpty()
        val notice = notice(this, title, line, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(WORKING, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(WORKING, notice)
        }
        if (lock == null) {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neue:ai-work")
                .apply { acquire(MAX_HOLD_MS) }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits a data-sync service's day; at its limit, it lets go rather than being stopped. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_WORKING = "ai-working"
        private const val CHANNEL_ANSWERED = "ai-answered"
        private const val WORKING = 7301
        private const val ANSWERED = 7302
        private const val TITLE = "title"
        private const val LINE = "line"
        private const val STOP = "stop"

        /** The longest a wake lock is held, whatever happens: a long answer with its tools. */
        private const val MAX_HOLD_MS = 30 * 60 * 1000L

        @Volatile
        private var on = false

        /** Ai started or stopped working; started only from the foreground, which is where a person sends from. */
        fun set(context: Context, working: Boolean, title: String, line: String) {
            val app = context.applicationContext
            if (working) {
                val intent = Intent(app, AiWorkService::class.java).putExtra(TITLE, title).putExtra(LINE, line)
                // Once it runs, a new line is an update to its notification; the first start must be a foreground one.
                runCatching {
                    if (on) app.startService(intent) else app.startForegroundService(intent)
                    on = true
                }
            } else if (on) {
                on = false
                runCatching { app.startService(Intent(app, AiWorkService::class.java).setAction(STOP)) }
                    .onFailure { runCatching { app.stopService(Intent(app, AiWorkService::class.java)) } }
            }
        }

        /** Ai answered while the app was out of sight. */
        fun answered(context: Context, title: String, line: String) {
            val app = context.applicationContext
            runCatching { manager(app).notify(ANSWERED, notice(app, title, line, ongoing = false)) }
        }

        /** The "answered" notice goes once the app is back in sight. */
        fun seen(context: Context) {
            runCatching { manager(context.applicationContext).cancel(ANSWERED) }
        }

        private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        private fun notice(context: Context, title: String, line: String, ongoing: Boolean): Notification {
            val m = manager(context)
            val channel = if (ongoing) CHANNEL_WORKING else CHANNEL_ANSWERED
            if (m.getNotificationChannel(channel) == null) {
                m.createNotificationChannel(
                    if (ongoing) {
                        NotificationChannel(CHANNEL_WORKING, "Ai at work", NotificationManager.IMPORTANCE_LOW)
                            .apply { description = "Shown while Ai answers with the app out of sight, so the answer is not cut off." }
                    } else {
                        NotificationChannel(CHANNEL_ANSWERED, "Ai answered", NotificationManager.IMPORTANCE_DEFAULT)
                            .apply { description = "When Ai finishes an answer while the app is out of sight." }
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_ai)
                .setContentTitle(title)
                .setContentText(line)
                .setStyle(Notification.BigTextStyle().bigText(line))
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true)
                .setCategory(if (ongoing) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_MESSAGE)
                .apply { if (ongoing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
                .build()
        }
    }
}
