package ru.iskra.app.data

import ru.iskra.core.i18n.tr
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
        Diag.i("backup", tr("Ежедневная копия включена", "Daily backup on"))
    }

    fun disable(ctx: Context) {
        folder(ctx)?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(ctx).edit().clear().apply()
        ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        Diag.i("backup", tr("Ежедневная копия выключена", "Daily backup off"))
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
            .onFailure { Diag.e("backup", tr("Не удалось запланировать ежедневную копию", "Couldn't schedule the daily backup"), it) }
    }

    /** Writes today's copy (replacing an earlier one from today) and removes copies beyond [KEEP]. */
    suspend fun runNow(ctx: Context): String = withContext(Dispatchers.IO) {
        val tree = folder(ctx) ?: return@withContext tr("Папка не выбрана", "No folder chosen")
        val app = ru.iskra.app.IskraApp.instance
        // After «Стереть всё» an empty copy would push the good ones out of the last KEEP.
        if (app.memory.all().isEmpty() && app.notes.all().isEmpty() && lastRunMs(ctx) > 0)
            return@withContext tr("Память и заметки пусты — копию не делаю, старые копии не трогаю", "Memory and notes are empty — not making a backup, old backups untouched")
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
                ?: error(tr("папка не даёт создать файл", "the folder doesn't allow creating a file"))
            val n = Backups.export(ctx, target)
            // Names sort by date: keep the newest KEEP.
            val all = (existing.map { it.first } + name).distinct().sortedDescending()
            for (old in all.drop(KEEP)) existing.firstOrNull { it.first == old }?.let {
                runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it.second)) }
            }
            prefs(ctx).edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
            tr("Копия сохранена: $name, записей памяти $n", "Backup saved: $name, memory entries $n")
        } catch (e: Exception) {
            val msg = if (e is SecurityException) tr("нет доступа к папке — выбери её заново", "no access to the folder — choose it again") else (e.message ?: e.javaClass.simpleName)
            prefs(ctx).edit().putString("error", msg).apply()
            Diag.e("backup", tr("Ежедневная копия не сохранена: $msg", "Daily backup not saved: $msg"))
            tr("Не удалось: $msg", "Failed: $msg")
        }
    }

    fun describe(ctx: Context): String {
        val f = folder(ctx) ?: return tr("выключена", "off")
        val last = lastRunMs(ctx).takeIf { it > 0 }?.let { SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(it)) } ?: tr("ещё не было", "not yet")
        val err = lastError(ctx)?.let { tr(" · ошибка: $it", " · error: $it") } ?: ""
        return tr("в папку ${f.lastPathSegment?.substringAfterLast(':') ?: "?"}, последняя $last$err", "to the folder ${f.lastPathSegment?.substringAfterLast(':') ?: "?"}, last $last$err")
    }
}

class AutoBackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val r = AutoBackup.runNow(applicationContext)
            Diag.i("backup", tr("Ежедневная копия: $r", "Daily backup: $r"))
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
