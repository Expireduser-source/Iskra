// Iskra: a small JNI bridge to llama.cpp for the model on the phone.
//
// One session = one loaded model + one context. The context keeps the keys/values of the last prompt and reply,
// so the next message only has to process what changed after the longest common prefix (the instructions and the
// earlier dialogue are not computed again). Text crosses the bridge as UTF-16 (Java) <-> UTF-8 (llama.cpp).
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_E(...) __android_log_print(ANDROID_LOG_ERROR, "IskraLlama", __VA_ARGS__)
#else
#define LOG_E(...) do { fprintf(stderr, "IskraLlama: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> cached;  // tokens held by the context: positions 0..n-1 of sequence 0
    std::atomic<bool> cancel{false};
    std::mutex busy;
    int n_ctx = 0;
    int n_batch = 256;
    int n_threads = 4;
};

std::once_flag g_init;

void init_backend() {
    std::call_once(g_init, [] {
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) LOG_E("%s", text);
        }, nullptr);
        llama_backend_init();
    });
}

std::string to_utf8(JNIEnv * env, jstring s) {
    const jsize len = env->GetStringLength(s);
    const jchar * ch = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (jsize i = 0; i < len; i++) {
        uint32_t c = ch[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len && ch[i + 1] >= 0xDC00 && ch[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (ch[i + 1] - 0xDC00);
            i++;
        }
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    env->ReleaseStringChars(s, ch);
    return out;
}

// NewString (UTF-16), not NewStringUTF: the latter wants "modified UTF-8" and rejects emoji in checked JNI.
jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::vector<jchar> u;
    u.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        const auto b = static_cast<unsigned char>(s[i]);
        uint32_t c;
        size_t n;
        if (b < 0x80) { c = b; n = 1; }
        else if ((b >> 5) == 0x6) { c = b & 0x1F; n = 2; }
        else if ((b >> 4) == 0xE) { c = b & 0x0F; n = 3; }
        else if ((b >> 3) == 0x1E) { c = b & 0x07; n = 4; }
        else { i++; continue; }
        if (i + n > s.size()) break;
        for (size_t k = 1; k < n; k++) c = (c << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        i += n;
        if (c >= 0x10000) {
            c -= 0x10000;
            u.push_back(static_cast<jchar>(0xD800 + (c >> 10)));
            u.push_back(static_cast<jchar>(0xDC00 + (c & 0x3FF)));
        } else {
            u.push_back(static_cast<jchar>(c));
        }
    }
    return env->NewString(u.data(), static_cast<jsize>(u.size()));
}

// How many bytes of s form whole UTF-8 characters (a token may end in the middle of a Cyrillic letter or emoji).
size_t complete_utf8(const std::string & s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        const auto b = static_cast<unsigned char>(s[n - back]);
        if ((b & 0xC0) == 0x80) continue;
        const size_t need = (b >> 5) == 0x6 ? 2 : (b >> 4) == 0xE ? 3 : (b >> 3) == 0x1E ? 4 : 1;
        return need > back ? n - back : n;
    }
    return n;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    // add_special: a BOS token for models that want one (Gemma); parse_special: "<|im_start|>" is one token.
    const int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    std::vector<llama_token> out(static_cast<size_t>(std::max(n, 0)));
    if (n > 0 && llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), out.data(), n, true, true) < 0) out.clear();
    return out;
}

std::string piece(const llama_vocab * vocab, llama_token t) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::string big(static_cast<size_t>(-n), '\0');
    const int m = llama_token_to_piece(vocab, t, big.data(), static_cast<int32_t>(big.size()), 0, false);
    return m > 0 ? big.substr(0, static_cast<size_t>(m)) : std::string();
}

bool decode(Session * s, const llama_token * toks, int n, int pos0, bool logits_last) {
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = toks[i];
        batch.pos[i] = pos0 + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (logits_last && i == n - 1) ? 1 : 0;
    }
    batch.n_tokens = n;
    const int rc = llama_decode(s->ctx, batch);
    llama_batch_free(batch);
    return rc == 0;
}

