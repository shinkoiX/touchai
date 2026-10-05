package app.touchai.android

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CropEditor(bitmap, crop, { crop = it }, Modifier.weight(0.65f).fillMaxHeight(), enabled = true)
                Column(Modifier.weight(0.35f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Crop screenshot", style = MaterialTheme.typography.titleLarge)
                    Text("Drag a corner to resize. Reset selects the full image.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onApply(crop) }, modifier = Modifier.fillMaxWidth()) { Text("Apply crop") }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Crop screenshot", style = MaterialTheme.typography.titleLarge)
                Text("Drag a corner to resize. Reset selects the full image.", style = MaterialTheme.typography.bodySmall)
                CropEditor(bitmap, crop, { crop = it }, Modifier.weight(1f).fillMaxWidth(), enabled = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { onApply(crop) }, modifier = Modifier.weight(1f)) { Text("Apply crop") }
                }
            }
        }
    }
}

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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!panMode, { panMode = false }, label = { Text("Adjust crop") }, enabled = enabled)
            FilterChip(panMode, { panMode = true }, label = { Text("Pan / zoom") }, enabled = enabled)
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero; onCrop(ImageCrop.Full) }, enabled = enabled) { Text("Reset") }
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Zoom ${((zoom * 10).roundToInt() / 10f)}×", style = MaterialTheme.typography.labelMedium)
            val pixels = crop.pixels(bitmap.width, bitmap.height)
            Text("${pixels.width} × ${pixels.height} px", style = MaterialTheme.typography.labelMedium)
        }
        Slider(zoom, { zoom = it; pan = constrainPan(pan) }, valueRange = 1f..4f, enabled = enabled)
    }
}

private val CropSaver = listSaver<ImageCrop, Float>(save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { ImageCrop(it[0], it[1], it[2], it[3]) })
private val PanSaver = listSaver<Offset, Float>(save = { listOf(it.x, it.y) }, restore = { Offset(it[0], it[1]) })
