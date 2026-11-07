package ru.iskra.app.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.iskra.app.IskraApp
import ru.iskra.app.diag.Diag
import ru.iskra.core.i18n.tr
import java.util.concurrent.TimeUnit

/**
 * Long conversations with only the model on the phone: the cloud compresses old turns into short recaps as it goes
 * (📖 in «Память»), but on the phone that takes a minute, so it happens here — once a day, on charge, while the phone is
 * idle, and only when there is no cloud and the model is a smart one. The recap is an ordinary memory entry.
 * Here too, with whichever brain there is, she writes her diary for the day that passed (🌙 in «Память», 0.15.0).
 */
object NightMemory {
    private const val JOB_ID = 4243

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, NightMemoryJob::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(job) }
    }

    /** Summarizes if it makes sense now; the result for the journal. */
    suspend fun runNow(): String {
        val app = IskraApp.instance
        // The cloud recaps by itself as it goes; at night it writes her diary for the day that passed (a small request).
        if (app.hasCloud()) return tr("есть облако — оно пересказывает само", "the cloud is on — it recaps by itself") +
            diaryText(runCatching { app.agent.writeDiary() }.getOrNull())
        if (!LocalBrain.canSummarize()) return tr("нет умной модели на телефоне", "no smart model on the phone")
        var entry: ru.iskra.core.memory.DiaryEntry? = null
        val saved = LocalBrain.aside {
            app.agent.summarizeOnPhone().also { entry = runCatching { app.agent.writeDiary(onPhone = true) }.getOrNull() }
        } ?: return tr("модель не загрузилась", "the model didn't load")
        return (if (saved == 0) tr("пересказывать пока нечего", "nothing to recap yet") else tr("сжато в пересказы: $saved", "recaps made: $saved")) +
            diaryText(entry)
    }

    /** For the journal: whether she wrote her diary — the day only, never the words. */
    private fun diaryText(e: ru.iskra.core.memory.DiaryEntry?): String = when {
        e == null -> ""
        e.text.isBlank() -> tr("; в дневник писать нечего", "; nothing for the diary")
        else -> tr("; дневник: запись за ${e.day}", "; diary: an entry for ${e.day}")
    }
}

class NightMemoryJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            val r = runCatching { NightMemory.runNow() }.getOrElse { it.message ?: it.javaClass.simpleName }
            Diag.i("memory", tr("Ночной пересказ переписки: $r", "Night recap of the chat: $r"))
            jobFinished(params, false)
        }
        return true
    }

    /** The phone woke up or was unplugged: stop (the model stops mid-word; the turns stay until next time). */
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }
}
