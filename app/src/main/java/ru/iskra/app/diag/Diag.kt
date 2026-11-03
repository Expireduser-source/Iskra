package ru.iskra.app.diag

import android.content.Context
import android.util.Log
import ru.iskra.core.diag.DiagEntry
import ru.iskra.core.diag.RingLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide diagnostics journal: the last ~500 technical events and errors, kept on disk
 * (`files/diag.log`) so they survive a crash or a HyperOS kill.
 *
 * Rules for callers: log events, states and error types — never what the owner said or wrote,
 * and never Iskra's replies. Keys are masked by [RingLog] anyway.
 */
object Diag {
    private const val TAG = "Iskra"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "iskra-diag").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    private var file: File? = null
    @Volatile private var secrets: () -> Collection<String> = { emptyList() }

    val log = RingLog(capacity = 500, knownSecrets = { runCatching { secrets() }.getOrDefault(emptyList()) })

    /** Runtime states shown on the diagnostics screen, updated by the services themselves. */
    @Volatile var overlayState: String = "не запущен"
    @Volatile var earState: String = "выключен"

    fun init(ctx: Context, knownSecrets: () -> Collection<String>) {
        secrets = knownSecrets
        val f = File(ctx.filesDir, "diag.log").also { file = it }
        runCatching { if (f.exists()) log.load(f.readText()) }
        installCrashHandler()
    }

    fun i(tag: String, msg: String) = add(DiagEntry.Level.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) =
        add(DiagEntry.Level.WARN, tag, if (t == null) msg else "$msg: ${describe(t)}")
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add(DiagEntry.Level.ERROR, tag, if (t == null) msg else "$msg: ${describe(t)}")

    fun clear() {
        log.clear()
        persist()
    }

    private fun add(level: DiagEntry.Level, tag: String, msg: String) {
        val e = log.add(level, tag, msg)
        // Logcat gets the same redacted line; useful with `adb logcat -s Iskra`.
        when (level) {
            DiagEntry.Level.ERROR -> Log.e(TAG, "${e.tag}: ${e.message}")
            DiagEntry.Level.WARN -> Log.w(TAG, "${e.tag}: ${e.message}")
            DiagEntry.Level.INFO -> Log.i(TAG, "${e.tag}: ${e.message}")
        }
        persist()
    }

    /** Coalesced background write: many events in a burst cost one file write. */
    private fun persist() {
        if (!pending.compareAndSet(false, true)) return
        io.execute {
            pending.set(false)
            writeNow()
        }
    }

    @Synchronized
    private fun writeNow() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(log.encode())
            tmp.renameTo(f)
        }
    }

    /** Exception type, a short message and the first frames of our own code. */
    fun describe(t: Throwable): String {
        val own = t.stackTrace.filter { it.className.startsWith("ru.iskra") }.take(4)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val cause = t.cause?.let { " / причина ${it.javaClass.simpleName}" }.orEmpty()
        return "${t.javaClass.simpleName}(${t.message?.take(120).orEmpty()})$cause" + if (own.isNotEmpty()) " @ $own" else ""
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                log.add(DiagEntry.Level.ERROR, "crash", "Падение в потоке ${thread.name}: ${describe(t)}")
                writeNow() // synchronously: the process is about to die
            }
            previous?.uncaughtException(thread, t)
        }
    }
}
