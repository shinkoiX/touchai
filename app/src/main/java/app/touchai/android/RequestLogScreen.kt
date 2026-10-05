package app.touchai.android

import android.content.ClipData
import android.content.ClipboardManager
import android.database.sqlite.SQLiteException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose) { Text("Back") }
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("Clear logs") }
        }
        Text("Request logs", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text("Stored on this device until cleared. Includes sent messages and instructions; excludes API keys and image data.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        (readError ?: storageError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!loading && entries.isEmpty()) item { Text("No requests recorded yet.") }
            items(entries.take(limit), key = { it.id }) { entry ->
                OutlinedCard(onClick = { expanded = if (expanded == entry.id) null else entry.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${entry.status} · ${entry.data.getValue("purpose").jsonPrimitive.content}", style = MaterialTheme.typography.titleMedium)
                        Text(dateFormat.format(Date(entry.startedAt)), style = MaterialTheme.typography.labelMedium)
                        Text(entry.data.getValue("model").jsonPrimitive.content, style = MaterialTheme.typography.bodyMedium)
                        val duration = entry.data["durationMillis"]?.jsonPrimitive?.content?.let { " · ${it} ms" }.orEmpty()
                        Text("${entry.data.getValue("messageCount")} messages · ${entry.data.getValue("imageCount")} images$duration", style = MaterialTheme.typography.bodySmall)
                        if (expanded == entry.id) {
                            val text = remember(entry) { json.encodeToString(JsonObject.serializer(), entry.json()) }
                            TextButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Request log", text))
                            }) { Text("Copy log") }
                            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                        } else Text("Tap for details", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (entries.size > limit) item { OutlinedButton(onClick = { limit += 100 }) { Text("Load older") } }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear request logs?") },
        text = { Text("This deletes all stored request logs from this device.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch {
                try { store.clear(); readError = null }
                catch (_: SQLiteException) { readError = "Could not clear request logs." }
            }
        }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}
