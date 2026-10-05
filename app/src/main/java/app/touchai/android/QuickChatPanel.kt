package app.touchai.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class QuickChatSize { OpenApp, Normal, Collapsed }

@Composable
internal fun QuickChatPanel(onClose: () -> Unit, onOpenApp: () -> Unit, onCollapse: () -> Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val height = constraints.maxHeight.toFloat()
        val normalTop = height * 0.12f
        val handleHeight = 48.dp
        val peekHeight = with(density) { handleHeight.toPx() } + WindowInsets.navigationBars.getBottom(density)
        val anchors = DraggableAnchors {
            QuickChatSize.OpenApp at 0f
            QuickChatSize.Normal at normalTop
            QuickChatSize.Collapsed at height - peekHeight
        }
        val sheet = rememberSaveable(saver = Saver<AnchoredDraggableState<QuickChatSize>, String>(
            save = { it.settledValue.name },
            restore = { AnchoredDraggableState(QuickChatSize.valueOf(it), anchors) },
        )) { AnchoredDraggableState(QuickChatSize.Normal, anchors) }
        SideEffect { sheet.updateAnchors(anchors, sheet.targetValue) }
        LaunchedEffect(sheet.settledValue) {
            if (sheet.settledValue == QuickChatSize.OpenApp) {
                focus.clearFocus()
                keyboard?.hide()
                onOpenApp()
            } else if (sheet.settledValue == QuickChatSize.Collapsed && !onCollapse()) {
                sheet.animateTo(QuickChatSize.Normal)
            }
        }
        val collapsed = sheet.targetValue == QuickChatSize.Collapsed
        LaunchedEffect(collapsed) {
            if (collapsed) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
        val offset = sheet.requireOffset()
        val scrimAlpha = 0.32f * (1f - (offset - normalTop) / (height - peekHeight - normalTop)).coerceIn(0f, 1f)
        val panelHeight = with(density) { maxOf(height - offset, height - normalTop).toDp() }
        val topPadding = with(density) { (WindowInsets.safeDrawing.getTop(this) - offset).coerceAtLeast(0f).toDp() }
        val corners = 28.dp * (offset / normalTop).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(enabled = !collapsed, onClick = onClose))
        Surface(
            modifier = Modifier.offset { IntOffset(0, sheet.requireOffset().roundToInt()) }.fillMaxWidth().height(panelHeight),
            shape = RoundedCornerShape(topStart = corners, topEnd = corners),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Spacer(Modifier.height(topPadding))
                Box(Modifier.fillMaxWidth().height(handleHeight)
                    .anchoredDraggable(sheet, Orientation.Vertical, flingBehavior = AnchoredDraggableDefaults.flingBehavior(sheet))
                    .clickable(onClickLabel = if (collapsed) "Expand chat" else "Collapse chat") {
                        scope.launch { sheet.animateTo(if (collapsed) QuickChatSize.Normal else QuickChatSize.Collapsed) }
                    }
                    .semantics {
                        contentDescription = "Resize chat"
                        stateDescription = when (sheet.settledValue) {
                            QuickChatSize.OpenApp -> "Opening app"
                            QuickChatSize.Normal -> "Expanded"
                            QuickChatSize.Collapsed -> "Collapsed"
                        }
                        if (sheet.settledValue != QuickChatSize.OpenApp) expand(if (collapsed) "Expand chat" else "Open in app") {
                            scope.launch { sheet.animateTo(if (collapsed) QuickChatSize.Normal else QuickChatSize.OpenApp) }; true
                        }
                        if (sheet.settledValue != QuickChatSize.Collapsed) collapse("Collapse chat") {
                            scope.launch { sheet.animateTo(QuickChatSize.Collapsed) }; true
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                }
                // The handle already clears the top inset. Keep the chat composed below the screen when collapsed.
                Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                    content()
                }
            }
        }
    }
}
