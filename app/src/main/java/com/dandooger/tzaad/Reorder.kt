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

/** One line in the sorting screen: a category title (id "" = "בלי קטגוריה") or a habit. */
private data class Entry(val id: String, val isHeader: Boolean)

/** Categories in order, each followed by its habits; "בלי קטגוריה" always last. */
private fun buildEntries(): List<Entry> {
    val list = mutableListOf<Entry>()
    val known = Store.categories.map { it.id }.toSet()
    for (c in Store.categories) {
        list += Entry(c.id, true)
        Store.habits.filter { it.category == c.id }.forEach { list += Entry(it.id, false) }
    }
    list += Entry("", true)
    Store.habits.filter { it.category !in known }.forEach { list += Entry(it.id, false) }
    return list
}

/** Split the list into blocks: a title and the habits under it. */
private fun blocks(entries: List<Entry>): MutableList<List<Entry>> {
    val out = mutableListOf<MutableList<Entry>>()
    for (e in entries) if (e.isHeader) out += mutableListOf(e) else out.last() += e
    return out.map { it.toList() }.toMutableList()
}

private fun Modifier.dragHandle(key1: Any, key2: Any, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) =
    pointerInput(key1, key2) {
        detectDragGestures(
            onDragStart = { onStart() },
            onDrag = { change, amount ->
                change.consume()
                onDrag(amount.y)
            },
            onDragEnd = { onEnd() },
            onDragCancel = { onEnd() },
        )
    }

/**
 * Everything in one list. Drag a habit ⠿ anywhere – also into another category.
 * Drag a category ⠿ and the list folds to just the categories; it moves with all its habits.
 */
@Composable
fun ReorderScreen(back: () -> Unit, categories: () -> Unit) {
    Store.version.value
    val ctx = LocalContext.current
    // Rebuild only when the layout really changed (not on every clock tick).
    val signature = Store.categories.joinToString { it.id } + "|" +
        Store.habits.joinToString { it.id + ":" + it.category }
    val entries = remember(signature) { buildEntries().toMutableStateList() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var catDrag by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (ROW_HEIGHT + ROW_GAP).toPx() }

    fun save() {
        var cat = ""
        val catOrder = mutableListOf<String>()
        val habitOrder = mutableListOf<Pair<String, String>>()
        for (e in entries) {
            if (e.isHeader) {
                cat = e.id
                if (e.id.isNotEmpty()) catOrder += e.id
            } else habitOrder += e.id to cat
        }
        Store.applyLayout(ctx, catOrder, habitOrder)
    }

    fun end() {
        dragId = null
        catDrag = false
        offset = 0f
        save()
    }

    fun dragHabit(dy: Float) {
        val id = dragId ?: return
        offset += dy
        val from = entries.indexOfFirst { it.id == id && !it.isHeader }
        if (from < 0) return
        // Never above the first title.
        val to = (from + (offset / stepPx).roundToInt()).coerceIn(1, entries.lastIndex)
        if (to != from) {
            entries.add(to, entries.removeAt(from))
            offset -= (to - from) * stepPx
        }
    }

    fun dragCategory(dy: Float) {
        val id = dragId ?: return
        offset += dy
        val bs = blocks(entries)
        val from = bs.indexOfFirst { it.first().id == id }
        if (from < 0) return
        // "בלי קטגוריה" stays last.
        val to = (from + (offset / stepPx).roundToInt()).coerceIn(0, bs.lastIndex - 1)
        if (to != from) {
            bs.add(to, bs.removeAt(from))
            entries.clear()
            entries.addAll(bs.flatten())
            offset -= (to - from) * stepPx
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
            Text("סידור", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "תחזיק את ⠿ וגרור. משימה אפשר לגרור גם לתוך קטגוריה אחרת.\n" +
                "כשגוררים קטגוריה – כל מה שבתוכה זז איתה. ככה זה יופיע גם בווידג'ט.",
            color = Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedButton(onClick = categories, modifier = Modifier.fillMaxWidth()) {
            Text("🗂️ להוסיף, לשנות שם או למחוק קטגוריות", fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))

        val shown = if (catDrag) entries.filter { it.isHeader } else entries
        shown.forEachIndexed { index, e ->
            key(e.id, e.isHeader) {
                val dragging = e.id == dragId && e.isHeader == catDrag
                val moving = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) offset else 0f
                        scaleX = if (dragging) 1.03f else 1f
                        scaleY = if (dragging) 1.03f else 1f
                    }
                if (e.isHeader) {
                    Column(moving.fillMaxWidth().height(ROW_HEIGHT)) {
                        // Thin black line where one category ends and the next begins.
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(if (dragging) LightBlue else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (e.id.isEmpty()) "בלי קטגוריה" else Store.category(e.id)?.name ?: "",
                                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DarkBlue,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                            )
                            if (e.id.isNotEmpty()) {
                                Box(
                                    Modifier.size(ROW_HEIGHT).dragHandle(
                                        e.id, entries,
                                        onStart = { dragId = e.id; catDrag = true; offset = 0f },
                                        onDrag = { dragCategory(it) },
                                        onEnd = { end() },
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) { Text("⠿", fontSize = 28.sp, color = DarkBlue) }
                            }
                        }
                    }
                } else {
                    val h = Store.habit(e.id)
                    Row(
                        moving
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(ROW_HEIGHT)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (dragging) LightBlue else Color.White)
                            .border(if (dragging) 2.dp else 1.dp, if (dragging) Blue else Line, RoundedCornerShape(16.dp))
                            .padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            h?.let { "${it.emoji} ${it.name}" } ?: "",
                            fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier.size(ROW_HEIGHT).dragHandle(
                                e.id, entries,
                                onStart = { dragId = e.id; catDrag = false; offset = 0f },
                                onDrag = { dragHabit(it) },
                                onEnd = { end() },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Text("⠿", fontSize = 28.sp, color = Gray) }
                    }
                }
                Spacer(Modifier.height(ROW_GAP))
            }
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