void forget_cache(Session * s) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ru_iskra_core_llm_local_LlamaNative_load(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    init_backend();
    const std::string path = to_utf8(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOG_E("cannot load the model");
        return 0;
    }
    auto * s = new Session();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(s->n_batch);
    cp.n_ubatch = static_cast<uint32_t>(s->n_batch);
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    cp.abort_callback = [](void * data) -> bool { return static_cast<Session *>(data)->cancel.load(); };
    cp.abort_callback_data = s;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOG_E("cannot create the context");
        llama_model_free(model);
        delete s;
        return 0;
    }
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    s->n_threads = n_threads;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_ru_iskra_core_llm_local_LlamaNative_generate(JNIEnv * env, jclass, jlong handle, jstring jprompt, jint max_tokens,
                                                 jfloat temp, jfloat top_p, jfloat min_p, jfloat repeat_penalty, jobject sink) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return to_jstring(env, "");
    std::lock_guard<std::mutex> guard(s->busy);
    s->cancel = false;

    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_cls, "onToken", "(Ljava/lang/String;)Z");
    if (on_token == nullptr) return nullptr;  // NoSuchMethodError is pending

    const std::vector<llama_token> toks = tokenize(s->vocab, to_utf8(env, jprompt));
    if (toks.empty()) return to_jstring(env, "");
    const int budget = std::max(16, static_cast<int>(max_tokens));
    if (static_cast<int>(toks.size()) + budget > s->n_ctx) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "prompt too long for the context");
        return nullptr;
    }

    // Keep the longest common prefix with what the context already holds; at least one token is decoded anew.
    size_t keep = 0;
    while (keep < s->cached.size() && keep < toks.size() && s->cached[keep] == toks[keep]) keep++;
    if (keep == toks.size()) keep--;
    if (!llama_memory_seq_rm(llama_get_memory(s->ctx), 0, static_cast<llama_pos>(keep), -1)) {
        forget_cache(s);
        keep = 0;
    }
    s->cached.resize(keep);

    for (size_t i = keep; i < toks.size(); i += static_cast<size_t>(s->n_batch)) {
        const int n = static_cast<int>(std::min(static_cast<size_t>(s->n_batch), toks.size() - i));
        const bool last = i + static_cast<size_t>(n) == toks.size();
        if (!decode(s, toks.data() + i, n, static_cast<int>(i), last)) {
            forget_cache(s);  // aborted or failed half-way: the cache may be partial
            return to_jstring(env, "");
        }
        s->cached.insert(s->cached.end(), toks.begin() + static_cast<long>(i), toks.begin() + static_cast<long>(i) + n);
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    std::string pending;
    for (int i = 0; i < budget && !s->cancel; i++) {
        const llama_token t = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, t)) break;
        pending += piece(s->vocab, t);
        const size_t whole = complete_utf8(pending);
        if (whole > 0) {
            const std::string chunk = pending.substr(0, whole);
            pending.erase(0, whole);
            out += chunk;
            jstring js = to_jstring(env, chunk);
            const jboolean go = env->CallBooleanMethod(sink, on_token, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) {
                llama_sampler_free(smpl);
                return nullptr;
            }
            if (!go) break;
        }
        if (static_cast<int>(s->cached.size()) + 1 >= s->n_ctx) break;
        if (!decode(s, &t, 1, static_cast<int>(s->cached.size()), true)) break;
        s->cached.push_back(t);
    }
    llama_sampler_free(smpl);
    return to_jstring(env, out);
}

JNIEXPORT void JNICALL
Java_ru_iskra_core_llm_local_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel = true;
}

JNIEXPORT void JNICALL
Java_ru_iskra_core_llm_local_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    s->cancel = true;
    { std::lock_guard<std::mutex> wait(s->busy); }  // let a running generation stop first
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT jstring JNICALL
Java_ru_iskra_core_llm_local_LlamaNative_info(JNIEnv * env, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return to_jstring(env, "");
    char desc[160] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    char buf[256];
    snprintf(buf, sizeof(buf), "%s · ctx %d · %d threads", desc, s->n_ctx, s->n_threads);
    return to_jstring(env, buf);
}

}  // extern "C"
