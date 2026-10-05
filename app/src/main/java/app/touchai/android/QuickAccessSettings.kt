package app.touchai.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat

@Composable
fun QuickAccessSettings(options: QuickAccessSettings, onChange: (QuickAccessSettings) -> Unit, runtime: QuickAccessRuntime) {
    val status by runtime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { runtime.refreshPermissions() }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick access", style = MaterialTheme.typography.titleLarge)
            Text(if (status.connected) "Screen capture service is connected" else "Enable TouchAI screen capture in Android Accessibility settings.")
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text(if (status.connected) "Accessibility settings" else "Enable screen capture")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Floating button")
                Switch(options.floatingButton, { onChange(options.copy(floatingButton = it)) })
            }
            Text("Drag the button to move it. Tap it to capture and open a quick conversation.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Quick-access notification")
                Switch(options.notification, { onChange(options.copy(notification = it)) })
            }
            if (!status.notificationsAllowed) {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Allow notifications") }
            }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
            Text("Save to apply these switches. Android may let you dismiss an ongoing notification.", style = MaterialTheme.typography.bodySmall)
            if (!status.connected) Text("For a sideloaded APK, Android may first require App info → Allow restricted settings.", style = MaterialTheme.typography.bodySmall)
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
