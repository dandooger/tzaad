package com.dandooger.tzaad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private val ROW_HEIGHT = 64.dp
private val ROW_GAP = 8.dp

/** Drag habits by the ⠿ handle to change their order (in the app and in the widget). */
@Composable
fun ReorderScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember { Store.habits.toMutableStateList() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (ROW_HEIGHT + ROW_GAP).toPx() }

    fun save() {
        Store.reorder(ctx, list.map { it.id })
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
            Text("סידור ההרגלים", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "תחזיק את ⠿ וגרור למעלה או למטה. ככה הם יופיעו גם בווידג'ט.",
            color = Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp),
        )

        list.forEachIndexed { index, h ->
            key(h.id) {
                val dragging = index == dragIndex
                Row(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            scaleX = if (dragging) 1.03f else 1f
                            scaleY = if (dragging) 1.03f else 1f
                        }
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (dragging) LightBlue else Color.White)
                        .border(if (dragging) 2.dp else 1.dp, if (dragging) Blue else Line, RoundedCornerShape(16.dp))
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(h.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(h.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(ROW_HEIGHT)
                            .pointerInput(h.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragIndex = list.indexOfFirst { it.id == h.id }
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (dragIndex < 0) return@detectDragGestures
                                        dragOffset += amount.y
                                        val to = (dragIndex + (dragOffset / stepPx).roundToInt())
                                            .coerceIn(0, list.lastIndex)
                                        if (to != dragIndex) {
                                            list.add(to, list.removeAt(dragIndex))
                                            dragOffset -= (to - dragIndex) * stepPx
                                            dragIndex = to
                                        }
                                    },
                                    onDragEnd = {
                                        dragIndex = -1
                                        dragOffset = 0f
                                        save()
                                    },
                                    onDragCancel = {
                                        dragIndex = -1
                                        dragOffset = 0f
                                        save()
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("⠿", fontSize = 28.sp, color = Gray) }
                }
                Spacer(Modifier.height(ROW_GAP))
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = back, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("סיימתי", fontSize = 18.sp) }
    }
}
