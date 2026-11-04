package ru.iskra.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ru.iskra.app.IskraApp
import ru.iskra.app.R
import ru.iskra.app.diag.Diag
import ru.iskra.app.overlay.OverlayService
import ru.iskra.app.system.DeviceHelper
import ru.iskra.core.device.Vendor

/** First launch only (not after an update): shown once, every step can be skipped. */
object OnboardingState {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun needed(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getBoolean("again", false)) return true
        if (p.getBoolean("done", false)) return false
        // Updated from an older version: the owner already knows her.
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull() ?: return false
        return info.firstInstallTime == info.lastUpdateTime
    }

    fun done(ctx: Context) = prefs(ctx).edit().putBoolean("done", true).putBoolean("again", false).apply()

    /** «Пройти знакомство заново» in Settings. */
    fun again(ctx: Context) = prefs(ctx).edit().putBoolean("again", true).apply()
}

/**
 * «Знакомство»: what she can do without any setup, then each permission with its reason.
 * Plain words, large buttons, a visible step counter; nothing is required.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = IskraApp.instance
    var step by rememberSaveable { mutableIntStateOf(0) }
    val total = 4
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val overlay = remember(tick) { DeviceHelper.canDrawOverlays(ctx) }
    val mic = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    var shown by remember { mutableStateOf(app.settings.load().overlayEnabled) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun finish(how: String) {
        Diag.i("onboarding", "Знакомство: $how на шаге ${step + 1} из $total")
        onDone()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Шаг ${step + 1} из $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { (step + 1f) / total }, modifier = Modifier.fillMaxWidth())
                val picture = when (step) { 0 -> R.drawable.iskra_wave; 1 -> R.drawable.iskra_stand; 2 -> R.drawable.iskra_shy; else -> R.drawable.iskra_thoughtful }
                Image(painterResource(picture), contentDescription = null, modifier = Modifier.size(168.dp).align(Alignment.CenterHorizontally))
                when (step) {
                    0 -> {
                        Title("Привет! Я Искра")
                        Body("Я живу в твоём телефоне. Ставлю будильники и таймеры, напоминаю о делах, записываю заметки, " +
                            "рассказываю погоду и играю в маленькие игры. Всё это работает сразу — без регистрации и без интернета.")
                        Body("Дальше пара разрешений. Каждое можно пропустить и включить потом на вкладке «Разрешения».")
                    }
                    1 -> {
                        Title("Жить на экране")
                        Body("Я могу сидеть поверх других приложений: нажми на меня — и поговорим, потяни — перенесу лапки в другое место. " +
                            "Для этого Android спросит разрешение «Поверх других приложений».")
                        if (!overlay) Button(onClick = { DeviceHelper.openOverlaySettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Разрешить")
                        } else SwitchRow(shown, "Показать Искру на экране") { on ->
                            shown = on
                            app.settings.save(app.settings.load().copy(overlayEnabled = on))
                            if (on) {
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                OverlayService.start(ctx)
                            } else OverlayService.stop(ctx)
                        }
                        if (overlay) Hint("Пока я на экране, Android показывает значок в уведомлениях — так он меня не выгружает.")
                    }
                    2 -> {
                        Title("Голос")
                        Body("Чтобы говорить со мной голосом, нужен микрофон. Я слушаю, только когда ты нажимаешь 🎤. " +
                            "А если включишь «Слух» в настройках, буду отзываться на имя — речь распознаётся прямо на телефоне.")
                        if (!mic) Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Разрешить микрофон")
                        } else Hint("Микрофон разрешён ✓")
                    }
                    else -> {
                        Title("Мозг — по желанию")
                        Body("Без подключения я понимаю команды: «поставь будильник на 7», «напомни через 10 минут позвонить маме», " +
                            "«запиши заметку: купить молоко», «какая погода», «удиви меня».")
                        Body("Чтобы свободно болтать, в «Настройках» можно подключить модель ИИ по ключу (DeepSeek, OpenRouter и другие). " +
                            "Ключ хранится зашифрованным и уходит только выбранному сервису.")
                        if (DeviceHelper.vendor != Vendor.NEAR_STOCK) Hint("${DeviceHelper.vendor.title}: прошивка иногда выгружает приложения в фоне. " +
                            "На вкладке «Разрешения» есть короткая инструкция именно для твоего телефона.")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) TextButton(onClick = { step-- }) { Text("Назад") }
                    Spacer(Modifier.weight(1f))
                    if (step < total - 1) TextButton(onClick = { finish("пропущено") }) { Text("Пропустить") }
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(onClick = { if (step < total - 1) step++ else finish("пройдено") }) {
                        Text(if (step < total - 1) "Дальше" else "Начать")
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
