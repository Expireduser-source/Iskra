package ru.iskra.app.ui

import ru.iskra.core.i18n.tr
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.testTag
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
        Diag.i("onboarding", tr("Знакомство: $how на шаге ${step + 1} из $total", "Introduction: $how at step ${step + 1} of $total"))
        onDone()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            // The buttons stay pinned at the bottom: with long Russian text, large fonts or a small screen the
            // content scrolls, but «Дальше» never hides below the fold.
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(tr("Шаг ${step + 1} из $total", "Step ${step + 1} of $total"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { (step + 1f) / total }, modifier = Modifier.fillMaxWidth())
                val picture = when (step) { 0 -> R.drawable.iskra_wave; 1 -> R.drawable.iskra_stand; 2 -> R.drawable.iskra_shy; else -> R.drawable.iskra_thoughtful }
                Image(painterResource(picture), contentDescription = null, modifier = Modifier.size(168.dp).align(Alignment.CenterHorizontally))
                when (step) {
                    0 -> {
                        Title(tr("Привет! Я Искра", "Hi! I'm Iskra"))
                        Body(tr("Я живу в твоём телефоне. Ставлю будильники и таймеры, напоминаю о делах, записываю заметки, " +
                            "рассказываю погоду и играю в маленькие игры. Всё это работает сразу — без регистрации и без интернета.", "I live in your phone. I set alarms and timers, remind you about things, take notes, tell the weather and play little games. All of this works right away — no sign-up and no internet."))
                        Body(tr("Дальше пара разрешений. Каждое можно пропустить и включить потом на вкладке «Разрешения».", "Next, a couple of permissions. Each one can be skipped and turned on later in the “Permissions” tab."))
                    }
                    1 -> {
                        Title(tr("Жить на экране", "Living on the screen"))
                        Body(tr("Я могу сидеть поверх других приложений: нажми на меня — и поговорим, потяни — перенесу лапки в другое место. " +
                            "Для этого Android спросит разрешение «Поверх других приложений».", "I can sit on top of other apps: tap me — and we'll talk, drag me — and I'll move my paws somewhere else. For this, Android will ask for the “Display over other apps” permission."))
                        if (!overlay) Button(onClick = { DeviceHelper.openOverlaySettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Разрешить", "Allow"))
                        } else SwitchRow(shown, tr("Показать Искру на экране", "Show Iskra on screen"), tag = "overlay_switch") { on ->
                            shown = on
                            app.settings.save(app.settings.load().copy(overlayEnabled = on))
                            if (on) {
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                OverlayService.start(ctx)
                            } else OverlayService.stop(ctx)
                        }
                        if (overlay) Hint(tr("Пока я на экране, Android показывает значок в уведомлениях — так он меня не выгружает.", "While I'm on screen, Android shows an icon in the notifications — that way it doesn't unload me."))
                    }
                    2 -> {
                        Title(tr("Голос", "Voice"))
                        Body(tr("Чтобы говорить со мной голосом, нужен микрофон. Я слушаю, только когда ты нажимаешь 🎤. " +
                            "А если включишь «Слух» в настройках, буду отзываться на имя — речь распознаётся прямо на телефоне.", "To talk to me by voice, I need the microphone. I listen only when you press 🎤. And if you turn on “Hearing” in Settings, I'll answer to my name — speech is recognized right on the phone."))
                        if (!mic) Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Разрешить микрофон", "Allow microphone"))
                        } else Hint(tr("Микрофон разрешён ✓", "Microphone allowed ✓"))
                    }
                    else -> {
                        Title(tr("Мозг — по желанию", "A brain — if you want"))
                        Body(tr("Без подключения я понимаю команды: «поставь будильник на 7», «напомни через 10 минут позвонить маме», " +
                            "«запиши заметку: купить молоко», «какая погода», «удиви меня».", "Without a connection I understand commands: “set an alarm for 7”, “remind me in 10 minutes to call mom”, “note: buy milk”, “what's the weather”, “surprise me”."))
                        Body(tr("Чтобы свободно болтать, подключи в «Настройках» модель ИИ: бесплатно одной кнопкой через OpenRouter или своим ключом " +
                            "(DeepSeek, OpenAI и другие). Ключ хранится зашифрованным и уходит только выбранному сервису.", "To chat freely, connect an AI model in “Settings”: free with one tap through OpenRouter, or with your own key (DeepSeek, OpenAI and others). The key is stored encrypted and goes only to the chosen service."))
                        if (DeviceHelper.vendor != Vendor.NEAR_STOCK) Hint(tr("${DeviceHelper.vendor.title}: прошивка иногда выгружает приложения в фоне. " +
                            "На вкладке «Разрешения» есть короткая инструкция именно для твоего телефона.", "${DeviceHelper.vendor.title}: the firmware sometimes unloads apps in the background. The “Permissions” tab has a short guide for exactly your phone."))
                    }
                }
            }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) TextButton(onClick = { step-- }, modifier = Modifier.testTag("onb_back")) { Text(tr("Назад", "Back")) }
                    Spacer(Modifier.weight(1f))
                    if (step < total - 1) TextButton(onClick = { finish(tr("пропущено", "skipped")) }, modifier = Modifier.testTag("onb_skip")) { Text(tr("Пропустить", "Skip")) }
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(onClick = { if (step < total - 1) step++ else finish(tr("пройдено", "completed")) }, modifier = Modifier.testTag("onb_next")) {
                        Text(if (step < total - 1) tr("Дальше", "Next") else tr("Начать", "Start"))
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
