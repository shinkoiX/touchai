package app.touchai.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

@Composable
fun QuickAccessSettings(options: QuickAccessSettings, onChange: (QuickAccessSettings) -> Unit, runtime: QuickAccessRuntime) {
    val status by runtime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { runtime.refreshPermissions() }
    val openNotificationSettings = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Screen capture", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val color = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(if (status.connected) "On" else "Off", style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
            val openAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            if (status.connected) TextButton(onClick = openAccessibility) { Text("Manage") }
            else FilledTonalButton(onClick = openAccessibility) { Text("Enable") }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow("Floating button", options.floatingButton, { onChange(options.copy(floatingButton = it)) })
        SwitchRow("Notification", options.notification, { onChange(options.copy(notification = it)) })
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Notification capture delay", style = MaterialTheme.typography.bodyMedium)
                Text("${options.notificationCaptureDelayMillis} ms", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = options.notificationCaptureDelayMillis.toFloat(),
                onValueChange = { onChange(options.copy(notificationCaptureDelayMillis = (it / 50).roundToInt() * 50)) },
                valueRange = 0f..1_000f,
                steps = 19,
                enabled = options.notification,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!status.notificationsAllowed) TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openNotificationSettings()
            }) { Text("Allow notifications") }
            TextButton(onClick = openNotificationSettings) { Text("Notification settings") }
        }
        status.error?.let { MessageBanner(it, error = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    }
}
