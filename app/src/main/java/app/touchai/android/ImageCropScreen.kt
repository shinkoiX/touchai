package app.touchai.android

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ImageCropScreen(bitmap: Bitmap, initialCrop: ImageCrop, onApply: (ImageCrop) -> Unit, onCancel: () -> Unit) {
    var crop by rememberSaveable(bitmap, stateSaver = CropSaver) { mutableStateOf(initialCrop) }
    BackHandler { onCancel() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppTopBar("Crop", navigation = { AppIconButton(R.drawable.ic_close, "Cancel", onCancel) }) {
            Button(onClick = { onApply(crop) }, modifier = Modifier.padding(end = 8.dp)) {
                AppIcon(R.drawable.ic_check, null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Apply")
            }
        }
        CropEditor(bitmap, crop, { crop = it }, Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), enabled = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropEditor(bitmap: Bitmap, crop: ImageCrop, onCrop: (ImageCrop) -> Unit, modifier: Modifier, enabled: Boolean) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by rememberSaveable(bitmap) { mutableFloatStateOf(1f) }
    var pan by rememberSaveable(bitmap, stateSaver = PanSaver) { mutableStateOf(Offset.Zero) }
    var panMode by rememberSaveable { mutableStateOf(false) }
    val currentCrop by rememberUpdatedState(crop)
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    fun bounds(): Rect {
        val scale = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height) * zoom
        val size = Size(bitmap.width * scale, bitmap.height * scale)
        return Rect(Offset((viewport.width - size.width) / 2f, (viewport.height - size.height) / 2f) + pan, size)
    }
    fun normalized(point: Offset): Offset {
        val rect = bounds()
        return Offset(((point.x - rect.left) / rect.width).coerceIn(0f, 1f), ((point.y - rect.top) / rect.height).coerceIn(0f, 1f))
    }
    fun constrainPan(value: Offset): Offset {
        val rect = bounds()
        val x = ((rect.width - viewport.width) / 2f).coerceAtLeast(0f)
        val y = ((rect.height - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(!panMode, { panMode = false }, SegmentedButtonDefaults.itemShape(0, 2), enabled = enabled,
                    icon = { AppIcon(R.drawable.ic_crop, null, size = 18.dp) }) { Text("Crop") }
                SegmentedButton(panMode, { panMode = true }, SegmentedButtonDefaults.itemShape(1, 2), enabled = enabled,
                    icon = { AppIcon(R.drawable.ic_pan, null, size = 18.dp) }) { Text("Move") }
            }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero; onCrop(ImageCrop.Full) }, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) { Text("Reset") }
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest).clipToBounds().onSizeChanged { viewport = it }
            .semantics { contentDescription = "Crop selection" }
            .pointerInput(bitmap, panMode, enabled) {
                if (!enabled) return@pointerInput
                if (panMode) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val old = bounds()
                        val imagePoint = Offset((centroid.x - old.left) / old.width, (centroid.y - old.top) / old.height)
                        zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                        val next = bounds()
                        val origin = centroid - Offset(imagePoint.x * next.width, imagePoint.y * next.height) + panChange
                        pan = constrainPan(origin - Offset((viewport.width - next.width) / 2f, (viewport.height - next.height) / 2f))
                    }
                } else {
                    var handle: CropHandle? = null
                    var start = Offset.Zero
                    detectDragGestures(onDragStart = { point ->
                        val rect = bounds()
                        val selection = currentCrop
                        val corners = listOf(
                            CropHandle.TopLeft to Offset(selection.left, selection.top),
                            CropHandle.TopRight to Offset(selection.right, selection.top),
                            CropHandle.BottomLeft to Offset(selection.left, selection.bottom),
                            CropHandle.BottomRight to Offset(selection.right, selection.bottom),
                        ).map { (handle, value) -> handle to Offset(rect.left + value.x * rect.width, rect.top + value.y * rect.height) }
                        val nearest = corners.minBy { (_, corner) -> (corner - point).getDistance() }
                        start = normalized(point)
                        handle = if ((nearest.second - point).getDistance() <= 32.dp.toPx()) nearest.first
                        else if (start.x in selection.left..selection.right && start.y in selection.top..selection.bottom) CropHandle.Move
                        else null
                    }) { change, amount ->
                        change.consume()
                        val rect = bounds()
                        val activeHandle = handle
                        onCrop(if (activeHandle != null) currentCrop.drag(activeHandle, amount.x / rect.width, amount.y / rect.height)
                        else normalized(change.position).let { cropBetween(start.x, start.y, it.x, it.y) })
                    }
                }
            }) {
            val rect = bounds()
            val offset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
            val imageSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1))
            drawImage(image, dstOffset = offset, dstSize = imageSize)
            drawRect(Color.Black.copy(alpha = 0.55f))
            val selection = Rect(rect.left + crop.left * rect.width, rect.top + crop.top * rect.height,
                rect.left + crop.right * rect.width, rect.top + crop.bottom * rect.height)
            clipRect(selection.left, selection.top, selection.right, selection.bottom) { drawImage(image, dstOffset = offset, dstSize = imageSize) }
            drawRect(Color.White, selection.topLeft, selection.size, style = Stroke(2.dp.toPx()))
            listOf(selection.topLeft, selection.topRight, selection.bottomLeft, selection.bottomRight).forEach { drawCircle(Color.White, 7.dp.toPx(), it) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${((zoom * 10).roundToInt() / 10f)}×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(zoom, { zoom = it; pan = constrainPan(pan) }, valueRange = 1f..4f, enabled = enabled, modifier = Modifier.weight(1f))
            val pixels = crop.pixels(bitmap.width, bitmap.height)
            Text("${pixels.width} × ${pixels.height} px", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val CropSaver = listSaver<ImageCrop, Float>(save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { ImageCrop(it[0], it[1], it[2], it[3]) })
private val PanSaver = listSaver<Offset, Float>(save = { listOf(it.x, it.y) }, restore = { Offset(it[0], it[1]) })
