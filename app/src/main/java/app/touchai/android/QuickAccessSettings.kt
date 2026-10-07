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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        }
        SettingsGroup {
            SwitchRow("Corner gesture", options.cornerSwipe, { onChange(options.copy(cornerSwipe = it)) })
            if (options.cornerSwipe) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAccessDropdown(options.gestureCorner.label, GestureCorner.entries, { it.label }) {
                        onChange(options.copy(gestureCorner = it))
                    }
                    QuickAccessDropdown(options.cornerGestures.joinToString { it.label(options.gestureCorner) },
                        CornerGesture.entries, { it.label(options.gestureCorner) }) {
                        onChange(options.copy(cornerGestures = setOf(it)))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Opacity", style = MaterialTheme.typography.bodyMedium)
                        Text("${options.cornerOpacityPercent}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = options.cornerOpacityPercent.toFloat(),
                        onValueChange = { onChange(options.copy(cornerOpacityPercent = (it / 5).roundToInt() * 5)) },
                        valueRange = 0f..100f, steps = 19,
                    )
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Touch area size", style = MaterialTheme.typography.bodyMedium)
                        Text("${options.cornerAreaSizeDp} dp", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = options.cornerAreaSizeDp.toFloat(),
                        onValueChange = { onChange(options.copy(cornerAreaSizeDp = (it / 8).roundToInt() * 8)) },
                        valueRange = 32f..160f, steps = 15,
                    )
                }
            }
        }
        SettingsGroup {
            SwitchRow("Floating button", options.floatingButton, { onChange(options.copy(floatingButton = it)) })
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Button size", style = MaterialTheme.typography.bodyMedium)
                    Text("${options.buttonSizeDp} dp", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = options.buttonSizeDp.toFloat(),
                    onValueChange = { onChange(options.copy(buttonSizeDp = (it / 4).roundToInt() * 4)) },
                    valueRange = 32f..96f, steps = 15, enabled = options.floatingButton,
                )
            }
        }
        SettingsGroup {
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
        }
        SettingsGroup {
            SwitchRow("Attach screenshots automatically", options.attachScreenshotAutomatically,
                { onChange(options.copy(attachScreenshotAutomatically = it)) })
        }
        status.error?.let { MessageBanner(it, error = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> QuickAccessDropdown(value: String, entries: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(text = { Text(label(entry)) }, onClick = {
                    onSelect(entry)
                    expanded = false
                })
            }
        }
    }
}
