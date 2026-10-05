// Jarvis's bridge to llama.cpp: load a model file, write replies to prompts, free it. No logging of prompts or
// replies (llama.cpp's own log is silenced), no network, no files written.
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

namespace {
// The model, and one context kept between replies: the words a new prompt shares with an earlier one (the fixed
// instructions) are already worked through, so only the new words are read - the main cost on a phone's CPU.
// The context holds SLOTS separate sequences in one shared memory (the app picks a slot per kind of prompt, see
// PromptSlots), so asking which command was meant and then chatting no longer throw each other's instructions away.
constexpr int SLOTS = 4;
struct Handle {
    llama_model *model;
    int threads;
    llama_context *ctx = nullptr;
    uint32_t n_ctx = 0;
    std::vector<llama_token> cached[SLOTS];   // what each slot holds now, in order
    uint64_t used[SLOTS] = {};                // when each slot was last used (for making room)
    uint64_t clock = 0;
};
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
    auto *hd = new Handle();
    hd->model = m;
    hd->threads = threads;
    return reinterpret_cast<jlong>(hd);
}

// The reply as UTF-8 bytes (Kotlin decodes them), or null when it failed or was cancelled. [slot]: which of the
// context's sequences this prompt reads into; [oneLine]: stop at the end of the first line that has words (the caller
// keeps only that line, so whatever the model would write after it is time spent for nothing).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_optionslab_app_ira_LlmNative_generate(JNIEnv *env, jobject, jlong h, jstring prompt, jint maxTokens, jint slot,
                                               jboolean oneLine) {
    auto *hd = reinterpret_cast<Handle *>(h);
    if (!hd) return nullptr;
    g_cancel = false;
    const int s = (slot >= 0 && slot < SLOTS) ? (int) slot : 0;
    const llama_vocab *vocab = llama_model_get_vocab(hd->model);
    const char *pc = env->GetStringUTFChars(prompt, nullptr);
    std::string text(pc);
    env->ReleaseStringUTFChars(prompt, pc);

    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (n <= 0) return nullptr;
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, true, true) < 0) return nullptr;

    // A context big enough for this prompt and reply (made again, empty, only when a longer one is needed).
    uint32_t need = (uint32_t) (n + maxTokens + 16);
    if (!hd->ctx || hd->n_ctx < need) {
        if (hd->ctx) llama_free(hd->ctx);
        uint32_t size = need < 2048 ? 2048 : need;
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = size;
        cp.n_batch = size;
        cp.n_seq_max = SLOTS;
        cp.kv_unified = true;     // one memory of n_ctx shared by the slots (not n_ctx / SLOTS each)
        cp.n_threads = hd->threads;
        cp.n_threads_batch = hd->threads;
        cp.no_perf = true;
        hd->ctx = llama_init_from_model(hd->model, cp);
        hd->n_ctx = hd->ctx ? size : 0;
        for (auto &c : hd->cached) c.clear();
        if (!hd->ctx) return nullptr;
    }
    llama_context *ctx = hd->ctx;
    llama_memory_t mem = llama_get_memory(ctx);
    std::vector<llama_token> &cached = hd->cached[s];
    hd->used[s] = ++hd->clock;

    // The slot's shared beginning is kept; everything after it is dropped and read again (at least one token is always
    // read, so the reply starts from this prompt's own last word).
    size_t keep = 0;
    while (keep < cached.size() && keep < toks.size() && cached[keep] == toks[keep]) keep++;
    if (keep >= toks.size()) keep = toks.size() - 1;
    if (!llama_memory_seq_rm(mem, s, (llama_pos) keep, -1)) { llama_memory_seq_rm(mem, s, -1, -1); keep = 0; }
    cached.resize(keep);

    // Room for the new words and the reply: the other slots unused longest leave first.
    for (;;) {
        size_t held = 0;
        for (int i = 0; i < SLOTS; i++) held += hd->cached[i].size();
        if (held + (toks.size() - keep) + (size_t) maxTokens + 16 <= hd->n_ctx) break;
        int old = -1;
        for (int i = 0; i < SLOTS; i++)
            if (i != s && !hd->cached[i].empty() && (old < 0 || hd->used[i] < hd->used[old])) old = i;
        if (old < 0) break;
        llama_memory_seq_rm(mem, old, -1, -1);
        hd->cached[old].clear();
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());   // the most likely word each time: steady, repeatable

    // One batch, refilled for each step: the slot's tokens at their positions, logits only for the last one.
    const int32_t fresh = (int32_t) (toks.size() - keep);
    llama_batch batch = llama_batch_init(fresh, 0, 1);
    auto fill = [&](const llama_token *t, int32_t count) {
        batch.n_tokens = count;
        for (int32_t j = 0; j < count; j++) {
            batch.token[j] = t[j];
            batch.pos[j] = (llama_pos) (cached.size() + j);
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = s;
            batch.logits[j] = j == count - 1;
        }
    };

    std::string out;
    fill(toks.data() + keep, fresh);
    std::vector<llama_token> fed(toks.begin() + keep, toks.end());
    llama_token next = 0;
    bool ok = true;
    for (int i = 0; i < maxTokens && !g_cancel; i++) {
        if (llama_decode(ctx, batch) != 0) { ok = false; break; }
        cached.insert(cached.end(), fed.begin(), fed.end());
        next = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        out += piece(vocab, next);
        if (oneLine) {
            size_t words = out.find_first_not_of(" \t\r\n");
            size_t nl = words == std::string::npos ? std::string::npos : out.find('\n', words);
            if (nl != std::string::npos) { out.resize(nl); break; }
        }
        fed.assign(1, next);
        fill(&next, 1);
    }
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    // Anything uncertain (a failed step, a cancel): the context starts empty next time.
    if (!ok || g_cancel) { llama_memory_clear(mem, true); for (auto &c : hd->cached) c.clear(); }
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
    if (hd->ctx) llama_free(hd->ctx);
    llama_model_free(hd->model);
    delete hd;
}
