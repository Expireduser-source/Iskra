package ru.iskra.core.llm.local

import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Reads what a GGUF model file says about itself (header only, no weights): the chat template, so a model the owner
 * put in by hand gets the right dialogue format.
 */
object Gguf {
    fun chatTemplate(file: File): String? = runCatching { file.inputStream().buffered(1 shl 16).use(::chatTemplate) }.getOrNull()

    fun chatTemplate(input: InputStream): String? {
        val d = DataInputStream(input)
        fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(d.readInt()))
        fun u64(): Long = java.lang.Long.reverseBytes(d.readLong())
        fun skipBytes(n: Long) { var left = n; while (left > 0) { val k = d.skip(left); if (k <= 0) { d.readByte(); left-- } else left -= k } }
        fun str(): String { val n = u64(); require(n in 0..64_000_000) { "bad string" }; return String(ByteArray(n.toInt()).also { d.readFully(it) }, Charsets.UTF_8) }
        val sizes = mapOf(0 to 1L, 1 to 1L, 2 to 2L, 3 to 2L, 4 to 4L, 5 to 4L, 6 to 4L, 7 to 1L, 10 to 8L, 11 to 8L, 12 to 8L)
        fun skip(type: Int) {
            when (type) {
                in sizes -> skipBytes(sizes.getValue(type))
                8 -> skipBytes(u64())
                9 -> {
                    val item = u32().toInt()
                    val n = u64()
                    if (item in sizes) skipBytes(sizes.getValue(item) * n) else repeat(n.toInt()) { skip(item) }
                }
                else -> throw IllegalArgumentException("bad type $type")
            }
        }
        val magic = ByteArray(4).also { d.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != "GGUF") return null
        u32()            // version
        u64()            // tensors
        val kv = u64()
        repeat(kv.toInt()) {
            val key = str()
            val type = u32().toInt()
            if (key == "tokenizer.chat_template" && type == 8) return str()
            skip(type)
        }
        return null
    }
}

/** The dialogue format a chat template asks for. */
fun ChatFormat.Companion.detect(template: String?, fallback: ChatFormat = ChatFormat.CHATML): ChatFormat = when {
    template == null -> fallback
    "<|turn>" in template -> ChatFormat.GEMMA4
    "<start_of_turn>" in template -> ChatFormat.GEMMA
    "<|start_header_id|>" in template -> ChatFormat.LLAMA3
    "<|im_start|>" in template -> if ("enable_thinking" in template || "<think>" in template) ChatFormat.CHATML_NO_THINK else ChatFormat.CHATML
    "<|assistant|>" in template && "<|end|>" in template -> ChatFormat.PHI
    "[INST]" in template -> ChatFormat.MISTRAL
    else -> fallback
}
