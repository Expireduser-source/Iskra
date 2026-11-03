package ru.iskra.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.iskra.app.diag.Diag
import ru.iskra.app.diag.DiagCollector
import ru.iskra.core.diag.DiagEntry
import ru.iskra.core.diag.DiagInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → «Диагностика»: what the app knows about its own state, and a report to copy. */
@Composable
fun DiagnosticsScreen(modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<DiagInfo?>(null) }
    var entries by remember { mutableStateOf(emptyList<DiagEntry>()) }
    var copied by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        info = withContext(Dispatchers.IO) { DiagCollector.collect(ctx) }
        entries = Diag.log.entries()
    }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++; copied = false }) { Text("Обновить") }
        }
        Text("Диагностика", fontSize = 20.sp)
        Text(
            "Журнал хранится только на телефоне (последние ${Diag.log.capacity} событий). В отчёт не попадают " +
                "ключи API (они замаскированы) и текст переписки. Отправляешь его ты сам — приложение ничего не отправляет.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = {
            val text = DiagCollector.report(ctx)
            copyToClipboard(ctx, text)
            copied = true
        }, modifier = Modifier.fillMaxWidth()) { Text("Скопировать отчёт") }
        if (copied) Text("Скопировано ✓ Вставь в чат или в заметку.", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)

        val i = info
        if (i == null) Text("Собираю…") else {
            Block("Приложение и телефон", listOf(
                "Версия" to "${i.appVersion} (сборка ${i.build})",
                "Телефон" to i.device,
                "Android" to i.android,
                "Оболочка" to i.vendorOs,
            ))
            Block("Разрешения", i.permissions.map { (n, ok) -> n to if (ok) "✅" else "⬜ нет" })
            Block("Сервисы", i.services)
            Block("Настройки", i.settings)
        }

        val errors = entries.filter { it.level != DiagEntry.Level.INFO }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (showAll) "Журнал (${entries.size})" else "Последние ошибки и предупреждения (${errors.size})", fontSize = 16.sp)
                val shown = (if (showAll) entries else errors).takeLast(40).asReversed()
                if (shown.isEmpty()) Text("Пусто 🎉", fontSize = 13.sp)
                shown.forEach { e ->
                    val color = when (e.level) {
                        DiagEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                        DiagEntry.Level.WARN -> MaterialTheme.colorScheme.secondary
                        DiagEntry.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text("${fmt.format(Date(e.timeMs))} ${e.tag}: ${e.message}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                }
                Row {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Только ошибки" else "Весь журнал") }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { Diag.clear(); refresh++ }) { Text("Очистить журнал") }
                }
            }
        }
    }
}

@Composable
private fun Block(title: String, rows: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp)
            rows.forEach { (k, v) ->
                Row {
                    Text(k, Modifier.weight(0.45f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, Modifier.weight(0.55f), fontSize = 13.sp)
                }
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Искра: диагностика", text)
    // Android 13+: mark as sensitive anyway, so the clipboard preview does not show it on screen.
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
}
