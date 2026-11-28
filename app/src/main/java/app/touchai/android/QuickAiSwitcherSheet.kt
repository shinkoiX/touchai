package app.touchai.android

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.wireServiceTier

/**
 * Compact bottom sheet for temporarily switching connection, model, reasoning effort, service tier,
 * and optional dual-send (A/B) comparison without editing saved Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAiSwitcherSheet(
    title: String,
    confirmLabel: String,
    connections: List<ApiConnection>,
    initial: AiConfiguration,
    canReset: Boolean,
    liveUpdate: Boolean,
    onApply: (AiConfiguration) -> Unit,
    onReset: (() -> Unit)? = null,
    allowDual: Boolean = false,
    dualEnabled: Boolean = false,
    initialSecondary: AiConfiguration? = null,
    initialSlot: Int = 0,
    editingSecondaryInSingleMode: Boolean = false,
    onDualModeChange: ((Boolean) -> Unit)? = null,
    onApplySecondary: ((AiConfiguration) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as TouchAiApplication
    val recentModels by app.settingsRepository.recentModels.collectAsStateWithLifecycle(initialValue = emptyList())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var primaryDraft by remember(initial) { mutableStateOf(initial) }
    var secondaryDraft by remember(initialSecondary, initial) { mutableStateOf(initialSecondary ?: initial) }
    var isDual by remember(dualEnabled) { mutableStateOf(allowDual && dualEnabled) }
    var activeSlot by remember(initialSlot, editingSecondaryInSingleMode) {
        mutableIntStateOf(
            when {
                allowDual && dualEnabled -> initialSlot.coerceIn(0, 1)
                editingSecondaryInSingleMode -> 1
                else -> 0
            },
        )
    }
    val editingSecondary = if (isDual) activeSlot == 1 else editingSecondaryInSingleMode
    val draft = if (editingSecondary) secondaryDraft else primaryDraft

    val selectedConnection = connections.find { it.id == draft.connectionId }
        ?: connections.firstOrNull { conn ->
            conn.authentication == draft.api.authentication &&
                if (conn.authentication == AuthenticationMethod.ApiKey) {
                    conn.baseUrl.trimEnd('/') == draft.api.baseUrl.trimEnd('/') && conn.protocol == draft.api.protocol
                } else {
                    conn.chatGptAccountId == draft.api.chatGptAccountId
                }
        }
    val endpointKey = selectedConnection?.recentEndpointKey() ?: draft.api.recentEndpointKey()
    val quickModels = remember(recentModels, endpointKey, draft.api.model) {
        buildList {
            recentModels.asSequence()
                .filter { it.endpointKey == endpointKey }
                .map { it.model }
                .distinct()
                .take(8)
                .forEach(::add)
            val current = draft.api.model.trim()
            if (current.isNotEmpty() && current !in this) add(0, current)
        }
    }
    var showAllModels by remember(endpointKey, activeSlot) { mutableStateOf(quickModels.isEmpty()) }
    var speedExpanded by remember { mutableStateOf(false) }

    fun updateDraft(next: AiConfiguration) {
        if (editingSecondary) {
            secondaryDraft = next
            if (liveUpdate) onApplySecondary?.invoke(next)
        } else {
            primaryDraft = next
            if (liveUpdate) onApply(next)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        isDual -> "Dual send"
                        editingSecondaryInSingleMode -> "Switch model (B)"
                        else -> title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (allowDual && onDualModeChange != null) {
                    AppPillChip(
                        label = "2×",
                        selected = isDual,
                        accentTertiary = true,
                        onClick = {
                            val next = !isDual
                            isDual = next
                            activeSlot = if (next) 1 else if (editingSecondaryInSingleMode) 1 else 0
                            onDualModeChange(next)
                        },
                    )
                }
                if (canReset && onReset != null) {
                    TextButton(onClick = { onReset(); onDismiss() }) { Text("Reset") }
                }
                if (!liveUpdate) {
                    Button(
                        onClick = {
                            onApply(primaryDraft)
                            onDismiss()
                        },
                        enabled = primaryDraft.api.model.isNotBlank(),
                        shape = CircleShape,
                    ) {
                        Text(confirmLabel)
                    }
                }
            }

            if (isDual) {
                val slots = listOf(0, 1)
                AppSegmentBar(
                    options = slots,
                    selected = { it == activeSlot },
                    label = { idx ->
                        if (idx == 0) "A · ${primaryDraft.api.model.ifBlank { "Pick model" }}"
                        else "B · ${secondaryDraft.api.model.ifBlank { "Pick model" }}"
                    },
                    onSelect = { activeSlot = it },
                )
            }

            if (connections.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    connections.forEach { connection ->
                        val selected = connection.id == selectedConnection?.id
                        AppPillChip(
                            label = connection.name.ifBlank { "API" },
                            selected = selected,
                            onClick = {
                                if (selected) return@AppPillChip
                                val targetKey = connection.recentEndpointKey()
                                val sameModels = selectedConnection != null &&
                                    selectedConnection.authentication == connection.authentication &&
                                    if (connection.authentication == AuthenticationMethod.ApiKey) {
                                        selectedConnection.baseUrl.trimEnd('/') == connection.baseUrl.trimEnd('/')
                                    } else {
                                        selectedConnection.chatGptAccountId == connection.chatGptAccountId
                                    }
                                val nextModel = if (sameModels) draft.api.model
                                else recentModels.firstOrNull { it.endpointKey == targetKey }?.model.orEmpty()
                                updateDraft(
                                    draft.copy(
                                        connectionId = connection.id,
                                        api = draft.api.using(connection).copy(model = nextModel),
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            if (quickModels.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickModels.forEach { recentId ->
                        AppPillChip(
                            label = recentId,
                            selected = draft.api.model == recentId,
                            onClick = { updateDraft(draft.copy(api = draft.api.copy(model = recentId))) },
                        )
                    }
                    AppPillChip(
                        label = "More",
                        selected = showAllModels,
                        onClick = { showAllModels = !showAllModels },
                        trailingIcon = { AppIcon(R.drawable.ic_expand, null, size = 15.dp) },
                    )
                }
            }

            if (showAllModels || quickModels.isEmpty()) {
                ModelField(selectedConnection, draft.api.model) { model ->
                    updateDraft(draft.copy(api = draft.api.copy(model = model)))
                }
            }

            AppDivider()

            ReasoningEffortEditor(
                effort = draft.api.reasoningEffort,
                showCustomInput = false,
            ) { effort ->
                updateDraft(draft.copy(api = draft.api.copy(reasoningEffort = effort)))
            }

            if ((selectedConnection?.authentication ?: draft.api.authentication) != AuthenticationMethod.ChatGpt) {
                AppDivider()
                val protocol = selectedConnection?.protocol ?: draft.api.protocol
                val effectiveTier = wireServiceTier(protocol, draft.api.serviceTier)
                val tierLabel = when (effectiveTier) {
                    null -> "Default"
                    "flex" -> "Flex"
                    "fast" -> "Fast"
                    "priority" -> "Priority"
                    "ultrafast" -> "Ultra"
                    else -> effectiveTier.replaceFirstChar(Char::uppercase)
                }
                val chevronRotation by animateFloatAsState(if (speedExpanded) 180f else 0f, label = "speedChevron")
                Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { speedExpanded = !speedExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Service tier",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            tierLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (effectiveTier != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppIcon(
                            R.drawable.ic_expand,
                            if (speedExpanded) "Collapse service tier" else "Expand service tier",
                            Modifier.padding(start = 4.dp).rotate(chevronRotation),
                            size = 18.dp,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (speedExpanded) {
                        ServiceTierEditor(protocol, draft.api.serviceTier, showHeader = false) { tier ->
                            updateDraft(draft.copy(api = draft.api.copy(serviceTier = tier)))
                        }
                    }
                }
            }

            AppDivider()
            SwitchRow("Rich UI", draft.openUi, { enabled ->
                updateDraft(draft.copy(openUi = enabled))
            })
        }
    }
}
