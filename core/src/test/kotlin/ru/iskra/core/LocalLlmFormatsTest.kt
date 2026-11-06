package ru.iskra.core

import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.detect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Own GGUF files of the Llama 3, Mistral and Phi families get the dialogue written the way they were trained. */
class LocalLlmFormatsTest {
    private val request = LlmRequest(
        system = "Ты Искра.",
        messages = listOf(ChatMessage.User("Привет"), ChatMessage.Assistant("Мрр, привет!"), ChatMessage.User("Как дела?")),
        situation = "Сейчас 15:00.",
    )

    @Test fun formatsAreReadFromTheTemplate() {
        assertEquals(ChatFormat.LLAMA3, ChatFormat.detect("{{ '<|start_header_id|>' + message['role'] + '<|end_header_id|>' }}"))
        assertEquals(ChatFormat.MISTRAL, ChatFormat.detect("{{ '[INST] ' + message['content'] + ' [/INST]' }}"))
        assertEquals(ChatFormat.PHI, ChatFormat.detect("{{'<|' + message['role'] + '|>' + '\n' + message['content'] + '<|end|>'}}{{ '<|assistant|>' }}"))
        assertEquals(ChatFormat.GEMMA, ChatFormat.detect("<start_of_turn>user"))
        assertEquals(ChatFormat.CHATML, ChatFormat.detect("<|im_start|>user"))
        assertEquals(ChatFormat.GEMMA, ChatFormat.detect(null, ChatFormat.GEMMA))
    }

    @Test fun llama3() {
        val p = ChatFormat.LLAMA3.prompt(request)
        assertEquals("<|start_header_id|>system<|end_header_id|>\n\nТы Искра.<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\nПривет<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\nМрр, привет!<|eot_id|>" +
            "<|start_header_id|>system<|end_header_id|>\n\nСейчас 15:00.<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\nКак дела?<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n", p.text)
        // The cached part ends before the situation; the instructions are the head.
        assertTrue(p.text.substring(0, p.stable).endsWith("Мрр, привет!<|eot_id|>"))
        assertEquals("<|start_header_id|>system<|end_header_id|>\n\nТы Искра.<|eot_id|>", p.text.substring(0, p.head))
        assertTrue("<|eot_id|>" in ChatFormat.LLAMA3.stops)
    }

    @Test fun mistral() {
        val p = ChatFormat.MISTRAL.prompt(request)
        assertEquals("[INST] Ты Искра.\n\nПривет [/INST] Мрр, привет!</s>[INST] Сейчас 15:00.\n\nКак дела? [/INST]", p.text)
        assertTrue(p.text.substring(0, p.stable).endsWith("</s>[INST] "))
        assertTrue("</s>" in ChatFormat.MISTRAL.stops)
    }

    @Test fun phi() {
        val p = ChatFormat.PHI.prompt(request)
        assertEquals("<|system|>\nТы Искра.<|end|>\n<|user|>\nПривет<|end|>\n<|assistant|>\nМрр, привет!<|end|>\n" +
            "<|system|>\nСейчас 15:00.<|end|>\n<|user|>\nКак дела?<|end|>\n<|assistant|>\n", p.text)
        assertTrue("<|end|>" in ChatFormat.PHI.stops)
    }
}
