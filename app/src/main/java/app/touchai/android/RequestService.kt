package app.touchai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class RequestService : Service() {
    private val app get() = application as TouchAiApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, "Active requests", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationId, notification())
        scope.launch {
            app.requests.restorePending()
            if (intent?.action == StopAll) app.requests.stopAll()
            if (app.requests.active.value.isEmpty()) stopSelf()
            else if (observer == null) observer = launch {
                app.requests.active.collect { if (it.isNotEmpty()) startForeground(NotificationId, notification()) }
            }
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val active = app.requests.active.value
        val open = Intent(this, MainActivity::class.java).setAction(MainActivity.OpenRequest)
            .putExtra(MainActivity.RequestChatId, active.keys.lastOrNull())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val stop = Intent(this, RequestService::class.java).setAction(StopAll)
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_quick_access)
            .setContentTitle("TouchAI is responding")
            .setContentText(if (active.size > 1) "${active.size} requests running" else "Request running in the background")
            .setContentIntent(PendingIntent.getActivity(this, 20, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, if (active.size > 1) "Stop all" else "Stop",
                PendingIntent.getService(this, 21, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.requests.pauseForSystem()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val ChannelId = "active_requests"
        private const val NotificationId = 2
        private const val StopAll = "app.touchai.android.STOP_REQUESTS"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, RequestService::class.java)) }
    }
}
