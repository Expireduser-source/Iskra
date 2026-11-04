package ru.iskra.app.data

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

    private val labels = mapOf(
        "preset" to "Мозг (сервис)", "baseUrl" to "Адрес сервиса", "model" to "Модель",
        "tts" to "Озвучивать ответы", "wander" to "Гуляет сама", "avatarSize" to "Размер персонажа",
        "ttsEngine" to "Голос (сервис)", "ttsVoice" to "Голос", "ttsModel" to "Модель голоса", "ttsBaseUrl" to "Адрес сервера голоса",
        "alwaysListen" to "Слушать постоянно", "listenScreenOff" to "Слушать при выключенном экране",
        "followUp" to "Продолжать без имени", "wakeNames" to "Имена", "economy" to "Экономия", "calmMotion" to "Спокойные движения",
        "voiceInputOffline" to "Голосовой ввод только на телефоне",
        "limitRequests" to "Лимит запросов в день", "limitTokens" to "Лимит токенов в день",
        "limitTtsChars" to "Лимит символов голоса в день", "priceInPerM" to "Цена 1 млн входных токенов",
        "priceOutPerM" to "Цена 1 млн выходных токенов", "priceTtsPerM" to "Цена 1 млн символов голоса", "currency" to "Валюта", "liveliness" to "Непоседливость",
        "avatarLook" to "Вид персонажа", "economicalEar" to "Экономный слух",
        "weatherCity" to "Город для погоды", "proactive" to "Сама заговаривает", "quietFrom" to "Тихие часы с",
        "quietTo" to "Тихие часы до", "proactiveVoice" to "Заговаривает голосом",
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
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Не удалось открыть файл для записи.")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Diag.i("backup", "Экспорт: память ${b.memory.size} ${plural(b.memory.size)}, ${b.settings.size} настроек, ${text.length} байт")
        b.memory.size
    }

    suspend fun read(ctx: Context, uri: Uri): Preview = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw BackupException("Не удалось открыть файл.")
        val bytes = input.use { s ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > BackupCodec.MAX_BYTES) throw BackupException("Файл слишком большой для резервной копии Искры.")
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
        val date = if (b.createdAtMs > 0) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date(b.createdAtMs)) else "неизвестно"
        return buildList {
            add("Копия от $date, версия ${b.appVersion.ifBlank { "?" }}.")
            add("Память: сейчас $now ${plural(now)} → будет ${b.memory.size}. Текущие записи удалятся.")
            if (b.notes.isNotEmpty()) add("Заметки: сейчас ${app.notes.all().size} → будет ${b.notes.size}.")
            add(if (changed.isEmpty()) "Настройки: не изменятся." else "Настройки: изменятся ${changed.size} — ${changed.joinToString(", ")}.")
            if (b.state != null) add("Настроение, привязанность и стиль общения: заменятся сохранёнными.")
            add("Не меняются: ключи API, переписка, разрешения, показ Искры на экране.")
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
        Diag.i("backup", "Импорт: память ${b.memory.size - skipped} ${plural(b.memory.size - skipped)}, пропущено $skipped, ${b.settings.size} настроек")
        skipped
    }

    private fun plural(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "запись"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "записи"
        else -> "записей"
    }
}
