package ru.iskra.core.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Invalidates queued UI callbacks and keeps data erasure behind active agent work. */
class InteractionGate {
    private val work = Mutex()
    private var generation = 0L
    private var resetting = false

    @Synchronized fun beginTurn(): Long? = if (resetting) null else ++generation
    @Synchronized fun snapshot(): Long? = if (resetting) null else generation
    @Synchronized fun isCurrent(token: Long): Boolean = !resetting && token == generation
    @Synchronized fun invalidate() { generation++ }

    /** Called before launching the reset coroutine, so no new work can slip in. */
    @Synchronized fun beginReset(): Boolean {
        if (resetting) return false
        resetting = true
        generation++
        return true
    }

    suspend fun <T> run(token: Long, action: suspend () -> T): T? = work.withLock {
        if (isCurrent(token)) action() else null
    }

    /** Waits for already-running work. Queued work with an old token is skipped. */
    suspend fun resetWhenIdle(action: suspend () -> Unit) {
        try {
            work.withLock { action() }
        } finally {
            synchronized(this) { resetting = false }
        }
    }
}
