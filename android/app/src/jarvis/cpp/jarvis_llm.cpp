// JarvisAlgo's bridge to llama.cpp: load a model file, write a reply to one prompt, free it. No logging of prompts or
// replies (llama.cpp's own log is silenced), no network, no files written.
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct Handle { llama_model *model; int threads; };
std::atomic<bool> g_cancel{false};

void quiet(ggml_log_level, const char *, void *) {}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    return n > 0 ? std::string(buf, n) : std::string();
}

// Only whole UTF-8 characters reach Java (the last token may end inside one).
size_t utf8_complete(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;                          // a continuation byte: look further back
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
        return back >= need ? n : n - back;
    }
    return n;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_optionslab_app_ira_LlmNative_load(JNIEnv *env, jobject, jstring path, jint threads) {
    llama_log_set(quiet, nullptr);
    llama_backend_init();
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;
    llama_model *m = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!m) return 0;
    return reinterpret_cast<jlong>(new Handle{m, threads});
}

// The reply as UTF-8 bytes (Kotlin decodes them), or null when it failed or was cancelled.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_optionslab_app_ira_LlmNative_generate(JNIEnv *env, jobject, jlong h, jstring prompt, jint maxTokens) {
    auto *hd = reinterpret_cast<Handle *>(h);
    if (!hd) return nullptr;
    g_cancel = false;
    const llama_vocab *vocab = llama_model_get_vocab(hd->model);
    const char *pc = env->GetStringUTFChars(prompt, nullptr);
    std::string text(pc);
    env->ReleaseStringUTFChars(prompt, pc);

    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (n <= 0) return nullptr;
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, true, true) < 0) return nullptr;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) (n + maxTokens + 16);
    cp.n_batch = (uint32_t) n;
    cp.n_threads = hd->threads;
    cp.n_threads_batch = hd->threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(hd->model, cp);   // a fresh context per reply: nothing carries over
    if (!ctx) return nullptr;

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());   // the most likely word each time: steady, repeatable

    std::string out;
    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t) toks.size());
    llama_token next = 0;
    bool ok = true;
    for (int i = 0; i < maxTokens && !g_cancel; i++) {
        if (llama_decode(ctx, batch) != 0) { ok = false; break; }
        next = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        out += piece(vocab, next);
        batch = llama_batch_get_one(&next, 1);
    }
    llama_sampler_free(smpl);
    llama_free(ctx);
    if (!ok || g_cancel) return nullptr;
    out.resize(utf8_complete(out));
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    if (arr) env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_optionslab_app_ira_LlmNative_cancel(JNIEnv *, jobject) { g_cancel = true; }

extern "C" JNIEXPORT void JNICALL
Java_com_optionslab_app_ira_LlmNative_free(JNIEnv *, jobject, jlong h) {
    auto *hd = reinterpret_cast<Handle *>(h);
    if (!hd) return;
    llama_model_free(hd->model);
    delete hd;
}
