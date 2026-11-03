package ru.iskra.core.voice

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache of synthesized speech for short phrases ("Готово!", "Таймер на 5 минут поставлен",
 * greetings): played instantly, offline and without paying the voice service again.
 *
 * Key = engine + voice + model + emotion hint + text, hashed (file names reveal nothing).
 * Least recently used files are removed above [maxBytes]. Only Iskra's own lines are stored —
 * never the owner's voice.
 */
class PcmCache(private val dir: File, private val maxBytes: Long = 24L * 1024 * 1024) {

    fun key(engine: String, voice: String, model: String, emotion: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(listOf(engine, voice, model, emotion, normalize(text)).joinToString("\u0001").toByteArray(Charsets.UTF_8))
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(key: String): Pair<PcmFormat, ByteArray>? {
        val f = File(dir, "$key.pcm")
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                require(inp.readInt() == MAGIC)
                val rate = inp.readInt(); val ch = inp.readInt(); val len = inp.readInt()
                require(rate in 8_000..48_000 && ch in 1..2 && len in 0..MAX_CLIP)
                val data = ByteArray(len); inp.readFully(data)
                f.setLastModified(System.currentTimeMillis())
                PcmFormat(rate, ch) to data
            }
        }.getOrElse { f.delete(); null }
    }

    @Synchronized
    fun put(key: String, format: PcmFormat, data: ByteArray, length: Int = data.size) {
        if (length <= 0 || length > MAX_CLIP) return
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC); out.writeInt(format.sampleRate); out.writeInt(format.channels); out.writeInt(length)
            out.write(data, 0, length)
        }
        if (!tmp.renameTo(File(dir, "$key.pcm"))) tmp.delete()
        evict()
    }

    @Synchronized
    fun sizeBytes(): Long = files().sumOf { it.length() }

    @Synchronized
    fun count(): Int = files().size

    @Synchronized
    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun files() = dir.listFiles { f -> f.name.endsWith(".pcm") }.orEmpty().toList()

    private fun evict() {
        var total = files().sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length(); f.delete()
        }
    }

    companion object {
        private const val MAGIC = 0x49534b31 // "ISK1"
        /** ~12 s of 24 kHz mono 16-bit: longer clips are not worth keeping. */
        const val MAX_CLIP = 600_000

        /** Short phrases repeat; long ones almost never do. */
        fun cacheable(text: String) = text.length in 1..140

        fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
