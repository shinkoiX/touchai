package app.touchai.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppTopBar("Chat history", navigation = { AppIconButton(R.drawable.ic_back, "Back", onClose, enabled = !state.historyTransferring) }) {
            AppIconButton(R.drawable.ic_refresh, "Refresh", onRefresh, enabled = !state.historyLoading)
            AppIconButton(R.drawable.ic_add, "New chat", onNew, enabled = !state.historyLoading)
            Box {
                AppIconButton(R.drawable.ic_more, "History options", { menuOpen = true }, enabled = !state.historyLoading)
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("Export history") },
                        enabled = state.historyEntries.isNotEmpty() || state.historyQuery.isNotBlank(),
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(text = { Text("Import history") }, onClick = { menuOpen = false; onImport() })
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = CircleShape,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppIcon(R.drawable.ic_search, null, size = 19.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                val searchStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                BasicTextField(
                    value = state.historyQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    enabled = !state.historyTransferring,
                    textStyle = searchStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                            if (state.historyQuery.isEmpty()) {
                                Text(
                                    "Search chats",
                                    style = searchStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (state.historyQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchChange("") },
                        enabled = !state.historyTransferring,
                        modifier = Modifier.size(34.dp),
                    ) {
                        AppIcon(R.drawable.ic_close, "Clear search", size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { MessageBanner(it, error = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        state.historyNotice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (!busy && state.historyEntries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.historyQuery.isNotBlank()) "No matching chats" else "No chats yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                itemsIndexed(state.historyEntries, key = { _, item -> item.id }) { index, entry ->
                    val snippet = state.historySnippets[entry.id]
                    val titleText = remember(entry.title, queryTerms, highlightColor) {
                        highlightMatches(entry.title, queryTerms, highlightColor)
                    }
                    val snippetText = remember(snippet, queryTerms, highlightColor) {
                        snippet?.let { highlightMatches(it, queryTerms, highlightColor) }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        if (index > 0) AppDivider(Modifier.padding(horizontal = 20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.historyLoading) { keyboard?.hide(); onOpen(entry.id) }
                                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    titleText,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                snippetText?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        dateFormat.format(Date(entry.updatedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
                                    if (entry.pending) {
                                        Text(
                                            "Running",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { deleting = entry },
                                enabled = !state.historyLoading && !entry.pending,
                            ) {
                                AppIcon(
                                    R.drawable.ic_delete,
                                    "Delete chat",
                                    size = 19.dp,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Delete chat?", fontWeight = FontWeight.SemiBold) },
            text = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(entry.id) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
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
