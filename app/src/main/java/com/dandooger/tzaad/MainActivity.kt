package com.dandooger.tzaad

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val Blue = Color(0xFF1E6FD9)
val DarkBlue = Color(0xFF0C447C)
val LightBlue = Color(0xFFE6F1FB)
val Bg = Color(0xFFF5F8FC)
val Line = Color(0xFFDDE3EA)
val Gray = Color(0xFF7A8496)
val Gold = Color(0xFF8A5A00)
val GoldLight = Color(0xFFFFF1CC)
val MissedBg = Color(0xFFFBE0E0)
val MissedFg = Color(0xFFA32D2D)
val OffBg = Color(0xFFEEF1F5)

val HE: Locale = Locale.forLanguageTag("he")
val DAY_LETTERS = mapOf(7 to "א", 1 to "ב", 2 to "ג", 3 to "ד", 4 to "ה", 5 to "ו", 6 to "ש")
val WEEK = listOf(7, 1, 2, 3, 4, 5)
val EMOJIS = listOf(
    "⭐", "💧", "📚", "💪", "🏃", "🐕", "🙏", "📖", "🎸", "🧘",
    "🛏️", "🍎", "🦷", "📵", "🍬", "✍️", "🧹", "💰", "🎮", "☀️",
)

sealed interface Screen {
    data object Today : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
    data object Reorder : Screen
}

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        setContent {
            TzaadTheme { App(onReminderSet = ::ensureNotificationPermission) }
        }
    }

    override fun onResume() {
        super.onResume()
        // The widget may have changed things, or a new day started.
        Store.version.value++
        TzaadWidget.refresh(this)
        Reminders.scheduleAll(this)
    }
}

@Composable
fun TzaadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            primaryContainer = LightBlue,
            onPrimaryContainer = DarkBlue,
            background = Bg,
            surface = Color.White,
        )
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}

@Composable
fun App(onReminderSet: () -> Unit) {
    Store.version.value
    var screen by remember { mutableStateOf<Screen>(Screen.Today) }
    val today = LocalDate.now()

    Surface(Modifier.fillMaxSize(), color = Bg) {
        when (val s = screen) {
            Screen.Today -> TodayScreen(
                today,
                open = { screen = Screen.Detail(it) },
                add = { screen = Screen.Edit(null) },
                reorder = { screen = Screen.Reorder },
            )

            Screen.Reorder -> {
                BackHandler { screen = Screen.Today }
                ReorderScreen(back = { screen = Screen.Today })
            }

            is Screen.Detail -> {
                BackHandler { screen = Screen.Today }
                val h = Store.habit(s.id)
                if (h == null) LaunchedEffect(Unit) { screen = Screen.Today }
                else DetailScreen(h, today, back = { screen = Screen.Today }, edit = { screen = Screen.Edit(h.id) })
            }

            is Screen.Edit -> {
                val back = { screen = if (s.id != null) Screen.Detail(s.id) else Screen.Today }
                BackHandler { back() }
                EditScreen(
                    existing = s.id?.let { Store.habit(it) },
                    onDone = back,
                    onDeleted = { screen = Screen.Today },
                    onReminderSet = onReminderSet,
                )
            }
        }
    }
}

// ───────────────────────── Today ─────────────────────────

@Composable
fun TodayScreen(today: LocalDate, open: (String) -> Unit, add: () -> Unit, reorder: () -> Unit) {
    Store.version.value
    val rest = JewishDays.restName(today)
    val todays = Store.habits.filter { it.activeOn(today) }
    val others = Store.habits.filter { !it.activeOn(today) }
    // Today's score counts the daily habits; weekly ones have their own weekly goal.
    val daily = todays.filter { !it.isWeekly }
    val done = daily.count { it.done(today) }

    Scaffold(
        containerColor = Bg,
        floatingActionButton = {
            FloatingActionButton(onClick = add, containerColor = Blue, contentColor = Color.White) {
                Icon(Icons.Filled.Add, contentDescription = "הוסף הרגל")
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header(today, if (Store.habits.size > 1) reorder else null) }
            if (rest != null) item { RestBanner(rest) }
            if (daily.isNotEmpty()) item { ProgressCard(done, daily.size) }
            if (Store.habits.isEmpty()) item { EmptyState(add) }
            items(todays, key = { it.id }) { h ->
                HabitCard(h, today, active = true, onOpen = { open(h.id) })
            }
            if (others.isNotEmpty()) {
                item {
                    Text(
                        if (rest != null) "היום יום מנוחה" else "לא היום",
                        color = Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(others, key = { "off-" + it.id }) { h ->
                    HabitCard(h, today, active = false, onOpen = { open(h.id) })
                }
            }
        }
    }
}

@Composable
fun Header(today: LocalDate, reorder: (() -> Unit)?) {
    val hour = LocalTime.now().hour
    val hello = when {
        hour in 5..11 -> "בוקר טוב"
        hour in 12..16 -> "צהריים טובים"
        hour in 17..20 -> "ערב טוב"
        else -> "לילה טוב"
    }
    val greg = today.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", HE))
    val heb = JewishDays.hebrewDate(today)
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("צעד", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Blue, modifier = Modifier.weight(1f))
            if (reorder != null) TextButton(onClick = reorder) { Text("↕ סדר", fontSize = 16.sp) }
        }
        Text("$hello, דניאל 👋", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(if (heb.isEmpty()) greg else "$greg · $heb", fontSize = 14.sp, color = Gray)
    }
}

@Composable
fun RestBanner(rest: String) {
    val title = if (rest == "שבת") "שבת שלום 🕯️" else "$rest – חג שמח 🕯️"
    Card(colors = CardDefaults.cardColors(containerColor = GoldLight)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Gold)
            Text("היום יום מנוחה – הרצפים שלך שמורים ולא יישברו.", color = Gold, fontSize = 14.sp)
        }
    }
}

