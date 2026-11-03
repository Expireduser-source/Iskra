package ru.iskra.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import ru.iskra.core.tools.ConfirmationGate

/**
 * Bridges [ConfirmationGate] to the UI: the agent suspends until a visible chat screen answers.
 * No answer within 60 s (or no screen) = denied. Safe default.
 */
class ConfirmBridge : ConfirmationGate {
    class Request(val title: String, val details: String) {
        internal val answer = CompletableDeferred<Boolean>()
        fun respond(ok: Boolean) { answer.complete(ok) }
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending

    override suspend fun confirm(title: String, details: String): Boolean {
        val r = Request(title, details)
        _pending.value = r
        return try {
            withTimeoutOrNull(60_000) { r.answer.await() } ?: false
        } finally {
            _pending.compareAndSet(r, null)
        }
    }
}
