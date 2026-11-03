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
            "Слух" to Diag.earState + " · " + ru.iskra.app.voice.EarStats.describe(),
            "Модель распознавания" to if (SpeechModel.isReady(ctx)) "скачана" else "нет",
            "Голос (TTS)" to buildString {
                append(s.ttsEngine.title)
                if (!s.ttsEnabled) append(", озвучка выключена")
                if (speaking) append(", сейчас говорит")
                if (ttsError != null) append(", последняя ошибка: ").append(ttsError)
            },
            "Служба спец. возможностей" to CompanionAccessibilityService.describe(ctx),
            "Кадры/с у Искры" to if (FrameStats.hz > 0) "экран ~${FrameStats.hz}, отрисовка ~${FrameStats.drawnHz}" else "нет данных (Искра не на экране)",
            "Режим кадров" to s.fpsMode.title,
            "Группа ожидания Android" to standbyBucket(ctx),
            "Падения" to ru.iskra.core.diag.CrashHistory.summary(Diag.crashTimes(), System.currentTimeMillis(), installedAt(ctx)),
            "Ежедневная копия" to ru.iskra.app.data.AutoBackup.describe(ctx),
            "Расход в покое" to (PowerWatch.summary() ?: "пока нет данных: нужно ≥ 2 замеров без зарядки (замер раз в 5 мин, пока Искра на экране)"),
            "Кэш голоса" to runCatching {
                val c = app.speaker.cache
                "${c.count()} фраз, ${c.sizeBytes() / 1024} КБ; за запуск из кэша ${app.speaker.cacheHits} из ${app.speaker.phrases}"
            }.getOrDefault("?"),
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
            "Вид" to s.avatarLook.title,
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
            signing = signing(ctx),
        )
    }

    /**
     * "release" or "debug", the package, and who signed the APK: a debug key (the repo's
     * app/debug.keystore "CN=Iskra Debug", or any "Android Debug" key) or the release key, with the start of its SHA-256.
     */
    fun signing(ctx: Context): String {
        val type = if (BuildConfig.DEBUG) "debug" else "release"
        val cert = runCatching {
            val pm = ctx.packageManager
            val sig = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
            } ?: return@runCatching "подпись не прочитана"
            val x509 = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(sig.toByteArray().inputStream()) as java.security.cert.X509Certificate
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString(":") { "%02X".format(it) }
            val subject = x509.subjectX500Principal.name
            val debugKey = sha.startsWith(DEBUG_CERT_SHA256) || subject.contains("Debug", ignoreCase = true)
            (if (debugKey) "debug-ключ" else "release-ключ") + ", SHA-256 ${sha.take(11)}…"
        }.getOrElse { "подпись не прочитана: ${it.javaClass.simpleName}" }
        return "$type · ${ctx.packageName} · $cert"
    }

    /** [maxLog]: 60 lines for the clipboard; the file version carries the whole journal. */
    fun report(ctx: Context, maxLog: Int = 60): String {
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
        return DiagReport.build(collect(ctx), Diag.log.entries(), knownSecrets(IskraApp.instance), maxLog = maxLog, time = { fmt.format(Date(it)) })
    }

    fun reportFileName(nowMs: Long = System.currentTimeMillis()): String =
        "iskra-diag-${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date(nowMs))}.txt"

    /**
     * Writes the full report to cache/reports (old reports there are removed) and returns a
     * content:// link that only the app the owner picks in the share sheet can read.
     */
    fun reportFile(ctx: Context): android.net.Uri {
        val dir = java.io.File(ctx.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = java.io.File(dir, reportFileName())
        f.writeText(report(ctx, maxLog = Diag.log.capacity))
        return androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    /** SHA-256 of app/debug.keystore's certificate (public, as is the debug key itself). */
    private const val DEBUG_CERT_SHA256 = "15:AD:00:1F:99:5A:A0:A5"

    fun installedAt(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime }.getOrDefault(System.currentTimeMillis())

    /** Opens the share sheet with the full report as a .txt file (same as the diagnostics button). */
    fun shareReport(ctx: Context) {
        val uri = reportFile(ctx)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Искра: отчёт диагностики")
            clipData = android.content.ClipData.newRawUri("report", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(send, "Отправить отчёт").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun keyState(key: String?) = if (key.isNullOrBlank()) "не сохранён" else "сохранён, ${Redactor.mask(key)}"
    private fun yesNo(b: Boolean) = if (b) "да" else "нет"

    /** Android's app-standby bucket: RARE/RESTRICTED explains a lot of "she disappeared" reports. */
    private fun standbyBucket(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 28) return "—"
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return "?"
        return when (val b = usm.appStandbyBucket) {
            5 /* EXEMPTED */ -> "освобождено от ограничений (лучшее)"
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
