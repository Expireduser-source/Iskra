package ru.iskra.app.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import ru.iskra.app.data.LocalBrain
import ru.iskra.core.i18n.tr
import java.util.Locale

/**
 * «📱 Мозг на телефоне»: download a small model once and talk without the internet. The owner sees the size,
 * what it means for speed, and a warning on mobile data before anything is downloaded.
 */
@Composable
fun LocalBrainCard() {
    val ctx = LocalContext.current
    val state by LocalBrain.state.collectAsState()
    val tuning by LocalBrain.tuning.collectAsState()
    var choice by remember { mutableStateOf(runCatching { LocalBrain.suggested() }.getOrNull() ?: LocalBrain.models.first()) }
    var confirm by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(LocalBrain.enabled()) }
    var speed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth().testTag("local_brain")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("📱 Мозг на телефоне — без интернета", "📱 A brain on the phone — no internet"), fontSize = 18.sp)
            when (val s = state) {
                is LocalBrain.State.Unsupported -> Text(s.reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LocalBrain.State.None, is LocalBrain.State.Failed -> {
                    Text(tr("Небольшая модель скачивается один раз и думает прямо на телефоне: бесплатно, без аккаунта, ничего не уходит в сеть. " +
                        "Она проще и медленнее облачной, поэтому отвечает, когда облака нет или оно недоступно.",
                        "A small model is downloaded once and thinks right on the phone: free, no account, nothing goes online. " +
                            "It's simpler and slower than the cloud, so it answers when there is no cloud or it's unavailable."), fontSize = 13.sp)
                    LocalBrain.models.forEach { m ->
                        Row(Modifier.fillMaxWidth().selectable(selected = m == choice, onClick = { choice = m }).semantics { role = Role.RadioButton },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = m == choice, onClick = null)
                            Column(Modifier.padding(start = 6.dp)) {
                                Text("${m.title} · ${mb(m.approxMb.toLong() * 1_000_000)}", fontSize = 14.sp)
                                Text(m.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (LocalBrain.totalRamGb() + 0.5 < choice.minRamGb) Text(
                        tr("На этом телефоне ${"%.0f".format(Locale.ROOT, LocalBrain.totalRamGb())} ГБ памяти — выбранная модель может работать медленно или не загрузиться.",
                            "This phone has ${"%.0f".format(Locale.ROOT, LocalBrain.totalRamGb())} GB of memory — the chosen model may be slow or fail to load."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    else if (choice != LocalBrain.models.first() && LocalBrain.fewFastCores()) Text(
                        tr("У этого телефона два быстрых ядра: по оценке, ${choice.title} будет писать ${if (choice.id == "gemma-4-e4b") "1–3 слова" else "3–5 слов"} в секунду, лёгкая — 8–10. Точно покажет «Проверить скорость» после скачивания.",
                            "This phone has two fast cores: by estimate, ${choice.title} will write ${if (choice.id == "gemma-4-e4b") "1–3 words" else "3–5 words"} a second, the light one 8–10. “Check the speed” after the download tells for sure."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s is LocalBrain.State.Failed) Text(s.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { if (metered(ctx)) confirm = true else LocalBrain.download(choice) }, modifier = Modifier.fillMaxWidth().testTag("local_brain_download")) {
                        Text(tr("Скачать (${mb(choice.approxMb.toLong() * 1_000_000)})", "Download (${mb(choice.approxMb.toLong() * 1_000_000)})"))
                    }
                }
                is LocalBrain.State.Downloading -> {
                    Text(if (s.checking) tr("Проверяю файл…", "Checking the file…")
                    else tr("Скачиваю ${s.model.title}: ${mb(s.done)} из ${mb(s.total)}", "Downloading ${s.model.title}: ${mb(s.done)} of ${mb(s.total)}"), fontSize = 13.sp)
                    LinearProgressIndicator(progress = { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    Text(tr("Можно пользоваться телефоном — если связь оборвётся, загрузка продолжится с того же места.",
                        "You can keep using the phone — if the connection drops, it continues where it stopped."), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { LocalBrain.cancelDownload() }) { Text(tr("Остановить", "Stop")) }
                }
                is LocalBrain.State.Ready -> {
                    Text(tr("Готово ✓ ${s.model.title} · ${mb(s.bytes)}. Первый ответ после паузы — через несколько секунд: модель загружается в память.",
                        "Ready ✓ ${s.model.title} · ${mb(s.bytes)}. The first reply after a pause takes a few seconds: the model loads into memory."), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Отвечать ею, когда нет облака", "Answer with it when there's no cloud"), Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = enabled, onCheckedChange = { enabled = it; LocalBrain.setEnabled(it) }, modifier = Modifier.testTag("local_brain_enabled"))
                    }
                    if (LocalBrain.isLegacy(s.model)) Text(
                        tr("Есть модель лучше — ${LocalBrain.suggested().title} (${mb(LocalBrain.suggested().approxMb.toLong() * 1_000_000)}): ${LocalBrain.whyBetter(s.model)}. Удали эту и скачай новую.",
                            "A better model is available — ${LocalBrain.suggested().title} (${mb(LocalBrain.suggested().approxMb.toLong() * 1_000_000)}): ${LocalBrain.whyBetter(s.model)}. Delete this one and download the new one."),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    if (tuning) Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        Text(tr("  Подбираю настройки под этот телефон…", "  Tuning for this phone…"), fontSize = 12.sp)
                    }
                    (speed ?: if (tuning) null else LocalBrain.tunedSpeed())?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row {
                        TextButton(enabled = speed != CHECKING && !tuning, onClick = {
                            speed = CHECKING
                            scope.launch { speed = runCatching { LocalBrain.speedCheck()?.text }.getOrNull() ?: tr("Не получилось загрузить модель.", "Couldn't load the model.") }
                        }, modifier = Modifier.testTag("local_brain_speed")) { Text(tr("Проверить скорость", "Check the speed")) }
                        TextButton(onClick = { speed = null; LocalBrain.delete() }) { Text(tr("Удалить (${mb(s.bytes)})", "Delete (${mb(s.bytes)})")) }
                    }
                }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Скачать по мобильной сети?", "Download over mobile data?")) },
        text = { Text(tr("Это ${mb(choice.approxMb.toLong() * 1_000_000)}. По Wi-Fi обычно быстрее и бесплатно.", "That's ${mb(choice.approxMb.toLong() * 1_000_000)}. Wi-Fi is usually faster and free."), fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = { confirm = false; LocalBrain.download(choice) }) { Text(tr("Скачать", "Download")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Подожду Wi-Fi", "I'll wait for Wi-Fi")) } },
    )
}

private val CHECKING: String get() = tr("Проверяю скорость… (первый раз модель загружается в память)", "Checking the speed… (the first time the model loads into memory)")

private fun mb(bytes: Long): String = if (bytes >= 1_000_000_000) String.format(Locale.getDefault(), tr("%.1f ГБ", "%.1f GB"), bytes / 1e9)
else tr("${bytes / 1_000_000} МБ", "${bytes / 1_000_000} MB")

private fun metered(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
}.getOrDefault(false)
