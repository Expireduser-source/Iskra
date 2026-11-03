package ru.iskra.app.diag

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ru.iskra.app.BuildConfig
import ru.iskra.app.IskraApp
import ru.iskra.app.data.ProviderPreset
import ru.iskra.app.data.TtsEngine
import ru.iskra.app.overlay.FrameStats
import ru.iskra.app.system.CompanionAccessibilityService
import ru.iskra.app.system.DeviceHelper
import ru.iskra.app.voice.SpeechModel
import ru.iskra.core.diag.DiagInfo
import ru.iskra.core.diag.DiagReport
import ru.iskra.core.diag.Redactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Gathers non-sensitive facts for the diagnostics screen and the copied report. */
object DiagCollector {

    fun knownSecrets(app: IskraApp): List<String> =
        listOfNotNull(runCatching { app.vault.load() }.getOrNull(), runCatching { app.ttsVault.load() }.getOrNull())

    fun build(): String = "${BuildConfig.CI_BUILD}" + if (BuildConfig.GIT_SHA.isNotEmpty()) " · ${BuildConfig.GIT_SHA}" else ""

    fun collect(ctx: Context): DiagInfo {
        val app = IskraApp.instance
        val s = app.settings.load()
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

        val permissions = listOf(
            "Поверх других приложений" to DeviceHelper.canDrawOverlays(ctx),
            "Микрофон" to granted(Manifest.permission.RECORD_AUDIO),
            "Уведомления" to (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)),
            "Спец. возможности (управление)" to CompanionAccessibilityService.isEnabled(ctx),
            "Без ограничений батареи" to DeviceHelper.isIgnoringBatteryOptimizations(ctx),
        )

        val speaking = app.speaker.speaking.value
        val ttsError = app.speaker.lastError.value
        val services = listOf(
            "Оверлей (Искра на экране)" to Diag.overlayState,
            "Слух" to Diag.earState,
            "Модель распознавания" to if (SpeechModel.isReady(ctx)) "скачана" else "нет",
            "Голос (TTS)" to buildString {
                append(s.ttsEngine.title)
                if (!s.ttsEnabled) append(", озвучка выключена")
                if (speaking) append(", сейчас говорит")
                if (ttsError != null) append(", последняя ошибка: ").append(ttsError)
            },
            "Служба спец. возможностей" to if (CompanionAccessibilityService.isConnected()) "подключена" else "не подключена",
            "Кадры/с у Искры" to if (FrameStats.hz > 0) "~${FrameStats.hz}" else "нет данных (Искра не на экране)",
            "Режим кадров" to s.fpsMode.title,
            "Группа ожидания Android" to standbyBucket(ctx),
            "Записей в памяти" to runCatching { app.memory.all().size.toString() }.getOrDefault("?"),
        )

        val llmKey = runCatching { app.vault.load() }.getOrNull()
        val ttsKey = runCatching { app.ttsVault.load() }.getOrNull()
        val settings = listOf(
            "Мозг" to s.preset.title + if (s.preset != ProviderPreset.OFFLINE) ", модель ${s.model.ifBlank { s.preset.defaultModel }}" else "",
            "Ключ мозга" to keyState(llmKey),
            "Ключ голоса" to if (s.ttsEngine == TtsEngine.SYSTEM) "не нужен" else keyState(ttsKey),
            "Слушать постоянно" to yesNo(s.alwaysListen),
            "Слушать при выключенном экране" to yesNo(s.listenScreenOff),
            "Продолжение без имени" to yesNo(s.followUp),
            "Гуляет сама" to yesNo(s.wander) + ", непоседливость ${s.liveliness}",
            "Размер" to "${s.avatarSizeDp} dp",
        )

        return DiagInfo(
            appVersion = BuildConfig.VERSION_NAME,
            build = build(),
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), патч ${Build.VERSION.SECURITY_PATCH}",
            vendorOs = vendorOs(),
            permissions = permissions,
            services = services,
            settings = settings,
        )
    }

    fun report(ctx: Context): String {
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
        return DiagReport.build(collect(ctx), Diag.log.entries(), knownSecrets(IskraApp.instance), time = { fmt.format(Date(it)) })
    }

    private fun keyState(key: String?) = if (key.isNullOrBlank()) "не сохранён" else "сохранён, ${Redactor.mask(key)}"
    private fun yesNo(b: Boolean) = if (b) "да" else "нет"

    /** Android's app-standby bucket: RARE/RESTRICTED explains a lot of "she disappeared" reports. */
    private fun standbyBucket(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 28) return "—"
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return "?"
        return when (val b = usm.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "активное"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "рабочий набор"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "частое"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "редкое (Android ограничивает фон)"
            45 /* RESTRICTED, API 30 */ -> "ограниченное (фон сильно урезан)"
            else -> "код $b"
        }
    }

    /** HyperOS / MIUI version from system properties (read-only `getprop`, no permission needed). */
    private fun vendorOs(): String {
        val hyper = prop("ro.mi.os.version.name")
        val hyperInc = prop("ro.mi.os.version.incremental")
        val miui = prop("ro.miui.ui.version.name")
        val inc = prop("ro.build.version.incremental").ifEmpty { Build.VERSION.INCREMENTAL }
        return when {
            hyper.isNotEmpty() -> "HyperOS $hyper ${hyperInc.ifEmpty { inc }}".trim()
            miui.isNotEmpty() -> "MIUI $miui $inc".trim()
            else -> "${Build.MANUFACTURER} $inc".trim()
        }
    }

    private fun prop(name: String): String = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroy()
        out.take(60)
    }.getOrDefault("")
}
