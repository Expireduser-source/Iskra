package ru.iskra.core.llm.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import ru.iskra.core.llm.ToolCall
import ru.iskra.core.llm.ToolSpec

/**
 * Gemma 4's own tool-calling markup, as Google's chat template writes it (2026-07-09): declarations in the system turn
 * (`<|tool>declaration:set_timer{description:<|"|>…<|"|>,parameters:{…}}<tool|>`), the model's call
 * (`<|tool_call>call:set_timer{seconds:300}<tool_call|>`) and the result in the same model turn
 * (`<|tool_response>response:set_timer{value:<|"|>…<|"|>}<tool_response|>`), after which the model goes on.
 * Strings are quoted with `<|"|>`; keys of arguments are bare.
 */
object Gemma4Tools {
    const val CALL_OPEN = "<|tool_call>"
    const val CALL_CLOSE = "<tool_call|>"
    private const val Q = "<|\"|>"

    fun declarations(tools: List<ToolSpec>): String = tools.joinToString("") { "<|tool>" + declaration(it) + "<tool|>" }

    fun declaration(t: ToolSpec): String = buildString {
        append("declaration:").append(t.name).append("{description:").append(Q).append(t.description).append(Q)
        val params = t.parameters
        if (params.isNotEmpty()) {
            append(",parameters:{")
            val props = params["properties"] as? JsonObject
            val required = (params["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (!props.isNullOrEmpty()) append("properties:{").append(parameters(props)).append("},")
            if (required.isNotEmpty()) append("required:[").append(required.joinToString(",") { Q + it + Q }).append("],")
            (params["type"] as? JsonPrimitive)?.contentOrNull?.let { append("type:").append(Q).append(it.uppercase()).append(Q).append("}") }
        }
        append("}")
    }

    /** One property after another, sorted by name, each as `name:{description:…,enum:[…],type:<|"|>STRING<|"|>}`. */
    private fun parameters(props: JsonObject): String = props.entries.sortedBy { it.key }.joinToString(",") { (key, v) ->
        val value = v as? JsonObject ?: JsonObject(emptyMap())
        val type = (value["type"] as? JsonPrimitive)?.contentOrNull?.uppercase().orEmpty()
        val parts = ArrayList<String>()
        (value["description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += "description:$Q$it$Q" }
        if (type == "STRING") (value["enum"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { parts += "enum:" + argument(it, escapeKeys = true) }
        if (type == "ARRAY") (value["items"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { items ->
            parts += "items:{" + items.entries.sortedBy { it.key }.filter { it.value !is JsonNull }.joinToString(",") { (k, iv) ->
                when (k) {
                    "properties" -> "properties:{" + ((iv as? JsonObject)?.let(::parameters).orEmpty()) + "}"
                    "required" -> "required:[" + (iv as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",") { Q + it + Q } + "]"
                    "type" -> "type:" + argument((iv as? JsonPrimitive)?.contentOrNull?.uppercase()?.let(::JsonPrimitive) ?: iv, escapeKeys = true)
                    else -> "$k:" + argument(iv, escapeKeys = true)
                }
            } + "}"
        }
        if ((value["nullable"] as? JsonPrimitive)?.booleanOrNull == true) parts += "nullable:true"
        if (type == "OBJECT") {
            (value["properties"] as? JsonObject)?.let { parts += "properties:{" + parameters(it) + "}" }
            (value["required"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { r ->
                parts += "required:[" + r.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",") { Q + it + Q } + "]"
            }
        }
        parts += "type:$Q$type$Q"
        "$key:{" + parts.joinToString(",") + "}"
    }

    /** A value as the template writes it: strings in `<|"|>`, numbers and booleans bare, keys quoted only if asked. */
    private fun argument(v: JsonElement, escapeKeys: Boolean): String = when (v) {
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            v.isString -> Q + v.content + Q
            else -> v.content
        }
        is JsonObject -> v.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, x) ->
            (if (escapeKeys) Q + k + Q else k) + ":" + argument(x, escapeKeys)
        }
        is JsonArray -> v.joinToString(",", "[", "]") { argument(it, escapeKeys) }
    }

    /** The model's call as it is written back in the dialogue. */
    fun call(c: ToolCall): String = CALL_OPEN + "call:" + c.name + argument(c.arguments, escapeKeys = false) + CALL_CLOSE

    /** A tool's result in the model's turn, after its call. */
    fun response(name: String, content: String): String = "<|tool_response>response:$name{value:$Q$content$Q}<tool_response|>"

    /**
     * Whether [start] — the beginning of a reply — is a call written without its markup: `set_timer{…}` or
     * `call:set_timer{…}`, with one of [names]. E4B wrote «set_timer{label:<|"|>Яйца всмятку<|"|>, minutes:5}» as its whole
     * reply (ARM evaluation, 0.15.0): no timer, and those words in the chat. Null while it can't be told yet (a beginning
     * of a name), false when it isn't one.
     */
    fun bareStart(start: String, names: Collection<String>): Boolean? {
        if (names.isEmpty() || start.isEmpty()) return false
        if ("call:".startsWith(start)) return null
        val s = if (start.startsWith("call:")) start.substring(5) else start
        var undecided = false
        for (n in names) {
            if (s.startsWith(n)) {
                val rest = s.substring(n.length).trimStart()
                if (rest.isEmpty()) undecided = true else if (rest[0] == '{') return true
            } else if (n.startsWith(s)) undecided = true
        }
        return if (undecided) null else false
    }

    /**
     * What the model wrote: the text before its first call, and the calls (`<|tool_call>call:name{…}`, the closing tag may
     * be cut off by the stop). A call that can't be read is dropped — the text stays. A reply that is a call of one of
     * [names] written without the markup ([bareStart]) is that call.
     */
    fun parse(text: String, names: Collection<String> = emptyList()): Pair<String, List<ToolCall>> {
        val first = text.indexOf(CALL_OPEN)
        if (first < 0) {
            val t = text.trimStart()
            if (bareStart(t, names) == true) Reader(if (t.startsWith("call:")) t else "call:$t").readCall()
                ?.takeIf { (name, _) -> name in names }?.let { (name, args) -> return "" to listOf(ToolCall("call_1", name, args)) }
            return text to emptyList()
        }
        val calls = ArrayList<ToolCall>()
        var at = first
        while (at >= 0) {
            val start = at + CALL_OPEN.length
            val end = text.indexOf(CALL_CLOSE, start).let { if (it < 0) text.length else it }
            Reader(text.substring(start, end)).readCall()?.let { (name, args) -> calls += ToolCall("call_${calls.size + 1}", name, args) }
            at = text.indexOf(CALL_OPEN, end)
        }
        return text.substring(0, first).trimEnd() to calls
    }

    /** Reads `call:name{key:value,…}` with Gemma's quoting into JSON. */
    private class Reader(private val s: String) {
        private var i = 0

        fun readCall(): Pair<String, JsonObject>? = runCatching {
            skip()
            if (!s.startsWith("call:", i)) return null
            i += 5
            val name = s.substring(i).takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            if (name.isEmpty()) return null
            i += name.length
            skip()
            val args = if (i < s.length && s[i] == '{') readObject() else JsonObject(emptyMap())
            name to args
        }.getOrNull()

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun readValue(): JsonElement {
            skip()
            return when {
                s.startsWith(Q, i) -> { i += Q.length; val end = s.indexOf(Q, i).also { require(it >= 0) }; JsonPrimitive(s.substring(i, end)).also { i = end + Q.length } }
                s[i] == '"' -> { val end = s.indexOf('"', i + 1).also { require(it >= 0) }; JsonPrimitive(s.substring(i + 1, end)).also { i = end + 1 } }
                s[i] == '{' -> readObject()
                s[i] == '[' -> readArray()
                else -> {
                    val word = s.substring(i).takeWhile { it !in ",}]" }.trim()
                    i += s.substring(i).takeWhile { it !in ",}]" }.length
                    when {
                        word == "true" || word == "false" -> JsonPrimitive(word == "true")
                        word == "null" -> JsonNull
                        word.toLongOrNull() != null -> JsonPrimitive(word.toLong())
                        word.toDoubleOrNull() != null -> JsonPrimitive(word.toDouble())
                        else -> JsonPrimitive(word)
                    }
                }
            }
        }

        private fun readKey(): String {
            skip()
            if (s.startsWith(Q, i)) { i += Q.length; val end = s.indexOf(Q, i); return s.substring(i, end).also { i = end + Q.length } }
            if (s[i] == '"') { val end = s.indexOf('"', i + 1); return s.substring(i + 1, end).also { i = end + 1 } }
            val key = s.substring(i).takeWhile { it != ':' }.trim()
            i += s.substring(i).takeWhile { it != ':' }.length
            return key
        }

        private fun readObject(): JsonObject {
            require(s[i] == '{'); i++
            val map = LinkedHashMap<String, JsonElement>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(map) }
            while (i < s.length) {
                val key = readKey()
                skip(); require(s[i] == ':'); i++
                map[key] = readValue()
                skip()
                if (i >= s.length) break
                when (s[i]) { ',' -> i++; '}' -> { i++; return JsonObject(map) }; else -> error("unexpected ${s[i]}") }
            }
            return JsonObject(map) // cut off: what was read
        }

        private fun readArray(): JsonArray {
            require(s[i] == '['); i++
            val list = ArrayList<JsonElement>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(list) }
            while (i < s.length) {
                list += readValue()
                skip()
                if (i >= s.length) break
                when (s[i]) { ',' -> i++; ']' -> { i++; return JsonArray(list) }; else -> error("unexpected ${s[i]}") }
            }
            return JsonArray(list)
        }
    }

    @Suppress("unused") private fun JsonPrimitive.number(): Number? = longOrNull ?: doubleOrNull
}