@Composable
fun ProgressCard(done: Int, total: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (done == total) "סיימת הכול להיום ✓" else "עשית $done מתוך $total היום",
                fontWeight = FontWeight.Medium, fontSize = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = Blue,
                trackColor = LightBlue,
            )
        }
    }
}

@Composable
fun EmptyState(add: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("👣", fontSize = 40.sp)
            Text("כל דרך מתחילה בצעד אחד", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("הוסף את ההרגל הראשון שלך", color = Gray)
            Spacer(Modifier.height(12.dp))
            Button(onClick = add) { Text("הוסף הרגל") }
        }
    }
}

@Composable
fun HabitCard(h: Habit, today: LocalDate, active: Boolean, onOpen: () -> Unit) {
    Store.version.value
    val ctx = LocalContext.current
    val c = Store.count(h, today)
    val done = h.done(today)
    val streak = h.streak(today)
    val highlight = active && done && h.type != HabitType.QUIT
    val slipped = h.type == HabitType.QUIT && c > 0
    var showWheel by remember { mutableStateOf(false) }
    if (showWheel) AmountDialog(h) { showWheel = false }

    val week = weekStart(today)
    val countText = if (h.type == HabitType.COUNT) "$c מתוך ${h.target} · " else ""
    val subtitle = when {
        !active && JewishDays.isRestDay(today) -> "🔥 רצף: $streak – שמור"
        h.isWeekly && !active -> "✓ השלמת את השבוע · 🔥 $streak שבועות"
        h.isWeekly -> "${countText}השבוע: ${h.weekCount(week)} מתוך ${h.weekGoal(week)} · 🔥 $streak שבועות"
        !active -> "לא מתוכנן להיום · 🔥 $streak"
        h.type == HabitType.QUIT && slipped -> "נפלת היום – מחר מתחילים מחדש 💙"
        h.type == HabitType.QUIT -> "💪 $streak ימים ברצף בלי"
        h.type == HabitType.COUNT -> "$c מתוך ${h.target} · 🔥 $streak"
        streak == 0 -> "🔥 בוא נתחיל רצף"
        else -> "🔥 רצף: $streak ימים"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlight) LightBlue else Color.White)
            .border(1.dp, if (highlight) Blue else Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (active) LightBlue else OffBg),
            contentAlignment = Alignment.Center,
        ) { Text(h.emoji, fontSize = 22.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                h.name, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = if (active) Color.Black else Gray,
            )
            Text(subtitle, fontSize = 13.sp, color = if (slipped) MissedFg else Gray)
        }
        if (active) {
            when (h.type) {
                HabitType.CHECK -> TapCircle(done, "") { Store.tap(ctx, h, today) }
                HabitType.COUNT -> if (h.usesWheel()) {
                    TapCircle(done, "+") { showWheel = true }
                } else {
                    if (c > 0) {
                        TextButton(onClick = { Store.setCount(ctx, h, today, c - 1) }) {
                            Text("−", fontSize = 22.sp, color = Gray)
                        }
                    }
                    TapCircle(done, "+") { Store.tap(ctx, h, today) }
                }
                HabitType.QUIT -> {
                    if (slipped) TextButton(onClick = { Store.tap(ctx, h, today) }) { Text("בטל") }
                    else OutlinedButton(onClick = { Store.tap(ctx, h, today) }) { Text("נפלתי", color = MissedFg) }
                }
            }
        }
    }
}

@Composable
fun TapCircle(done: Boolean, todoLabel: String, onTap: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (done) Blue else Color.White)
            .border(2.dp, if (done) Blue else Line, CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (done) "✓" else todoLabel,
            color = if (done) Color.White else Blue,
            fontSize = 22.sp, fontWeight = FontWeight.Bold,
        )
    }
}

