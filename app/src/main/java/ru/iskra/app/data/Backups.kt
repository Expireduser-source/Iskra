package ru.iskra.app.data

import ru.iskra.core.i18n.tr
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.iskra.app.BuildConfig
import ru.iskra.app.IskraApp
import ru.iskra.app.diag.Diag
import ru.iskra.app.diag.DiagCollector
import ru.iskra.core.backup.Backup
import ru.iskra.core.backup.BackupCodec
import ru.iskra.core.backup.BackupException
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup to / restore from a JSON file the owner picks (Storage Access Framework — no storage
 * permission, the app sees only that one file). Contains memory, settings and mood/style;
 * never API keys, never the chat log.
 */
object Backups {
    data class Preview(val backup: Backup, val lines: List<String>)

    fun suggestedName(nowMs: Long = System.currentTimeMillis()): String =
        "iskra-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(nowMs))}.json"

    private val labels get() = mapOf(
        "preset" to tr("Мозг (сервис)", "Brain (service)"), "baseUrl" to tr("Адрес сервиса", "Service address"), "model" to tr("Модель", "Model"),
        "tts" to tr("Озвучивать ответы", "Speak the replies"), "wander" to tr("Гуляет сама", "Wanders on her own"), "avatarSize" to tr("Размер персонажа", "Character size"),
        "ttsEngine" to tr("Голос (сервис)", "Voice (service)"), "ttsVoice" to tr("Голос", "Voice"), "ttsModel" to tr("Модель голоса", "Voice model"), "ttsBaseUrl" to tr("Адрес сервера голоса", "Voice server address"),
        "alwaysListen" to tr("Слушать постоянно", "Listen all the time"), "listenScreenOff" to tr("Слушать при выключенном экране", "Listen with the screen off"),
        "followUp" to tr("Продолжать без имени", "Continue without the name"), "wakeNames" to tr("Имена", "Names"), "economy" to tr("Экономия", "Economy"), "calmMotion" to tr("Спокойные движения", "Calm movements"),
        "voiceInputOffline" to tr("Голосовой ввод только на телефоне", "Voice input on the phone only"),
        "limitRequests" to tr("Лимит запросов в день", "Requests limit per day"), "limitTokens" to tr("Лимит токенов в день", "Tokens limit per day"),
        "limitTtsChars" to tr("Лимит символов голоса в день", "Voice characters limit per day"), "priceInPerM" to tr("Цена 1 млн входных токенов", "Price of 1M input tokens"),
        "priceOutPerM" to tr("Цена 1 млн выходных токенов", "Price of 1M output tokens"), "priceTtsPerM" to tr("Цена 1 млн символов голоса", "Price of 1M voice characters"), "currency" to tr("Валюта", "Currency"), "liveliness" to tr("Непоседливость", "Restlessness"),
        "avatarLook" to tr("Вид персонажа", "Character look"), "economicalEar" to tr("Экономный слух", "Economical hearing"),
        "weatherCity" to tr("Город для погоды", "Weather city"), "proactive" to tr("Сама заговаривает", "Speaks up on her own"), "quietFrom" to tr("Тихие часы с", "Quiet hours from"),
        "quietTo" to tr("Тихие часы до", "Quiet hours to"), "proactiveVoice" to tr("Заговаривает голосом", "Speaks aloud"),
        "crisisHelp" to tr("Помощь в кризисе", "Help in a crisis"),
    )

    /** Returns the number of memory items written. */
    suspend fun export(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val app = IskraApp.instance
        val b = Backup(
            createdAtMs = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            settings = app.settings.exportMap(),
            memory = app.memory.all(),
            state = app.stateStore.load(),
            notes = app.notes.all(),
        )
        val text = BackupCodec.encode(b, knownSecrets = DiagCollector.knownSecrets(app))
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException(tr("Не удалось открыть файл для записи.", "Couldn't open the file for writing."))
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Diag.i("backup", tr("Экспорт: память ${b.memory.size} ${plural(b.memory.size)}, ${b.settings.size} настроек, ${text.length} байт", "Export: memory ${b.memory.size} ${plural(b.memory.size)}, ${b.settings.size} settings, ${text.length} bytes"))
        b.memory.size
    }

