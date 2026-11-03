package ru.iskra.app.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.iskra.app.diag.Diag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Stage 5: a daily copy of memory, notes and settings (the same JSON as «Сохранить копию», without
 * keys) into a folder the owner picked once (SAF tree). The last [KEEP] copies are kept. The folder
 * can be on the phone or an SD card; files survive reinstalling the app.
 */
object AutoBackup {
    const val KEEP = 7
    private const val JOB_ID = 4242
    private val nameRe = Regex("""^iskra-backup-\d{4}-\d{2}-\d{2}\.json$""")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autobackup", Context.MODE_PRIVATE)

    fun folder(ctx: Context): Uri? = prefs(ctx).getString("tree", null)?.let(Uri::parse)
    fun lastRunMs(ctx: Context) = prefs(ctx).getLong("last", 0L)
    fun lastError(ctx: Context): String? = prefs(ctx).getString("error", null)

    fun enable(ctx: Context, tree: Uri) {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(ctx).edit().putString("tree", tree.toString()).remove("error").apply()
        schedule(ctx)
        Diag.i("backup", "Ежедневная копия включена")
    }

    fun disable(ctx: Context) {
        folder(ctx)?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(ctx).edit().clear().apply()
        ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        Diag.i("backup", "Ежедневная копия выключена")
    }

    /** Once a day, when the battery is not low; survives reboots. */
    fun schedule(ctx: Context, onlyIfMissing: Boolean = false) {
        if (folder(ctx) == null) return
        if (onlyIfMissing && ctx.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoBackupJob::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        runCatching { ctx.getSystemService(JobScheduler::class.java).schedule(job) }
            .onFailure { Diag.e("backup", "Не удалось запланировать ежедневную копию", it) }
    }

    /** Writes today's copy (replacing an earlier one from today) and removes copies beyond [KEEP]. */
    suspend fun runNow(ctx: Context): String = withContext(Dispatchers.IO) {
        val tree = folder(ctx) ?: return@withContext "Папка не выбрана"
        val app = ru.iskra.app.IskraApp.instance
        // After «Стереть всё» an empty copy would push the good ones out of the last KEEP.
        if (app.memory.all().isEmpty() && app.notes.all().isEmpty() && lastRunMs(ctx) > 0)
            return@withContext "Память и заметки пусты — копию не делаю, старые копии не трогаю"
        try {
            val cr = ctx.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val existing = ArrayList<Pair<String, String>>() // name, documentId
            cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { c ->
                while (c.moveToNext()) { val n = c.getString(0) ?: continue; if (nameRe.matches(n)) existing += n to c.getString(1) }
            }
            val name = Backups.suggestedName()
            val target = existing.firstOrNull { it.first == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.second) }
                ?: DocumentsContract.createDocument(cr, parent, "application/json", name)
                ?: error("папка не даёт создать файл")
            val n = Backups.export(ctx, target)
            // Names sort by date: keep the newest KEEP.
            val all = (existing.map { it.first } + name).distinct().sortedDescending()
            for (old in all.drop(KEEP)) existing.firstOrNull { it.first == old }?.let {
                runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it.second)) }
            }
            prefs(ctx).edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
            "Копия сохранена: $name, записей памяти $n"
        } catch (e: Exception) {
            val msg = if (e is SecurityException) "нет доступа к папке — выбери её заново" else (e.message ?: e.javaClass.simpleName)
            prefs(ctx).edit().putString("error", msg).apply()
            Diag.e("backup", "Ежедневная копия не сохранена: $msg")
            "Не удалось: $msg"
        }
    }

    fun describe(ctx: Context): String {
        val f = folder(ctx) ?: return "выключена"
        val last = lastRunMs(ctx).takeIf { it > 0 }?.let { SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(it)) } ?: "ещё не было"
        val err = lastError(ctx)?.let { " · ошибка: $it" } ?: ""
        return "в папку ${f.lastPathSegment?.substringAfterLast(':') ?: "?"}, последняя $last$err"
    }
}

class AutoBackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val r = AutoBackup.runNow(applicationContext)
            Diag.i("backup", "Ежедневная копия: $r")
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