// ───────────────────────── Habit details ─────────────────────────

@Composable
fun DetailScreen(h: Habit, today: LocalDate, back: () -> Unit, edit: () -> Unit) {
    Store.version.value
    var month by remember { mutableStateOf(YearMonth.from(today)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
            Text(
                "${h.emoji} ${h.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = edit) { Icon(Icons.Filled.Edit, contentDescription = "עריכה", tint = Blue) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(
                when {
                    h.type == HabitType.QUIT -> "ימים בלי"
                    h.isWeekly -> "שבועות ברצף"
                    else -> "רצף עכשיו"
                },
                "${h.streak(today)} 🔥",
            )
            Stat("השיא שלי", "${h.bestStreak(today)}")
            Stat("ימים שהצלחתי", "${h.totalDone(today)}")
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            MonthCalendar(
                h, month, today,
                onPrev = { month = month.minusMonths(1) },
                onNext = { if (month < YearMonth.from(today)) month = month.plusMonths(1) },
            )
        }

        Legend()

        val daysText = when {
            h.isWeekly -> "${h.perWeek} פעמים בשבוע, באיזה יום שבא לך"
            h.days.size == WEEK.size -> "כל יום חוץ משבת"
            else -> WEEK.filter { it in h.days }.joinToString(" ") { DAY_LETTERS.getValue(it) + "׳" }
        }
        Text("📅 $daysText", color = Gray, fontSize = 14.sp)
        h.reminder?.let { Text("⏰ תזכורת: ${fmtTime(it)}", color = Gray, fontSize = 14.sp) }
        if (h.type == HabitType.COUNT) Text("🎯 מטרה: ${h.target} ביום", color = Gray, fontSize = 14.sp)
    }
}

@Composable
fun RowScope.Stat(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.weight(1f),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = Gray)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
        }
    }
}

@Composable
fun MonthCalendar(h: Habit, month: YearMonth, today: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
    Store.version.value
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrev) { Text("›", fontSize = 26.sp) }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", HE)),
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold, fontSize = 17.sp,
            )
            TextButton(onClick = onNext) {
                Text("‹", fontSize = 26.sp, color = if (month < YearMonth.from(today)) Blue else Line)
            }
        }
        Row {
            listOf("א", "ב", "ג", "ד", "ה", "ו", "ש").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Gray, fontSize = 12.sp)
            }
        }
        val offset = month.atDay(1).dayOfWeek.value % 7 // Sunday = 0
        val length = month.lengthOfMonth()
        val rows = (offset + length + 6) / 7
        for (r in 0 until rows) {
            Row {
                for (col in 0 until 7) {
                    val day = r * 7 + col - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                        if (day in 1..length) DayCell(h, month.atDay(day), today)
                    }
                }
            }
        }
    }
}

