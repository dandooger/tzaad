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
import kotlinx.coroutines.launch

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
    data object Categories : Screen
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
    // Check the clock every half minute, so the app closes/opens right on time.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            Store.version.value++
        }
    }
    val rest = JewishDays.currentRest()

    Surface(Modifier.fillMaxSize(), color = Bg) {
        if (rest != null) {
            ShabbatScreen(rest)
        } else when (val s = screen) {
            Screen.Today -> TodayScreen(
                today,
                // Tasks have no streak or calendar – open them straight for editing.
                open = { screen = if (Store.habit(it)?.isTask == true) Screen.Edit(it) else Screen.Detail(it) },
                add = { screen = Screen.Edit(null) },
                reorder = { screen = Screen.Reorder },
            )

            Screen.Reorder -> {
                BackHandler { screen = Screen.Today }
                ReorderScreen(back = { screen = Screen.Today }, categories = { screen = Screen.Categories })
            }

            Screen.Categories -> {
                BackHandler { screen = Screen.Reorder }
                CategoriesScreen(back = { screen = Screen.Reorder })
            }

            is Screen.Detail -> {
                BackHandler { screen = Screen.Today }
                val h = Store.habit(s.id)
                if (h == null) LaunchedEffect(Unit) { screen = Screen.Today }
                else DetailScreen(h, today, back = { screen = Screen.Today }, edit = { screen = Screen.Edit(h.id) })
            }

            is Screen.Edit -> {
                val back = {
                    val h = s.id?.let { Store.habit(it) }
                    screen = if (h != null && !h.isTask) Screen.Detail(h.id) else Screen.Today
                }
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
    val todays = Store.habits.filter { it.markableOn(today) }
    val others = Store.habits.filter { !it.markableOn(today) }
    // Today's score counts the daily habits; weekly ones have their own weekly goal.
    // Every kind counts: daily, weekly, quit, succeed, one-time tasks and homework.
    val (done, total) = todayProgress(today, todays)
    val candle = JewishDays.candleToday(today)
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // A one-time task marked done disappears – with a few seconds to undo.
    val taskDone: (Habit) -> Unit = { h ->
        val index = Store.habits.indexOfFirst { it.id == h.id }
        Store.tap(ctx, h, today)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("✓ ${h.name} – בוצע", actionLabel = "בטל", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) Store.restore(ctx, h, index)
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
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
            if (rest != null) item { MotzaeiBanner(rest) }
            if (candle != null) item { ErevBanner(JewishDays.restName(today.plusDays(1)) ?: "שבת", candle) }
            if (total > 0) item { ProgressCard(done, total) }
            if (Store.habits.isEmpty()) item { EmptyState(add) }
            val groups = Store.grouped(todays)
            val showTitles = groups.any { it.first != null }
            groups.forEachIndexed { gi, (cat, hs) ->
                if (showTitles) item(key = "cat-" + (cat?.id ?: "none")) {
                    Column {
                        // Thin black line where one category ends and the next begins.
                        if (gi > 0) Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(Color.Black))
                        SectionTitle(cat?.name ?: "בלי קטגוריה")
                    }
                }
                items(hs, key = { it.id }) { h ->
                    HabitCard(h, today, active = true, onOpen = { open(h.id) }, onTaskDone = { taskDone(h) })
                }
            }
            if (others.isNotEmpty()) {
                item {
                    Text(
                        "לא היום",
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

/** Shown on Shabbat/chag: the app is closed until it goes out. */
@Composable
fun ShabbatScreen(rest: JewishDays.RestWindow) {
    Column(
        Modifier.fillMaxSize().background(GoldLight).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🕯️🕯️", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            rest.greeting.removeSuffix(" 🕯️"), fontSize = 32.sp, fontWeight = FontWeight.Bold,
            color = Gold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text("צעד סגורה עכשיו", fontSize = 18.sp, color = Gold)
        Text("ותיפתח לבד ${rest.opensAt}", fontSize = 18.sp, color = Gold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text("הרצפים שלך שמורים 💙", fontSize = 16.sp, color = Gold)
    }
}

@Composable
fun ErevBanner(name: String, candle: java.time.Instant) {
    val what = if (name == "שבת") "השבת נכנסת" else "$name נכנס"
    Card(colors = CardDefaults.cardColors(containerColor = GoldLight)) {
        Column(Modifier.padding(16.dp)) {
            Text("🕯️ $what היום ב־${JewishDays.hhmm(candle)}", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Gold)
            Text("מה שלא תספיק היום לא ישבור את הרצף, ומה שכן תעשה – בונוס.", color = Gold, fontSize = 14.sp)
        }
    }
}

@Composable
fun MotzaeiBanner(rest: String) {
    Card(colors = CardDefaults.cardColors(containerColor = GoldLight)) {
        Column(Modifier.padding(16.dp)) {
            Text(if (rest == "שבת") "שבוע טוב! 🌟" else "מועדים לשמחה! 🌟", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Gold)
            Text("מה שתסמן הערב הוא בונוס לרצף.", color = Gold, fontSize = 14.sp)
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
fun HabitCard(h: Habit, today: LocalDate, active: Boolean, onOpen: () -> Unit, onTaskDone: () -> Unit = {}) {
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
        h.type == HabitType.HOMEWORK -> h.note.ifBlank { "שיעורי בית" }
        h.type == HabitType.ONCE -> "חד פעמי – נמחק כשמסמנים וי"
        !active && JewishDays.isRestDay(today) -> "🔥 רצף: $streak – שמור"
        h.isWeekly && !active -> "✓ השלמת את השבוע · 🔥 $streak שבועות"
        h.isWeekly -> "${countText}השבוע: ${h.weekCount(week)} מתוך ${h.weekGoal(week)} · 🔥 $streak שבועות"
        !active -> "לא מתוכנן להיום · 🔥 $streak"
        JewishDays.isRestDay(today) -> "${countText}בונוס 🌟 · 🔥 $streak"
        h.type == HabitType.QUIT && slipped -> "נפסלת היום – מחר מתחילים מחדש 💙"
        h.type == HabitType.QUIT -> "💪 $streak ימים ברצף בלי"
        h.type == HabitType.SUCCEED && done -> "🏆 הצלחת היום! · 🔥 $streak"
        h.type == HabitType.SUCCEED -> "🏆 $streak ימים ברצף שהצלחתי"
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
            Text(
                subtitle, fontSize = 13.sp, color = if (slipped) MissedFg else Gray,
                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
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
                    else OutlinedButton(onClick = { Store.tap(ctx, h, today) }) { Text("נפסלתי", color = MissedFg) }
                }
                HabitType.ONCE, HabitType.HOMEWORK -> TapCircle(false, "", onTaskDone)
                HabitType.SUCCEED -> {
                    if (done) TextButton(onClick = { Store.tap(ctx, h, today) }) { Text("בטל") }
                    else OutlinedButton(onClick = { Store.tap(ctx, h, today) }) { Text("🏆 הצלחתי", color = Blue) }
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
        date.isBefore(h.createdDate()) -> (if (JewishDays.isRestDay(date)) GoldLight else Color.Transparent) to Gray
        h.done(date) && (h.required(date) || h.type != HabitType.QUIT) -> Blue to Color.White
        JewishDays.isRestDay(date) -> GoldLight to Gold
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
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var addingCategory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isTask = type == HabitType.ONCE || type == HabitType.HOMEWORK
    val isHomework = type == HabitType.HOMEWORK
    val isWeekly = weekly && type != HabitType.QUIT && !isTask
    fun pickType(t: HabitType) {
        type = t
        if (t == HabitType.HOMEWORK && emoji == "⭐") emoji = "📚"
        if (t == HabitType.HOMEWORK && category.isEmpty()) {
            Store.categories.find { it.name.contains("שיעורי בית") }?.let { category = it.id }
        }
        error = null
    }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
            Text(
                when {
                    isHomework -> "שיעורי בית"
                    isTask -> "משימה חד פעמית"
                    existing == null -> "הרגל חדש"
                    else -> "עריכת הרגל"
                },
                fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
        }

        Label("מה מוסיפים?")
        Choice("✓  הרגל רגיל – מסמנים וי", "למשל: לקרוא 20 דקות", type == HabitType.CHECK) { pickType(HabitType.CHECK) }
        Choice("🔢  הרגל עם מספר", "למשל: 8 כוסות מים ביום", type == HabitType.COUNT) { pickType(HabitType.COUNT) }
        Choice("🚫  להפסיק משהו", "כל יום שלא נפסלת = הצלחה. למשל: בלי ממתקים", type == HabitType.QUIT) { pickType(HabitType.QUIT) }
        Choice("🏆  להצליח משהו", "לוחצים \"הצלחתי\" כשהצלחת. למשל: לא לאחר לבית ספר", type == HabitType.SUCCEED) { pickType(HabitType.SUCCEED) }
        Choice("☑️  חד פעמי", "נמחק אחרי שמסמנים וי. למשל: לקנות מתנה לאמא", type == HabitType.ONCE) { pickType(HabitType.ONCE) }
        Choice("📚  שיעורי בית", "מקצוע + מה צריך לעשות. נמחק כשמסיימים", isHomework) { pickType(HabitType.HOMEWORK) }

        Label(
            when {
                isHomework -> "באיזה מקצוע?"
                isTask -> "מה צריך לעשות?"
                else -> "איך קוראים להרגל?"
            }
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            placeholder = {
                Text(
                    when {
                        isHomework -> "מתמטיקה"
                        isTask -> "לקנות מתנה לאמא"
                        else -> "לשתות מים"
                    }
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isHomework) {
            Label("מה השיעורי בית?")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("עד עמוד 25, או: עבודה במחשב שיעור 3") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("בווידג'ט יופיע רק שם המקצוע.", fontSize = 13.sp, color = Gray)
        }

        Label("קטגוריה")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryChip("בלי", category.isEmpty()) { category = "" }
            Store.categories.forEach { c -> CategoryChip(c.name, category == c.id) { category = c.id } }
            CategoryChip("➕ חדשה", false) { addingCategory = true }
        }

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

        if (type == HabitType.COUNT) {
            Label("כמה פעמים ביום? גלול למספר")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NumberWheel(target, 2, MAX_WHEEL) { target = it }
            }
        }

        if (type != HabitType.QUIT && !isTask) {
            Label("באיזו תדירות?")
            Choice("📅  בימים קבועים", "בוחרים באילו ימים בשבוע", !weekly) { weekly = false; error = null }
            Choice("🗓️  כמה פעמים בשבוע", "באיזה יום שבא לך. למשל: ריצה פעמיים בשבוע", weekly) { weekly = true; error = null }
        }

        if (isTask) {
            // One-time tasks have no days and no streak.
        } else if (isWeekly) {
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
        if (!isTask) {
            Text(
                "🕯️ שבת וחגים הם ימי מנוחה – הרצף לא נשבר בהם, וגם לא ביום שישי ובערב חג.",
                fontSize = 13.sp, color = Gold,
            )
        }

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
                "ביום שישי ובערב חג התזכורת תגיע לכל המאוחר שעה לפני כניסת השבת/החג, ובשבת ובחג לא יגיעו תזכורות.",
                fontSize = 13.sp, color = Gray,
            )
        }

        error?.let { Text(it, color = MissedFg, fontWeight = FontWeight.Medium) }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                when {
                    name.isBlank() -> error = if (isHomework) "צריך לכתוב מקצוע" else "צריך לתת שם"
                    !isTask && !isWeekly && days.isEmpty() -> error = "צריך לבחור לפחות יום אחד"
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
                                note = if (isHomework) note.trim() else "",
                                category = category,
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
                Text("מחק", color = MissedFg)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (addingCategory) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingCategory = false },
            title = { Text("קטגוריה חדשה") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    placeholder = { Text("למשל: 🏠 בבית") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank()) category = Store.addCategory(ctx, newName).id
                    addingCategory = false
                }) { Text("הוסף") }
            },
            dismissButton = { TextButton(onClick = { addingCategory = false }) { Text("ביטול") } },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק?") },
            text = {
                Text(
                    if (existing.isTask) "\"${existing.name}\" יימחק."
                    else "כל ההיסטוריה והרצף של \"${existing.name}\" יימחקו."
                )
            },
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
fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Blue else Color.White)
            .border(1.dp, if (selected) Blue else Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(text, color = if (selected) Color.White else Color.Black, fontSize = 15.sp) }
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
