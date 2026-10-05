package app.touchai.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatHistoryScreen(state: OpenAIChatUiState, onOpen: (String) -> Unit, onDelete: (String) -> Unit,
    onNew: () -> Unit, onRefresh: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    var deleting by remember { mutableStateOf<ChatHistoryEntry?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppTopBar("Chat history", navigation = { AppIconButton(R.drawable.ic_back, "Back", onClose, enabled = !state.historyTransferring) }) {
            AppIconButton(R.drawable.ic_refresh, "Refresh", onRefresh, enabled = !state.historyLoading)
            AppIconButton(R.drawable.ic_add, "New chat", onNew, enabled = !state.historyLoading)
            Box {
                AppIconButton(R.drawable.ic_more, "History options", { menuOpen = true }, enabled = !state.historyLoading)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Export history") }, enabled = state.historyEntries.isNotEmpty(),
                        onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("Import history") }, onClick = { menuOpen = false; onImport() })
                }
            }
        }
        if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { MessageBanner(it, error = true, modifier = Modifier.padding(16.dp)) }
        state.historyNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
        if (!state.historyLoading && state.historyEntries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No chats yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.historyEntries, key = { it.id }) { entry ->
                Surface(onClick = { onOpen(entry.id) }, enabled = !state.historyLoading, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(dateFormat.format(Date(entry.updatedAt)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppIconButton(R.drawable.ic_delete, "Delete chat", { deleting = entry }, enabled = !state.historyLoading)
                    }
                }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete chat?") },
            text = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(entry.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