    suspend fun read(ctx: Context, uri: Uri): Preview = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw BackupException(tr("Не удалось открыть файл.", "Couldn't open the file."))
        val bytes = input.use { s ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > BackupCodec.MAX_BYTES) throw BackupException(tr("Файл слишком большой для резервной копии Искры.", "The file is too big for an Iskra backup."))
            }
            buf.toByteArray()
        }
        val app = IskraApp.instance
        val b = BackupCodec.decode(String(bytes, Charsets.UTF_8), knownSecrets = DiagCollector.knownSecrets(app))
        Preview(b, describe(b))
    }

    /** What exactly will be replaced — shown before the owner confirms. */
    private fun describe(b: Backup): List<String> {
        val app = IskraApp.instance
        val now = app.memory.all().size
        val current = app.settings.exportMap()
        val imported = app.settings.exportMap(app.settings.fromMap(b.settings, app.settings.load()))
        val changed = imported.filter { (k, v) -> current[k] != v }.keys.map { labels[it] ?: it }
        val date = if (b.createdAtMs > 0) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date(b.createdAtMs)) else tr("неизвестно", "unknown")
        return buildList {
            add(tr("Копия от $date, версия ${b.appVersion.ifBlank { "?" }}.", "Backup from $date, version ${b.appVersion.ifBlank { "?" }}."))
            add(tr("Память: сейчас $now ${plural(now)} → будет ${b.memory.size}. Текущие записи удалятся.", "Memory: now $now ${plural(now)} → will be ${b.memory.size}. Current entries will be deleted."))
            if (b.notes.isNotEmpty()) add(tr("Заметки: сейчас ${app.notes.all().size} → будет ${b.notes.size}.", "Notes: now ${app.notes.all().size} → will be ${b.notes.size}."))
            add(if (changed.isEmpty()) tr("Настройки: не изменятся.", "Settings: won't change.") else tr("Настройки: изменятся ${changed.size} — ${changed.joinToString(", ")}.", "Settings: ${changed.size} will change — ${changed.joinToString(", ")}."))
            if (b.state != null) add(tr("Настроение, привязанность и стиль общения: заменятся сохранёнными.", "Mood, bond and conversation style: replaced with the saved ones."))
            add(tr("Не меняются: ключи API, переписка, разрешения, показ Искры на экране.", "Not changed: API keys, chat, permissions, showing Iskra on screen."))
        }
    }

    /** Applies a confirmed backup. Returns memory items skipped by the memory rules (passwords, too long…). */
    suspend fun apply(b: Backup): Int = withContext(Dispatchers.IO) {
        val app = IskraApp.instance
        val cur = app.settings.load()
        app.settings.save(app.settings.fromMap(b.settings, cur).copy(overlayEnabled = cur.overlayEnabled))
        val skipped = app.memory.replaceAll(b.memory)
        if (b.notes.isNotEmpty()) app.notes.replaceAll(b.notes)
        b.state?.let { app.stateStore.save(it) }
        // The city comes as a name; find its coordinates again (only the name goes to open-meteo).
        val city = app.settings.load().weatherCity
        if (city.isNotBlank() && app.settings.weatherPlace() == null) {
            runCatching { ru.iskra.core.skills.WeatherClient().geocode(city) }.getOrNull()?.let(app.settings::saveWeatherPlace)
        }
        Diag.i("backup", tr("Импорт: память ${b.memory.size - skipped} ${plural(b.memory.size - skipped)}, пропущено $skipped, ${b.settings.size} настроек", "Import: memory ${b.memory.size - skipped} ${plural(b.memory.size - skipped)}, skipped $skipped, ${b.settings.size} settings"))
        skipped
    }

    private fun plural(n: Int) = tr(ru.iskra.core.i18n.Plural.ru(n.toLong(), "запись", "записи", "записей"), if (n == 1) "entry" else "entries")
}
