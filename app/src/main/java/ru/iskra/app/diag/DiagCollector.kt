package ru.iskra.app.diag

import ru.iskra.core.i18n.tr
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
            tr("Поверх других приложений", "Over other apps") to DeviceHelper.canDrawOverlays(ctx),
            tr("Микрофон", "Microphone") to granted(Manifest.permission.RECORD_AUDIO),
            tr("Уведомления", "Notifications") to (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)),
            tr("Спец. возможности (управление)", "Accessibility (control)") to CompanionAccessibilityService.isEnabled(ctx),
            tr("Без ограничений батареи", "No battery restrictions") to DeviceHelper.isIgnoringBatteryOptimizations(ctx),
        )

        val speaking = app.speaker.speaking.value
        val ttsError = app.speaker.lastError.value
        val services = listOf(
            tr("Оверлей (Искра на экране)", "Overlay (Iskra on screen)") to Diag.overlayState,
            tr("Слух", "Hearing") to Diag.earState + " · " + ru.iskra.app.voice.EarStats.describe(),
            tr("Модель распознавания", "Speech model") to if (SpeechModel.isReady(ctx)) tr("скачана", "downloaded") else tr("нет", "no"),
            tr("Голос (TTS)", "Voice (TTS)") to buildString {
                append(s.ttsEngine.title)
                if (!s.ttsEnabled) append(tr(", озвучка выключена", ", speech off"))
                if (speaking) append(tr(", сейчас говорит", ", speaking now"))
                if (ttsError != null) append(tr(", последняя ошибка: ", ", last error: ")).append(ttsError)
            },
            tr("Служба спец. возможностей", "Accessibility service") to CompanionAccessibilityService.describe(ctx),
            tr("Кадры/с у Искры", "Iskra fps") to if (FrameStats.hz > 0) tr("экран ~${FrameStats.hz}, отрисовка ~${FrameStats.drawnHz}", "screen ~${FrameStats.hz}, drawing ~${FrameStats.drawnHz}") else tr("нет данных (Искра не на экране)", "no data (Iskra isn't on screen)"),
            tr("Экономия", "Economy") to "${s.economy.title} → ${Diag.economyState}",
            tr("Облако", "Cloud") to app.usage.describe(),
            tr("Телефон", "Phone") to tr("${DeviceHelper.vendor.title}, ${DeviceHelper.deviceClass(ctx).title}, " +
                "${DeviceHelper.totalRamMb(ctx)} МБ ОЗУ, ${Runtime.getRuntime().availableProcessors()} ядер", "${DeviceHelper.vendor.title}, ${DeviceHelper.deviceClass(ctx).title}, ${DeviceHelper.totalRamMb(ctx)} MB RAM, ${Runtime.getRuntime().availableProcessors()} cores") +
                (if (DeviceHelper.animationsOff(ctx)) tr(", анимации выключены в системе", ", animations off in the system") else ""),
            tr("Группа ожидания Android", "Android standby bucket") to standbyBucket(ctx),
            tr("Падения", "Crashes") to ru.iskra.core.diag.CrashHistory.summary(Diag.crashTimes(), System.currentTimeMillis(), installedAt(ctx)),
            tr("Ежедневная копия", "Daily backup") to ru.iskra.app.data.AutoBackup.describe(ctx),
            tr("Расход в покое", "Idle drain") to (PowerWatch.summary() ?: tr("пока нет данных: нужно ≥ 2 замеров без зарядки (замер раз в 5 мин, пока Искра на экране)", "no data yet: needs ≥ 2 samples off the charger (a sample every 5 min while Iskra is on screen)")),
            tr("Кэш голоса", "Voice cache") to runCatching {
                val c = app.speaker.cache
                tr("${c.count()} фраз, ${c.sizeBytes() / 1024} КБ; за запуск из кэша ${app.speaker.cacheHits} из ${app.speaker.phrases}", "${c.count()} phrases, ${c.sizeBytes() / 1024} KB; this run from cache ${app.speaker.cacheHits} of ${app.speaker.phrases}")
            }.getOrDefault("?"),
            tr("Записей в памяти", "Memory entries") to runCatching { app.memory.all().size.toString() }.getOrDefault("?"),
        )

        val llmKey = runCatching { app.vault.load() }.getOrNull()
        val ttsKey = runCatching { app.ttsVault.load() }.getOrNull()
        val settings = listOf(
            tr("Мозг", "Brain") to s.preset.title + if (s.preset != ProviderPreset.OFFLINE) tr(", модель ${s.model.ifBlank { s.preset.defaultModel }}", ", model ${s.model.ifBlank { s.preset.defaultModel }}") else "",
            tr("Ключ мозга", "Brain key") to keyState(llmKey),
            tr("Мозг на телефоне", "Brain on the phone") to when (val l = ru.iskra.app.data.LocalBrain.state.value) {
                is ru.iskra.app.data.LocalBrain.State.Ready -> "${l.model.id}, ${l.bytes / 1_000_000} MB" + if (ru.iskra.app.data.LocalBrain.enabled()) "" else tr(" (выключен)", " (off)")
                is ru.iskra.app.data.LocalBrain.State.Downloading -> tr("скачивается", "downloading") + " ${l.done / 1_000_000}/${l.total / 1_000_000} MB"
                is ru.iskra.app.data.LocalBrain.State.Unsupported -> l.reason
                is ru.iskra.app.data.LocalBrain.State.Failed -> l.message
                ru.iskra.app.data.LocalBrain.State.None -> tr("нет", "no")
            },
            tr("Ключ голоса", "Voice key") to if (s.ttsEngine == TtsEngine.SYSTEM) tr("не нужен", "not needed") else keyState(ttsKey),
            tr("Слушать постоянно", "Listen all the time") to yesNo(s.alwaysListen),
            tr("Слушать при выключенном экране", "Listen with the screen off") to yesNo(s.listenScreenOff),
            tr("Продолжение без имени", "Continue without the name") to yesNo(s.followUp),
            tr("Гуляет сама", "Wanders on her own") to yesNo(s.wander) + tr(", непоседливость ${s.liveliness}", ", restlessness ${s.liveliness}"),
            tr("Размер", "Size") to "${s.avatarSizeDp} dp",
            tr("Вид", "Look") to s.avatarLook.title,
        )

        return DiagInfo(
            appVersion = BuildConfig.VERSION_NAME,
            build = build(),
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            android = tr("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), патч ${Build.VERSION.SECURITY_PATCH}", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}"),
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
            } ?: return@runCatching tr("подпись не прочитана", "signature not read")
            val x509 = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(sig.toByteArray().inputStream()) as java.security.cert.X509Certificate
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString(":") { "%02X".format(it) }
            val subject = x509.subjectX500Principal.name
            val debugKey = sha.startsWith(DEBUG_CERT_SHA256) || subject.contains("Debug", ignoreCase = true)
            (if (debugKey) tr("debug-ключ", "debug key") else tr("release-ключ", "release key")) + ", SHA-256 ${sha.take(11)}…"
        }.getOrElse { tr("подпись не прочитана: ${it.javaClass.simpleName}", "signature not read: ${it.javaClass.simpleName}") }
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
            putExtra(android.content.Intent.EXTRA_SUBJECT, tr("Искра: отчёт диагностики", "Iskra: diagnostics report"))
            clipData = android.content.ClipData.newRawUri("report", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(send, tr("Отправить отчёт", "Send report")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun keyState(key: String?) = if (key.isNullOrBlank()) tr("не сохранён", "not saved") else tr("сохранён, ${Redactor.mask(key)}", "saved, ${Redactor.mask(key)}")
    private fun yesNo(b: Boolean) = if (b) tr("да", "yes") else tr("нет", "no")

    /** Android's app-standby bucket: RARE/RESTRICTED explains a lot of "she disappeared" reports. */
    private fun standbyBucket(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 28) return "—"
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return "?"
        return when (val b = usm.appStandbyBucket) {
            5 /* EXEMPTED */ -> tr("освобождено от ограничений (лучшее)", "exempt from restrictions (best)")
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> tr("активное", "active")
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> tr("рабочий набор", "working set")
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> tr("частое", "frequent")
            UsageStatsManager.STANDBY_BUCKET_RARE -> tr("редкое (Android ограничивает фон)", "rare (Android limits the background)")
            45 /* RESTRICTED, API 30 */ -> tr("ограниченное (фон сильно урезан)", "restricted (background heavily cut)")
            else -> tr("код $b", "code $b")
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
