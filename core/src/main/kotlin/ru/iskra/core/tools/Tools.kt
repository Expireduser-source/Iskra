package ru.iskra.core.tools

import kotlinx.serialization.json.JsonObject
import ru.iskra.core.llm.ToolSpec

/**
 * SAFE     — executes immediately; the result is always shown to the user.
 * CONFIRM  — requires an explicit user confirmation for every call.
 * FORBIDDEN — never executed (kept so the model gets a clear refusal).
 */
enum class Risk { SAFE, CONFIRM, FORBIDDEN }

data class ToolOutcome(val ok: Boolean, val message: String)

interface Tool {
    val spec: ToolSpec
    val risk: Risk
    /** Human-readable description of what this call will do, used in confirmation dialogs. */
    fun describe(args: JsonObject): String
    suspend fun execute(args: JsonObject): ToolOutcome
    /** The outcome message is the answer itself (weather, a list), not a "done" report. */
    val informational: Boolean get() = false
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = LinkedHashMap<String, Tool>()
    init { tools.forEach(::register) }

    fun register(tool: Tool) {
        require(tool.spec.name.matches(Regex("^[a-z][a-z0-9_]{1,63}$"))) { "bad tool name ${tool.spec.name}" }
        require(tool.spec.name !in byName) { "duplicate tool ${tool.spec.name}" }
        byName[tool.spec.name] = tool
    }

    operator fun get(name: String): Tool? = byName[name]
    fun specs(): List<ToolSpec> = byName.values.filter { it.risk != Risk.FORBIDDEN }.map { it.spec }
    fun names(): Set<String> = byName.keys
}

/** UI hook that asks the owner to approve an action. */
fun interface ConfirmationGate {
    suspend fun confirm(title: String, details: String): Boolean
}

data class ActionRecord(val tool: String, val description: String, val outcome: ToolOutcome)

/**
 * Central policy point: every tool call — whether proposed by the LLM or by the local command
 * parser — goes through here. Enforces risk levels, user overrides and a rate limit.
 */
class SafetyPolicy(
    private val gate: ConfirmationGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxActionsPerMinute: Int = 6,
    /** Tools the owner explicitly disabled in settings. */
    private val disabled: () -> Set<String> = { emptySet() },
) {
    private val recent = ArrayDeque<Long>()

    suspend fun run(tool: Tool?, name: String, args: JsonObject): ActionRecord {
        if (tool == null) return ActionRecord(name, name, ToolOutcome(false, "Неизвестный инструмент: $name"))
        val description = runCatching { tool.describe(args) }.getOrElse { name }
        if (name in disabled()) return ActionRecord(name, description, ToolOutcome(false, "Владелец отключил это действие в настройках."))
        if (tool.risk == Risk.FORBIDDEN) return ActionRecord(name, description, ToolOutcome(false, "Это действие запрещено."))
        if (!takeRateSlot()) return ActionRecord(name, description, ToolOutcome(false, "Слишком много действий подряд — остановилась для безопасности."))
        // CONFIRM tools need approval on every call, even when the owner typed the command:
        // speech recognition and fuzzy parsing can misfire.
        if (tool.risk == Risk.CONFIRM) {
            val ok = gate.confirm("Разрешить действие?", description)
            if (!ok) return ActionRecord(name, description, ToolOutcome(false, "Владелец отклонил действие."))
        }
        val outcome = try {
            tool.execute(args)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome(false, "Ошибка при выполнении: ${e.message ?: e.javaClass.simpleName}")
        }
        return ActionRecord(name, description, outcome)
    }

    @Synchronized
    private fun takeRateSlot(): Boolean {
        val now = clock()
        while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
        if (recent.size >= maxActionsPerMinute) return false
        recent.addLast(now)
        return true
    }
}
