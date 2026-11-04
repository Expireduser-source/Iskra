package ru.iskra.app.ui

import ru.iskra.core.i18n.tr
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    val text = DiagCollector.report(ctx, maxLog = Diag.log.capacity)
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error(tr("нет доступа к файлу", "no access to the file"))
                }
                tr("Сохранено ✓", "Saved ✓")
            } catch (e: Exception) {
                Diag.e("diag", tr("Не удалось сохранить отчёт", "Couldn't save the report"), e)
                tr("Не получилось сохранить: ${e.message ?: e.javaClass.simpleName}", "Couldn't save: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
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
            TextButton(onClick = onBack) { Text(tr("← Назад", "← Back")) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++; status = null }) { Text(tr("Обновить", "Refresh")) }
        }
        Text(tr("Диагностика", "Diagnostics"), fontSize = 20.sp)
        Text(
            tr("Журнал хранится только на телефоне (последние ${Diag.log.capacity} событий). В отчёт не попадают " +
                "ключи API (они замаскированы) и текст переписки. Отправляешь его ты сам — приложение ничего не отправляет.", "The journal is kept only on the phone (the last ${Diag.log.capacity} events). The report never includes API keys (they are masked) or chat text. You send it yourself — the app sends nothing."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = {
            scope.launch {
                status = try {
                    val uri = withContext(Dispatchers.IO) { DiagCollector.reportFile(ctx) }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, tr("Искра: отчёт диагностики", "Iskra: diagnostics report"))
                        clipData = ClipData.newRawUri("report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, tr("Отправить отчёт", "Send report")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    null
                } catch (e: Exception) {
                    Diag.e("diag", tr("Не удалось подготовить файл отчёта", "Couldn't prepare the report file"), e)
                    tr("Не получилось: ${e.javaClass.simpleName}", "Didn't work: ${e.javaClass.simpleName}")
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(tr("📤 Отправить отчёт файлом", "📤 Send report as a file")) }
        Row {
            OutlinedButton(onClick = { saveLauncher.launch(DiagCollector.reportFileName()) }) { Text(tr("💾 В папку", "💾 To a folder")) }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = {
                copyToClipboard(ctx, DiagCollector.report(ctx))
                status = tr("Скопировано текстом ✓ (последние 60 событий)", "Copied as text ✓ (last 60 events)")
            }) { Text(tr("Скопировать текстом", "Copy as text")) }
        }
        Text(tr("Файл .txt содержит весь журнал (до ${Diag.log.capacity} событий) — прикрепи его в чат вместо вставки текста.", "The .txt file has the whole journal (up to ${Diag.log.capacity} events) — attach it in the chat instead of pasting text."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }

        val i = info
        if (i == null) Text(tr("Собираю…", "Collecting…")) else {
            Block(tr("Приложение и телефон", "App and phone"), listOf(
                tr("Версия", "Version") to tr("${i.appVersion} (сборка ${i.build})", "${i.appVersion} (build ${i.build})"),
                tr("Сборка и подпись", "Build and signature") to i.signing,
                tr("Телефон", "Phone") to i.device,
                "Android" to i.android,
                tr("Оболочка", "Skin") to i.vendorOs,
            ))
            Block(tr("Разрешения", "Permissions"), i.permissions.map { (n, ok) -> n to if (ok) "✅" else tr("⬜ нет", "⬜ no") })
            Block(tr("Сервисы", "Services"), i.services)
            Block(tr("Настройки", "Settings"), i.settings)
        }

        val errors = entries.filter { it.level != DiagEntry.Level.INFO }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (showAll) tr("Журнал (${entries.size})", "Journal (${entries.size})") else tr("Последние ошибки и предупреждения (${errors.size})", "Latest errors and warnings (${errors.size})"), fontSize = 16.sp)
                val shown = (if (showAll) entries else errors).takeLast(40).asReversed()
                if (shown.isEmpty()) Text(tr("Пусто 🎉", "Empty 🎉"), fontSize = 13.sp)
                shown.forEach { e ->
                    val color = when (e.level) {
                        DiagEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                        DiagEntry.Level.WARN -> MaterialTheme.colorScheme.secondary
                        DiagEntry.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text("${fmt.format(Date(e.timeMs))} ${e.tag}: ${e.message}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                }
                Row {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) tr("Только ошибки", "Errors only") else tr("Весь журнал", "Whole journal")) }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { Diag.clear(); refresh++ }) { Text(tr("Очистить журнал", "Clear journal")) }
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
    val clip = ClipData.newPlainText(tr("Искра: диагностика", "Iskra: diagnostics"), text)
    // Android 13+: mark as sensitive anyway, so the clipboard preview does not show it on screen.
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
}
