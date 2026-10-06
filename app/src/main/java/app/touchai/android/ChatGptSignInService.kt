package app.touchai.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps the loopback callback runnable while the browser covers our activity. */
class ChatGptSignInService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, "ChatGPT sign-in", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = Intent(this, ChatGptSignInActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val cancel = Intent(open).setAction(ChatGptSignInActivity.CancelSignIn)
        startForeground(NotificationId, NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_quick_access)
            .setContentTitle("Signing in to ChatGPT")
            .setContentIntent(PendingIntent.getActivity(this, 30, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Cancel", PendingIntent.getActivity(this, 31, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ChannelId = "chatgpt_sign_in"
        private const val NotificationId = 3
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChatGptSignInService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, ChatGptSignInService::class.java))
        }
    }
}
