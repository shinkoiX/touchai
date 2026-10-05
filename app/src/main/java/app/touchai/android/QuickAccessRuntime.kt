package app.touchai.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuickAccessState(
    val connected: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val options: QuickAccessSettings = QuickAccessSettings(),
    val error: String? = null,
)

class QuickAccessRuntime(private val context: Context, private val repository: DataStoreSettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(QuickAccessState())
    val state = mutableState.asStateFlow()
    private var service: ScreenCaptureService? = null
    private var appVisible = false
    private var optionsLoaded = false

    init {
        scope.launch {
            try { repository.quickAccess.collect { options ->
                optionsLoaded = true
                mutableState.update { it.copy(options = options, error = null) }
                applyOptions()
            } } catch (error: Exception) { mutableState.update { it.copy(error = "Could not load quick-access settings: ${error.message}") } }
        }
    }

    fun connect(value: ScreenCaptureService) {
        service = value
        mutableState.update { it.copy(connected = true) }
        refreshPermissions()
        applyOptions()
    }

    fun disconnect(value: ScreenCaptureService) {
        if (service === value) {
            service = null
            mutableState.update { it.copy(connected = false) }
            NotificationManagerCompat.from(context).cancel(NotificationId)
        }
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        service?.setBubbleVisible(optionsLoaded && state.value.options.floatingButton && !visible)
    }

    fun refreshPermissions() {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(ChannelId)
        val granted = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (granted != state.value.notificationsAllowed) {
            mutableState.update { it.copy(notificationsAllowed = granted) }
        }
        updateNotification()
    }

    private fun applyOptions() {
        if (!optionsLoaded) return
        service?.updateButtonPosition(state.value.options)
        service?.setBubbleVisible(state.value.options.floatingButton && !appVisible)
        updateNotification()
    }

    fun saveButtonPosition(onRight: Boolean, y: Float) {
        scope.launch {
            try { repository.saveButtonPosition(onRight, y) }
            catch (error: Exception) { mutableState.update { it.copy(error = "Could not save button position: ${error.message}") } }
        }
    }

    suspend fun capture(fromNotification: Boolean): Bitmap {
        val connectedService = service
            ?: throw ScreenCaptureException("Enable TouchAI in Android Accessibility settings to capture the screen.")
        return connectedService.captureScreen(waitForNotificationShade = fromNotification)
    }

    private fun updateNotification() {
        val manager = NotificationManagerCompat.from(context)
        val snapshot = state.value
        if (!optionsLoaded || !snapshot.connected || !snapshot.options.notification || !snapshot.notificationsAllowed) {
            manager.cancel(NotificationId)
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, "Quick access", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tap to capture the current screen and ask AI"
                setShowBadge(false)
            },
        )
        val capture = PendingIntent.getActivity(context, 0, CaptureActivity.intent(context, fromNotification = true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val settings = PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).setAction(MainActivity.OpenSettings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_quick_access)
            .setContentTitle("Ask about this screen")
            .setContentIntent(capture)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Ask AI", capture).addAction(0, "Settings", settings)
            .build()
        // Permission is checked above; the system remains authoritative if it is revoked.
        try { manager.notify(NotificationId, notification) }
        catch (_: SecurityException) { mutableState.update { it.copy(notificationsAllowed = false) } }
    }

    companion object {
        private const val ChannelId = "quick_access"
        private const val NotificationId = 1
    }
}

class ScreenCaptureException(message: String) : Exception(message)
