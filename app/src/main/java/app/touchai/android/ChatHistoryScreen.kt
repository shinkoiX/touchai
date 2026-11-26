package app.touchai.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatHistoryScreen(
    state: OpenAIChatUiState,
    onSearchChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler {
        if (state.historyQuery.isNotEmpty()) onSearchChange("") else onClose()
    }
    var deleting by remember { mutableStateOf<ChatHistoryEntry?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val queryTerms = remember(state.historyQuery) { tokenizeSearchQuery(state.historyQuery) }
    val highlightColor = MaterialTheme.colorScheme.primary
    val busy = state.historyLoading || state.historySearching

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppTopBar("Chat history", navigation = { AppIconButton(R.drawable.ic_back, "Back", onClose, enabled = !state.historyTransferring) }) {
            AppIconButton(R.drawable.ic_refresh, "Refresh", onRefresh, enabled = !state.historyLoading)
            AppIconButton(R.drawable.ic_add, "New chat", onNew, enabled = !state.historyLoading)
            Box {
                AppIconButton(R.drawable.ic_more, "History options", { menuOpen = true }, enabled = !state.historyLoading)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Export history") }, enabled = state.historyEntries.isNotEmpty() || state.historyQuery.isNotBlank(),
                        onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("Import history") }, onClick = { menuOpen = false; onImport() })
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = CircleShape,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_search, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = state.historyQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search chats") },
                    singleLine = true,
                    enabled = !state.historyTransferring,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
                if (state.historyQuery.isNotEmpty()) {
                    AppIconButton(R.drawable.ic_close, "Clear search", { onSearchChange("") }, enabled = !state.historyTransferring)
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { MessageBanner(it, error = true, modifier = Modifier.padding(16.dp)) }
        state.historyNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
        if (!busy && state.historyEntries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.historyQuery.isNotBlank()) "No matching chats" else "No chats yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.historyEntries, key = { it.id }) { entry ->
                val snippet = state.historySnippets[entry.id]
                val titleText = remember(entry.title, queryTerms, highlightColor) {
                    highlightMatches(entry.title, queryTerms, highlightColor)
                }
                val snippetText = remember(snippet, queryTerms, highlightColor) {
                    snippet?.let { highlightMatches(it, queryTerms, highlightColor) }
                }
                Surface(onClick = { keyboard?.hide(); onOpen(entry.id) }, enabled = !state.historyLoading, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(titleText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            snippetText?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(dateFormat.format(Date(entry.updatedAt)), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.pairedChatId != null) {
                                    val pairBadge = if (state.historyQuery.isNotBlank() && entry.pairSlot != null) {
                                        "2× ${if (entry.pairSlot == 1) "B" else "A"}"
                                    } else {
                                        "2×"
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        shape = CircleShape,
                                    ) {
                                        Text(
                                            pairBadge,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            if (entry.pending) Text("Running", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        AppIconButton(R.drawable.ic_delete, "Delete chat", { deleting = entry }, enabled = !state.historyLoading && !entry.pending)
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

internal fun highlightMatches(text: String, terms: List<String>, highlightColor: Color): AnnotatedString {
    if (terms.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase(Locale.ROOT)
    val ranges = mutableListOf<IntRange>()
    for (term in terms) {
        if (term.isEmpty()) continue
        var start = 0
        while (start < lower.length) {
            val found = lower.indexOf(term, start)
            if (found < 0) break
            val endExclusive = (found + term.length).coerceAtMost(text.length)
            if (found < endExclusive) ranges.add(found until endExclusive)
            start = found + term.length
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)
    ranges.sortBy { it.first }
    return buildAnnotatedString {
        append(text)
        var currentStart = ranges[0].first
        var currentEnd = ranges[0].last + 1
        for (i in 1 until ranges.size) {
            val next = ranges[i]
            if (next.first <= currentEnd) {
                currentEnd = maxOf(currentEnd, next.last + 1)
            } else {
                addStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold), currentStart, currentEnd)
                currentStart = next.first
                currentEnd = next.last + 1
            }
        }
        addStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold), currentStart, currentEnd)
    }
}
