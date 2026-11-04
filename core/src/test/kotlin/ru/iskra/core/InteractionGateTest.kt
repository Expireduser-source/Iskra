package ru.iskra.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.iskra.core.agent.InteractionGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionGateTest {
    @Test fun cancelledReplyCannotSpeakOrClearReplacementBusyState() = runTest {
        val gate = InteractionGate()
        val first = assertNotNull(gate.beginTurn())
        var speaking = false
        var busy = true
        // These callbacks have already been queued when the owner presses Stop.
        launch { if (gate.isCurrent(first)) speaking = true }
        launch { if (gate.isCurrent(first)) busy = false }
        gate.invalidate()
        val replacement = assertNotNull(gate.beginTurn())

        runCurrent()

        assertFalse(speaking)
        assertTrue(busy)
        assertTrue(gate.isCurrent(replacement))
    }

    @Test fun erasureWaitsForRunningWorkAndSkipsQueuedInteractions() = runTest {
        val gate = InteractionGate()
        val token = assertNotNull(gate.beginTurn())
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        launch {
            gate.run(token) {
                events += "started"
                release.await()
                events += "write finished"
            }
        }
        runCurrent()
        launch { gate.run(token) { events += "stale queued write" } }
        runCurrent()

        assertTrue(gate.beginReset())
        assertFalse(gate.beginReset())
        launch { gate.resetWhenIdle { events += "erased" } }
        runCurrent()
        assertEquals(listOf("started"), events)
        assertNull(gate.beginTurn())
        assertNull(gate.snapshot())
        assertFalse(gate.isCurrent(token))

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("started", "write finished", "erased"), events)
        assertNotNull(gate.beginTurn())
    }

    @Test fun cancellationCleanupFinishesBeforeErasure() = runTest {
        val gate = InteractionGate()
        val token = assertNotNull(gate.beginTurn())
        val stored = mutableListOf<String>()
        val request = launch {
            gate.run(token) {
                try { awaitCancellation() }
                finally { stored += "cleanup" }
            }
        }
        runCurrent()
        assertTrue(gate.beginReset())
        request.cancel()
        launch { gate.resetWhenIdle { stored.clear() } }

        advanceUntilIdle()

        assertTrue(request.isCompleted)
        assertTrue(stored.isEmpty())
    }

    @Test fun failedErasureReopensInputWithoutRevivingOldCallbacks() = runTest {
        val gate = InteractionGate()
        val old = assertNotNull(gate.beginTurn())
        assertTrue(gate.beginReset())

        assertFailsWith<IllegalStateException> {
            gate.resetWhenIdle { error("storage unavailable") }
        }

        assertFalse(gate.isCurrent(old))
        assertNotNull(gate.beginTurn())
    }
}
