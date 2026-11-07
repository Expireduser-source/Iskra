package ru.iskra.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.iskra.app.IskraApp
import ru.iskra.app.diag.Diag
import ru.iskra.app.overlay.OverlayService
import ru.iskra.app.system.DeviceHelper
import ru.iskra.core.avatar.RigFraming
import ru.iskra.core.device.Vendor
import ru.iskra.core.i18n.tr
import ru.iskra.core.persona.Emotion

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

/** The hero: an area of [HERO] with her figure of [FIGURE] in the middle. */
private const val HERO = 250f
private const val FIGURE = 206f

/**
 * «Знакомство»: what she can do without any setup, then each permission with its reason.
 * Plain words, large buttons, a visible step counter; nothing is required.
 *
 * 0.16.0: it is her birth. On the first page a spark lights up where her heart will be, beats twice, flares — and she is
 * there and waves ([Birth]); then she is alive on every page: curious about living on the screen, ears up for the voice,
 * thinking about the brain. With Android's animations off she is simply there.
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
    val still = rememberStill()
    val view = LocalView.current

    // Her birth, once (not again after turning the phone or coming back to the first page).
    var born by rememberSaveable { mutableStateOf(still) }
    val birth = remember { Animatable(if (born) 1f else 0f) }
    var wave by remember { mutableIntStateOf(0) }
    var hop by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (born) return@LaunchedEffect
        delay(350)
        launch { snapshotFlow { birth.value }.first { it >= Birth.FLARE }; view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        launch { snapshotFlow { birth.value }.first { it >= 0.62f }; wave++ }
        birth.animateTo(1f, tween(Birth.MS, easing = LinearEasing))
        born = true
        Diag.i("onboarding", tr("Знакомство: она родилась", "Introduction: she was born"))
    }
    // Each page: she reacts — waves on the first, hops on the others.
    LaunchedEffect(step) {
        if (!born) return@LaunchedEffect
        delay(250)
        if (step == 0) wave++ else hop++
    }

    fun finish(how: String) {
        Diag.i("onboarding", tr("Знакомство: $how на шаге ${step + 1} из $total", "Introduction: $how at step ${step + 1} of $total"))
        onDone()
    }

    val c = iskraColors
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.verticalGradient(0f to c.skyTop, 0.5f to c.skyTop.copy(alpha = 0f), endY = size.height))
        }.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            // The buttons stay pinned at the bottom: with long Russian text, large fonts or a small screen the
            // content scrolls, but «Дальше» never hides below the fold.
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepDots(step, total)
                    Spacer(Modifier.weight(1f))
                    Text(tr("Шаг ${step + 1} из $total", "Step ${step + 1} of $total"), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(HERO.dp), contentAlignment = Alignment.Center) {
                        Aura(Modifier.size(HERO.dp).graphicsLayer { alpha = Birth.figure(birth.value) }, still, strength = if (step == 2) 1.5f else 1f)
                        Sparkles(Modifier.size(HERO.dp).graphicsLayer { alpha = Birth.words(birth.value) }, still)
                        LiveIskra(
                            Modifier.size(FIGURE.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClickLabel = tr("погладить", "pet her")) { hop++ },
                            Framing.FULL,
                            emotion = if (step == 1) Emotion.CURIOUS else Emotion.NEUTRAL,
                            thinking = step == 3, listening = step == 2, still = still, wave = wave, hop = hop,
                            alpha = { Birth.figure(birth.value) }, scale = { Birth.figureScale(birth.value) },
                        )
                        BirthFlare(Modifier.size(HERO.dp), progress = { birth.value },
                            heart = Offset(0.5f, ((HERO - FIGURE) / 2f + RigFraming.heartY * FIGURE) / HERO))
                    }
                    Box(Modifier.graphicsLayer {
                        val k = Birth.words(birth.value)
                        alpha = k
                        translationY = (1f - k) * 16.dp.toPx()
                    }) {
                        AnimatedContent(targetState = step, label = "page", transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(160)) { -it / 6 * dir } + fadeOut(tween(120))) using SizeTransform(clip = false)
                        }) { s ->
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                when (s) {
                                    0 -> {
                                        Title(tr("Привет! Я Искра", "Hi! I'm Iskra"))
                                        // The moment she is born (0.15.0): her birthday is the first meeting.
                                        Body(tr("Я юная цифровая жизнь — и только что родилась в твоём телефоне. Буду расти, учиться и помнить наши дни. " +
                                            "А ещё ставлю будильники и таймеры, напоминаю о делах, записываю заметки, рассказываю погоду и играю в маленькие игры. " +
                                            "Всё это работает сразу — без регистрации и без интернета.",
                                            "I'm a young digital life — and I was just born in your phone. I'll grow, learn and remember our days. " +
                                                "I also set alarms and timers, remind you about things, take notes, tell the weather and play little games. " +
                                                "All of this works right away — no sign-up and no internet."))
                                        Hint(tr("Дальше пара разрешений. Каждое можно пропустить и включить потом на вкладке «Разрешения».", "Next, a couple of permissions. Each one can be skipped and turned on later in the “Permissions” tab."))
                                    }
                                    1 -> {
                                        Title(tr("Жить на экране", "Living on the screen"))
                                        Body(tr("Я могу сидеть поверх других приложений: нажми на меня — и поговорим, потяни — перенесу лапки в другое место. " +
                                            "Для этого Android спросит разрешение «Поверх других приложений».", "I can sit on top of other apps: tap me — and we'll talk, drag me — and I'll move my paws somewhere else. For this, Android will ask for the “Display over other apps” permission."))
                                        if (!overlay) Button(onClick = { DeviceHelper.openOverlaySettings(ctx) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                            Text(tr("Разрешить", "Allow"))
                                        } else SwitchRow(shown, tr("Показать Искру на экране", "Show Iskra on screen"), tag = "overlay_switch") { on ->
                                            shown = on
                                            if (on) hop++
                                            app.settings.save(app.settings.load().copy(overlayEnabled = on))
                                            if (on) {
                                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                                                    != PackageManager.PERMISSION_GRANTED) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                OverlayService.start(ctx)
                                            } else OverlayService.stop(ctx)
                                        }
                                        // While her own screens are open she is in them, not over them: say where she will be.
                                        if (overlay) Hint(if (shown) tr("Я появлюсь поверх других приложений, как только ты выйдешь отсюда. Пока я на экране, Android показывает значок в уведомлениях — так он меня не выгружает.",
                                                "I'll appear over other apps as soon as you leave this one. While I'm on screen, Android shows an icon in the notifications — that way it doesn't unload me.")
                                            else tr("Пока я на экране, Android показывает значок в уведомлениях — так он меня не выгружает.", "While I'm on screen, Android shows an icon in the notifications — that way it doesn't unload me."))
                                    }
                                    2 -> {
                                        Title(tr("Голос", "Voice"))
                                        Body(tr("Чтобы говорить со мной голосом, нужен микрофон. Я слушаю, только когда ты нажимаешь на микрофон. " +
                                            "А если включишь «Слух» в настройках, буду отзываться на имя — речь распознаётся прямо на телефоне.", "To talk to me by voice, I need the microphone. I listen only when you press the microphone. And if you turn on “Hearing” in Settings, I'll answer to my name — speech is recognized right on the phone."))
                                        if (!mic) Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
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
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) TextButton(onClick = { step-- }, modifier = Modifier.testTag("onb_back")) { Text(tr("Назад", "Back")) }
                    Spacer(Modifier.weight(1f))
                    if (step < total - 1) TextButton(onClick = { finish(tr("пропущено", "skipped")) }, modifier = Modifier.testTag("onb_skip")) { Text(tr("Пропустить", "Skip")) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (step < total - 1) step++ else finish(tr("пройдено", "completed")) },
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp), modifier = Modifier.testTag("onb_next")) {
                        Text(if (step < total - 1) tr("Дальше", "Next") else tr("Начать", "Start"))
                    }
                }
            }
        }
    }
}

/** Where she is in the introduction: a dot per page, the current one long. */
@Composable
private fun StepDots(step: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { }) {
        repeat(total) { i ->
            val w by animateDpAsState(if (i == step) 24.dp else 8.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "dot")
            Box(Modifier.size(width = w, height = 8.dp).clip(CircleShape)
                .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall,
    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
