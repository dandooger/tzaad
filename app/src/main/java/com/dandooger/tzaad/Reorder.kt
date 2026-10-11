package com.dandooger.tzaad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

private val ROW_HEIGHT = 60.dp
private val ROW_GAP = 8.dp

/**
 * A list you reorder by dragging the ⠿ handle. [onMoved] gets the new order of ids
 * when the finger is lifted.
 */
@Composable
fun DragList(
    ids: List<String>,
    label: (String) -> String,
    onMoved: (List<String>) -> Unit,
    trailing: @Composable RowScope.(String) -> Unit = {},
) {
    val list = remember(ids) { ids.toMutableStateList() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (ROW_HEIGHT + ROW_GAP).toPx() }
    val movedCallback by rememberUpdatedState(onMoved)

    Column {
        list.forEachIndexed { index, id ->
            key(id) {
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
                    Text(label(id), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    trailing(id)
                    Box(
                        Modifier
                            .size(ROW_HEIGHT)
                            .pointerInput(id, list) {
                                fun end() {
                                    dragIndex = -1
                                    dragOffset = 0f
                                    movedCallback(list.toList())
                                }
                                detectDragGestures(
                                    onDragStart = {
                                        dragIndex = list.indexOf(id)
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
                                    onDragEnd = { end() },
                                    onDragCancel = { end() },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("⠿", fontSize = 28.sp, color = Gray) }
                }
                Spacer(Modifier.height(ROW_GAP))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DarkBlue,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}

/** Order the habits inside each category (the same order shows in the widget). */
@Composable
fun ReorderScreen(back: () -> Unit, categories: () -> Unit) {
    Store.version.value
    val ctx = LocalContext.current
    val groups = Store.grouped(Store.habits.toList())
    val showTitles = groups.any { it.first != null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
            Text("סידור", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "תחזיק את ⠿ וגרור למעלה או למטה. ככה זה יופיע גם בווידג'ט.\n" +
                "כדי להעביר הרגל לקטגוריה אחרת – לחץ עליו במסך הראשי ואז ✏️.",
            color = Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedButton(onClick = categories, modifier = Modifier.fillMaxWidth()) {
            Text("🗂️ הקטגוריות שלי – להוסיף, למחוק ולסדר", fontSize = 15.sp)
        }

        for ((cat, hs) in groups) {
            if (showTitles) SectionTitle(cat?.name ?: "בלי קטגוריה")
            DragList(
                ids = hs.map { it.id },
                label = { id -> Store.habit(id)?.let { "${it.emoji} ${it.name}" } ?: "" },
                onMoved = { Store.reorderWithin(ctx, it) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = back, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("סיימתי", fontSize = 18.sp) }
    }
}

/** Add, rename, delete and order the categories. */
@Composable
fun CategoriesScreen(back: () -> Unit) {
    Store.version.value
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
            Text("הקטגוריות שלי", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "גרור את ⠿ כדי לקבוע איזו קטגוריה למעלה. ✏️ משנה שם, 🗑️ מוחק.",
            color = Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp),
        )

        DragList(
            ids = Store.categories.map { it.id },
            label = { id -> Store.category(id)?.name ?: "" },
            onMoved = { Store.reorderCategories(ctx, it) },
            trailing = { id ->
                TextButton(onClick = { renaming = Store.category(id) }) { Text("✏️", fontSize = 18.sp) }
                TextButton(onClick = { deleting = Store.category(id) }) { Text("🗑️", fontSize = 18.sp) }
            },
        )
        if (Store.categories.isEmpty()) Text("אין עדיין קטגוריות.", color = Gray)

        SectionTitle("קטגוריה חדשה")
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            placeholder = { Text("למשל: 🏠 בבית") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (newName.isNotBlank()) {
                    Store.addCategory(ctx, newName)
                    newName = ""
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("➕ הוסף קטגוריה", fontSize = 16.sp) }
        Spacer(Modifier.height(24.dp))
    }

    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("שינוי שם") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) Store.renameCategory(ctx, c.id, name)
                    renaming = null
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("ביטול") } },
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("למחוק את \"${c.name}\"?") },
            text = { Text("ההרגלים שבה לא יימחקו – הם רק יעברו ל\"בלי קטגוריה\".") },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteCategory(ctx, c.id)
                    deleting = null
                }) { Text("מחק", color = MissedFg) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ביטול") } },
        )
    }
}
