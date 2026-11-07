package ru.iskra.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.iskra.app.BuildConfig
import ru.iskra.app.R
import ru.iskra.app.data.Updates
import ru.iskra.app.diag.DiagCollector
import ru.iskra.core.i18n.tr
import ru.iskra.core.update.UpdateCheck

/** «О приложении»: version, the GPL notice (no warranty, where the source is), third-party licenses, updates, sharing. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AboutSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var share by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Updates.enabled(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Text(tr("О приложении", "About"), style = MaterialTheme.typography.titleLarge)
    Text(tr("Искра ", "Iskra ") + BuildConfig.VERSION_NAME + tr(" (сборка ", " (build ") + DiagCollector.build() + ")", fontSize = 14.sp)
    Text(tr(
        "Свободная программа: исходный код открыт по лицензии GNU GPL v3 — её можно использовать, изучать, менять и распространять. " +
            "Предоставляется «как есть», без каких-либо гарантий. © 2026 авторы Искры. Рисунки персонажа © владелец проекта, все права защищены.",
        "Free software: the source code is open under the GNU GPL v3 — you may use, study, change and share it. " +
            "Provided “as is”, without any warranty. © 2026 Iskra authors. Character artwork © the project owner, all rights reserved.",
    ), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(tr(
        "Внутри: Kotlin, kotlinx, OkHttp, AndroidX и Jetpack Compose, Material Icons (Apache-2.0); шрифт Nunito (SIL OFL 1.1); Vosk и его модели (Apache-2.0); " +
            "JNA (Apache-2.0 / LGPL-2.1); llama.cpp и ggml (MIT). Модели Gemma и Qwen скачиваются отдельно, по лицензиям авторов (Gemma Terms of Use, Apache-2.0). " +
            "Погода: Open-Meteo.com (данные по лицензии CC BY 4.0). Факты: Википедия (CC BY-SA). Курсы: Rates By Exchange Rate API.",
        "Inside: Kotlin, kotlinx, OkHttp, AndroidX and Jetpack Compose, Material Icons (Apache-2.0); the Nunito font (SIL OFL 1.1); Vosk and its models (Apache-2.0); " +
            "JNA (Apache-2.0 / LGPL-2.1); llama.cpp and ggml (MIT). The Gemma and Qwen models are downloaded separately, under their authors' licenses (Gemma Terms of Use, Apache-2.0). " +
            "Weather: Open-Meteo.com (data under CC BY 4.0). Facts: Wikipedia (CC BY-SA). Rates By Exchange Rate API.",
    ), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow {
        TextButton(onClick = { open(UpdateCheck.PROJECT) }) { Text(tr("Исходный код", "Source code")) }
        TextButton(onClick = { open("https://www.gnu.org/licenses/gpl-3.0.html") }) { Text(tr("Лицензия", "License")) }
        TextButton(onClick = { open(UpdateCheck.PROJECT + "/issues") }) { Text(tr("Сообщить о проблеме", "Report a problem")) }
    }
    if (Updates.applicable(ctx)) {
        SwitchRow(auto, tr("Проверять обновления (раз в день спрашивает GitHub — без данных о тебе)",
            "Check for updates (asks GitHub once a day — nothing about you)")) { auto = it; Updates.setEnabled(ctx, it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { status = tr("Проверяю…", "Checking…"); scope.launch { status = Updates.check(ctx) } }) {
                Text(tr("Проверить сейчас", "Check now"))
            }
            status?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
        }
    }
    Button(onClick = { share = true }, modifier = Modifier.fillMaxWidth().testTag("share_app")) { Text(tr("❤ Рассказать другу", "❤ Tell a friend")) }
    if (share) ShareAppDialog { share = false }
}

/** A QR code to scan from this screen, or a link to send. */
@Composable
fun ShareAppDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val text = tr("Это Искра — котодевочка-компаньон для Android: таймеры, напоминания, заметки, погода и болтовня. " +
        "Бесплатно, без рекламы, данные на телефоне: ${UpdateCheck.PROJECT}",
        "Meet Iskra — a cat-girl companion for Android: timers, reminders, notes, weather and chat. " +
            "Free, no ads, your data stays on the phone: ${UpdateCheck.PROJECT}")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Рассказать другу", "Tell a friend")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Card { Image(painterResource(R.drawable.qr_project), contentDescription = tr("QR-код со ссылкой на Искру", "QR code with a link to Iskra"),
                    modifier = Modifier.size(220.dp).padding(8.dp)) }
                Text(tr("Пусть друг наведёт камеру телефона на код — откроется страница, где можно скачать Искру.",
                    "Let your friend point their phone camera at the code — it opens the page where Iskra can be downloaded."), fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    tr("Поделиться Искрой", "Share Iskra")))
                onClose()
            }) { Text(tr("Отправить ссылку", "Send the link")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Закрыть", "Close")) } },
    )
}

/** A newer version is out (copies from GitHub/the site only). */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val r by Updates.available.collectAsState()
    val rel = r ?: return
    Card(Modifier.fillMaxWidth().padding(8.dp).testTag("update_banner")) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("🎁 Вышла Искра ${rel.version}", "🎁 Iskra ${rel.version} is out"), style = MaterialTheme.typography.titleSmall)
            UpdateCheck.shortNotes(rel.notes).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(tr("Скачай APK и открой его — Искра обновится, память и настройки сохранятся.",
                "Download the APK and open it — Iskra updates, memory and settings stay."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl ?: rel.pageUrl))) } }) {
                    Text(tr("Скачать", "Download"))
                }
                TextButton(onClick = { Updates.dismiss(ctx) }) { Text(tr("Позже", "Later")) }
            }
        }
    }
}
