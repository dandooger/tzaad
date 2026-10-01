package com.dandooger.tzaad

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate

const val MAX_WHEEL = 500

/** A scroll wheel of numbers – fling it to get to big numbers fast. */
@Composable
fun NumberWheel(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    AndroidView(
        factory = { context ->
            NumberPicker(context).apply {
                minValue = min
                maxValue = max
                this.value = value.coerceIn(min, max)
                wrapSelectorWheel = false
                // Scroll only – no keyboard popping up.
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                if (Build.VERSION.SDK_INT >= 29) {
                    textSize = 30 * context.resources.displayMetrics.density
                }
                setOnValueChangedListener { _, _, n -> onChange(n) }
            }
        },
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun AmountDialog(h: Habit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val c = Store.count(h, today)
    var amount by remember { mutableIntStateOf(Store.lastAdd(ctx, h).coerceIn(1, MAX_WHEEL)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${h.emoji} ${h.name}") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("היום עד עכשיו: $c מתוך ${h.target}", color = Gray, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Text("כמה עשית? גלול למספר", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                NumberWheel(amount, 1, MAX_WHEEL) { amount = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                Store.add(ctx, h, today, amount)
                onDismiss()
            }) { Text("הוסף $amount", fontSize = 16.sp) }
        },
        dismissButton = {
            if (c > 0) {
                TextButton(onClick = {
                    Store.add(ctx, h, today, -amount)
                    onDismiss()
                }) { Text("הורד $amount", color = MissedFg) }
            } else {
                TextButton(onClick = onDismiss) { Text("ביטול") }
            }
        },
    )
}

/** Opens just the number-wheel window on top of the home screen (from the widget or a reminder). */
class AddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        val h = intent.getStringExtra("habit")?.let { Store.habit(it) }
        if (h == null) {
            finish()
            return
        }
        val notif = intent.getIntExtra("notif", 0)
        if (notif != 0) getSystemService(NotificationManager::class.java).cancel(notif)
        if (!h.usesWheel()) {
            // A tap on a small habit in the widget list: mark it and vanish – no window.
            Store.tap(this, h, LocalDate.now())
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }
        setContent { TzaadTheme { AmountDialog(h) { finish() } } }
    }

    companion object {
        fun pending(ctx: Context, habitId: String, notif: Int = 0): PendingIntent {
            val i = Intent(ctx, AddActivity::class.java)
                .setData(Uri.parse("tzaad://add/$habitId/$notif"))
                .putExtra("habit", habitId)
                .putExtra("notif", notif)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return PendingIntent.getActivity(
                ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