@Composable
fun DayCell(h: Habit, date: LocalDate, today: LocalDate) {
    val (bg, fg) = when {
        date.isAfter(today) -> Color.Transparent to Line
        JewishDays.isRestDay(date) -> GoldLight to Gold
        date.isBefore(h.createdDate()) -> Color.Transparent to Gray
        h.done(date) && (h.required(date) || h.type != HabitType.QUIT) -> Blue to Color.White
        !h.required(date) -> OffBg to Gray
        date == today -> Color.White to Blue
        h.type == HabitType.COUNT && Store.count(h, date) > 0 -> LightBlue to DarkBlue
        else -> MissedBg to MissedFg
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (date == today) Modifier.border(2.dp, Blue, RoundedCornerShape(8.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text("${date.dayOfMonth}", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(Blue, "הצלחתי")
        LegendItem(MissedBg, "פספסתי")
        LegendItem(GoldLight, "שבת / חג")
        LegendItem(OffBg, "לא מתוכנן")
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = Gray)
    }
}

// ───────────────────────── Add / edit ─────────────────────────

@Composable
fun EditScreen(existing: Habit?, onDone: () -> Unit, onDeleted: () -> Unit, onReminderSet: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "⭐") }
    var type by remember { mutableStateOf(existing?.type ?: HabitType.CHECK) }
    var target by remember { mutableStateOf(existing?.takeIf { it.type == HabitType.COUNT }?.target ?: 8) }
    var days by remember { mutableStateOf(existing?.days ?: WEEK.toSet()) }
    var reminder by remember { mutableStateOf(existing?.reminder) }
    var weekly by remember { mutableStateOf((existing?.perWeek ?: 0) > 0) }
    var perWeek by remember { mutableStateOf(existing?.perWeek?.takeIf { it > 0 } ?: 2) }
    var error by remember { mutableStateOf<String?>(null) }
    val isWeekly = weekly && type != HabitType.QUIT
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
            Text(if (existing == null) "הרגל חדש" else "עריכת הרגל", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Label("איך קוראים להרגל?")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            placeholder = { Text("לשתות מים") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Label("בחר אמוג'י")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EMOJIS.forEach { e ->
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (e == emoji) LightBlue else Color.White)
                        .border(if (e == emoji) 2.dp else 1.dp, if (e == emoji) Blue else Line, CircleShape)
                        .clickable { emoji = e },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 24.sp) }
            }
        }

        Label("איזה סוג הרגל?")
        Choice("✓  רגיל – מסמנים וי", "למשל: לקרוא 20 דקות", type == HabitType.CHECK) { type = HabitType.CHECK }
        Choice("🔢  עם מספר", "למשל: 8 כוסות מים ביום", type == HabitType.COUNT) { type = HabitType.COUNT }
        Choice("🚫  להפסיק משהו", "הצלחה = לא עשיתי. למשל: בלי ממתקים", type == HabitType.QUIT) { type = HabitType.QUIT }

        if (type == HabitType.COUNT) {
            Label("כמה פעמים ביום? גלול למספר")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NumberWheel(target, 2, MAX_WHEEL) { target = it }
            }
        }

        if (type != HabitType.QUIT) {
            Label("באיזו תדירות?")
            Choice("📅  בימים קבועים", "בוחרים באילו ימים בשבוע", !weekly) { weekly = false; error = null }
            Choice("🗓️  כמה פעמים בשבוע", "באיזה יום שבא לך. למשל: ריצה פעמיים בשבוע", weekly) { weekly = true; error = null }
        }

        if (isWeekly) {
            Label("כמה פעמים בשבוע? גלול למספר")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NumberWheel(perWeek, 1, 6) { perWeek = it }
            }
        } else {
            Label("באילו ימים?")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEK.forEach { d ->
                    DayChip(DAY_LETTERS.getValue(d), d in days, rest = false) {
                        days = if (d in days) days - d else days + d
                        error = null
                    }
                }
                DayChip("ש", selected = false, rest = true) {}
            }
        }
        Text("🕯️ שבת וחגים הם ימי מנוחה – הרצף לא נשבר בהם", fontSize = 13.sp, color = Gold)

        Label("תזכורת")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = reminder != null, onCheckedChange = { reminder = if (it) 20 * 60 else null })
            Spacer(Modifier.width(12.dp))
            val r = reminder
            if (r != null) {
                TextButton(onClick = {
                    TimePickerDialog(ctx, { _, hh, mm -> reminder = hh * 60 + mm }, r / 60, r % 60, true).show()
                }) { Text("⏰ ${fmtTime(r)}", fontSize = 20.sp) }
            } else Text("בלי תזכורת", color = Gray)
        }
        if (reminder != null) {
            Text(
                "ביום שישי ובערב חג התזכורת תגיע עד 13:00, ובשבת ובחג לא יגיעו תזכורות.",
                fontSize = 13.sp, color = Gray,
            )
        }

        error?.let { Text(it, color = MissedFg, fontWeight = FontWeight.Medium) }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                when {
                    name.isBlank() -> error = "צריך לתת שם להרגל"
                    !isWeekly && days.isEmpty() -> error = "צריך לבחור לפחות יום אחד"
                    else -> {
                        val base = existing ?: Habit(name = "", emoji = "", type = type)
                        Store.saveHabit(
                            ctx,
                            base.copy(
                                name = name.trim(), emoji = emoji, type = type,
                                target = if (type == HabitType.COUNT) target else 1,
                                days = if (days.isEmpty()) WEEK.toSet() else days,
                                reminder = reminder,
                                perWeek = if (isWeekly) perWeek else 0,
                            ),
                        )
                        if (reminder != null) onReminderSet()
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("שמור", fontSize = 18.sp) }

        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("מחק הרגל", color = MissedFg)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק את ההרגל?") },
            text = { Text("כל ההיסטוריה והרצף של \"${existing.name}\" יימחקו.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    Store.deleteHabit(ctx, existing.id)
                    onDeleted()
                }) { Text("מחק", color = MissedFg) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } },
        )
    }
}

@Composable
fun Label(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Choice(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) LightBlue else Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) Blue else Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = if (selected) DarkBlue else Color.Black)
        Text(sub, fontSize = 13.sp, color = Gray)
    }
}

@Composable
fun DayChip(label: String, selected: Boolean, rest: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                when {
                    rest -> GoldLight
                    selected -> Blue
                    else -> Color.White
                }
            )
            .border(1.dp, if (selected || rest) Color.Transparent else Line, CircleShape)
            .clickable(enabled = !rest, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                rest -> Gold
                selected -> Color.White
                else -> Color.Black
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

fun fmtTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
