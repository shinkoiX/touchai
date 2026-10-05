package app.touchai.android

import android.content.ClipData
import android.content.ClipboardManager
import android.database.sqlite.SQLiteException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.touchai.core.openai.RequestLogRecord
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun RequestLogScreen(store: RequestLogStore, onClose: () -> Unit) {
    BackHandler { onClose() }
    val revision by store.revision.collectAsStateWithLifecycle()
    val storageError by store.error.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<RequestLogRecord>>(emptyList()) }
    var limit by rememberSaveable { mutableIntStateOf(100) }
    var loading by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val json = remember { Json { prettyPrint = true } }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }
    LaunchedEffect(revision, limit, refresh) {
        loading = true
        try { entries = store.read(limit + 1); readError = null }
        catch (_: SQLiteException) { readError = "Could not read request logs. Device storage may be unavailable." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppTopBar("Request logs", navigation = { AppIconButton(R.drawable.ic_back, "Back", onClose) }) {
            AppIconButton(R.drawable.ic_refresh, "Refresh", { refresh++ })
            AppIconButton(R.drawable.ic_delete, "Clear logs", { confirmClear = true }, enabled = entries.isNotEmpty())
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        (readError ?: storageError)?.let { MessageBanner(it, error = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (!loading && entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No requests yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries.take(limit), key = { it.id }) { entry ->
                Surface(onClick = { expanded = if (expanded == entry.id) null else entry.id }, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(entry.status)
                            Text(entry.data.getValue("purpose").jsonPrimitive.content, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(dateFormat.format(Date(entry.startedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(entry.data.getValue("model").jsonPrimitive.content, style = MaterialTheme.typography.bodyMedium)
                        val duration = entry.data["durationMillis"]?.jsonPrimitive?.content?.let { " · ${it} ms" }.orEmpty()
                        Text("${entry.data.getValue("messageCount")} messages · ${entry.data.getValue("imageCount")} images$duration",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (expanded == entry.id) {
                            val text = remember(entry) { json.encodeToString(JsonObject.serializer(), entry.json()) }
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                                Box {
                                    SelectionContainer {
                                        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 48.dp))
                                    }
                                    Box(Modifier.align(Alignment.TopEnd)) {
                                        AppIconButton(R.drawable.ic_copy, "Copy log", {
                                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Request log", text))
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (entries.size > limit) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = { limit += 100 }) { Text("Load older") } }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear request logs?") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch {
                try { store.clear(); readError = null }
                catch (_: SQLiteException) { readError = "Could not clear request logs." }
            }
        }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun StatusPill(status: String) {
    val colors = MaterialTheme.colorScheme
    val (background, content) = when (status) {
        "Completed" -> colors.primaryContainer to colors.onPrimaryContainer
        "Failed" -> colors.errorContainer to colors.onErrorContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(color = background, contentColor = content, shape = CircleShape) {
        Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}
