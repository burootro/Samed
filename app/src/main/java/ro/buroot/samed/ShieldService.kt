package ro.buroot.samed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the shield running whenever the app is active,
 * including after the screen is off. Posts a persistent notification with the
 * live protection status.
 */
class ShieldService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private lateinit var engine: ShieldEngine

    @Volatile private var lastStatus: ShieldEngine.Status =
        ShieldEngine.Status(running = false, protected = false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = ShieldEngine { status ->
            lastStatus = status
            StatusBus.publish(status)
            updateNotification(status)
        }
        startForeground(NOTIF_ID, buildNotification("Starting protection…", false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) {
            loop = scope.launch { engine.run() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        StatusBus.publish(ShieldEngine.Status(running = false, protected = false,
            lastMessage = "Protection stopped."))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Samed Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows whether network protection is active."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, protectedNow: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ShieldService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (protectedNow) "Protected" else "Samed"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: ShieldEngine.Status) {
        val text = if (status.protected)
            "Gateway locked • ${status.attacksBlocked} attempt(s) blocked"
        else
            status.lastMessage
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, status.protected))
    }

    companion object {
        private const val CHANNEL_ID = "samed_protection"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "ro.buroot.samed.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ShieldService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ShieldService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
