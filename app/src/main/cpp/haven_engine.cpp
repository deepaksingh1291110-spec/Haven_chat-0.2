// haven_engine.cpp - llama.cpp engine for Haven Chat (CPU only).
//
// Main model (Gemma): load one GGUF model, chat with it (streaming), choose CPU threads.
// Search models (phases 2b and 2c): bge-m3 embeddings and the reranker, loaded one at a
// time and only while the main model is unloaded (RAM).
//
// All functions are called from ONE background thread in Kotlin, except
// stop(), which only sets a flag.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "common.h"
#include "chat.h"

#define TAG "haven-engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// State (one model at a time)
// ---------------------------------------------------------------------------
static llama_model *                g_model = nullptr;
static llama_context *              g_ctx   = nullptr;
static llama_sampler *              g_smpl  = nullptr;
static common_chat_templates_ptr    g_tmpls;
static std::vector<common_chat_msg> g_msgs;    // the conversation (system, user, assistant, ...)
static std::vector<llama_token>     g_cached;  // exactly the tokens now stored in the KV cache
static std::atomic<bool>            g_stop{false};
static std::vector<common_chat_tool> g_tools;   // tools Gemma may call
static int                          g_call_counter = 0;

// What the last generate() produced.
struct LastResult {
    std::string                        content;      // the answer text
    std::string                        reasoning;    // the thinking text
    std::vector<common_chat_tool_call> calls;        // tools Gemma wants to call
};
static LastResult g_last;
static int                          g_n_ctx = 0;

// Last warnings/errors from llama.cpp, so the app can show them on screen.
static std::mutex       g_log_mu;
static std::string      g_log_buf;
static ggml_log_level   g_last_level = GGML_LOG_LEVEL_INFO;

static void log_cb(ggml_log_level level, const char * text, void *) {
    if (!text) { return; }
    if (level != GGML_LOG_LEVEL_CONT) { g_last_level = level; }

    int prio = ANDROID_LOG_INFO;
    if (g_last_level == GGML_LOG_LEVEL_ERROR) { prio = ANDROID_LOG_ERROR; }
    else if (g_last_level == GGML_LOG_LEVEL_WARN) { prio = ANDROID_LOG_WARN; }
    else if (g_last_level == GGML_LOG_LEVEL_DEBUG) { prio = ANDROID_LOG_DEBUG; }
    __android_log_print(prio, TAG, "%s", text);

    if (g_last_level == GGML_LOG_LEVEL_ERROR || g_last_level == GGML_LOG_LEVEL_WARN) {
        std::lock_guard<std::mutex> lock(g_log_mu);
        g_log_buf += text;
        if (g_log_buf.size() > 6000) {
            g_log_buf.erase(0, g_log_buf.size() - 6000);
        }
    }
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------
static std::string jstring_to_std(JNIEnv * env, jstring s) {
    if (!s) { return ""; }
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) { env->ReleaseStringUTFChars(s, c); }
    return r;
}

// Prompts come from Kotlin as real UTF-8 bytes (JNI strings would break emoji).
static std::string bytes_to_std(JNIEnv * env, jbyteArray arr) {
    if (!arr) { return ""; }
    const jsize n = env->GetArrayLength(arr);
    std::string r((size_t) n, '\0');
    if (n > 0) {
        env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&r[0]));
    }
    return r;
}

// Only used for short status/error texts: non-ASCII bytes become '?'.
static jstring ascii_jstring(JNIEnv * env, const std::string & s) {
    std::string t = s;
    for (auto & c : t) {
        if ((unsigned char) c >= 0x80) { c = '?'; }
    }
    return env->NewStringUTF(t.c_str());
}

// Length of the leading part of s that contains only complete UTF-8 characters.
static size_t utf8_complete_len(const std::string & s) {
    const size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        i--;
        back++;
        const unsigned char c = (unsigned char) s[i];
        if ((c & 0xC0) != 0x80) {   // ASCII or a lead byte
            int need = 1;
            if (c >= 0xF0)      { need = 4; }
            else if (c >= 0xE0) { need = 3; }
            else if (c >= 0xC0) { need = 2; }
            return need > back ? i : n;
        }
    }
    return n;
}

static double seconds_since(const std::chrono::steady_clock::time_point & t0) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
}

// Gemma writes its thinking between two marker words:
//   <|channel>thought ... thinking text ... <channel|> final answer
// Only the final answer goes into the chat memory. If the model was stopped
// while it was still thinking, there is no answer yet and "" is returned.
static std::string final_answer_only(const std::string & raw) {
    static const std::string k_start = "<|channel>";
    static const std::string k_end   = "<channel|>";

    std::string out;
    const size_t end = raw.rfind(k_end);
    if (end != std::string::npos) {
        out = raw.substr(end + k_end.size());
    } else if (raw.find(k_start) != std::string::npos) {
        return "";
    } else {
        out = raw;
    }

    const char * ws = " \t\r\n";
    const size_t first = out.find_first_not_of(ws);
    if (first == std::string::npos) { return ""; }
    const size_t last = out.find_last_not_of(ws);
    return out.substr(first, last - first + 1);
}

static jbyteArray to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (!s.empty()) {
        env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

static std::string trim_ws(const std::string & s) {
    const char * ws = " \t\r\n";
    const size_t first = s.find_first_not_of(ws);
    if (first == std::string::npos) { return ""; }
    const size_t last = s.find_last_not_of(ws);
    return s.substr(first, last - first + 1);
}

static void reset_kv() {
    if (g_ctx) { llama_memory_clear(llama_get_memory(g_ctx), true); }
    g_cached.clear();
}

// Frees the model. The conversation (g_msgs) and the tools (g_tools) are KEPT, so Gemma can be
// unloaded for a heavy memory search and loaded again without losing the chat.
// (resetChat() starts a new conversation.)
static void free_all() {
    if (g_smpl)  { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    g_tmpls.reset();
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cached.clear();       // the KV cache is gone with the model
    g_last = LastResult();
    g_n_ctx = 0;
}

// Decodes tokens[from..] in chunks. g_cached must hold exactly tokens[0..from).
// Returns 0 = ok, 1 = stopped by the user, 2 = error.
static int decode_chunked(const std::vector<llama_token> & tokens, size_t from) {
    const size_t n_batch = std::max<size_t>(1, llama_n_batch(g_ctx));
    size_t i = from;
    while (i < tokens.size()) {
        if (g_stop) { return 1; }
        const size_t n = std::min(n_batch, tokens.size() - i);
        std::vector<llama_token> chunk(tokens.begin() + i, tokens.begin() + i + n);
        llama_batch batch = llama_batch_get_one(chunk.data(), (int32_t) n);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("decode failed at token %zu", i);
            reset_kv();
            return 2;
        }
        g_cached.insert(g_cached.end(), chunk.begin(), chunk.end());
        i += n;
    }
    return 0;
}

// ---------------------------------------------------------------------------
// Search models: bge-m3 embedder (kind 1) and reranker (kind 2)
// ---------------------------------------------------------------------------
static llama_model *   g_aux_model = nullptr;
static llama_context * g_aux_ctx   = nullptr;
static int             g_aux_kind  = 0;   // 1 = embedder, 2 = reranker
static int             g_aux_n_ctx = 0;

static void free_aux() {
    if (g_aux_ctx)   { llama_free(g_aux_ctx); g_aux_ctx = nullptr; }
    if (g_aux_model) { llama_model_free(g_aux_model); g_aux_model = nullptr; }
    g_aux_kind  = 0;
    g_aux_n_ctx = 0;
}

// Runs the search model over one token sequence (all tokens in sequence 0).
// Returns the pooled result of sequence 0 (embedding vector, or the rank score),
// or nullptr on error.
static const float * aux_run(const std::vector<llama_token> & tokens) {
    if (!g_aux_ctx || tokens.empty()) { return nullptr; }

    llama_memory_t mem = llama_get_memory(g_aux_ctx);
    if (mem) { llama_memory_clear(mem, true); }

    const int32_t n = (int32_t) tokens.size();
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int32_t i = 0; i < n; i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = true;
    }
    batch.n_tokens = n;
    const int rc = llama_decode(g_aux_ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        LOGE("search model decode failed: %d (%d tokens)", rc, (int) n);
        return nullptr;
    }
    return llama_get_embeddings_seq(g_aux_ctx, 0);
}

static std::vector<llama_token> embed_tokens(const std::string & text) {
    const llama_vocab * vocab = llama_model_get_vocab(g_aux_model);
    std::vector<llama_token> t = common_tokenize(vocab, text, /*add_special*/ true, /*parse_special*/ false);
    if ((int) t.size() > g_aux_n_ctx && g_aux_n_ctx > 1) {
        const llama_token last = t.back();     // keep the closing token
        t.resize((size_t) g_aux_n_ctx);
        t.back() = last;
    }
    return t;
}

// Same token layout as llama-server uses for reranking:
//   [BOS] query [EOS] [SEP] document [EOS]   (or the model's own "rerank" template)
static std::vector<llama_token> rerank_tokens(const std::string & query, const std::string & doc) {
    const llama_vocab * vocab = llama_model_get_vocab(g_aux_model);
    std::vector<llama_token> out;

    const char * tmpl = llama_model_chat_template(g_aux_model, "rerank");
    if (tmpl != nullptr) {
        const std::string t = tmpl;
        std::string prompt;
        for (size_t pos = 0; pos < t.size();) {
            if (t.compare(pos, 7, "{query}") == 0) {
                prompt += query;
                pos += 7;
            } else if (t.compare(pos, 10, "{document}") == 0) {
                prompt += doc;
                pos += 10;
            } else {
                prompt += t[pos++];
            }
        }
        out = common_tokenize(vocab, prompt, /*add_special*/ false, /*parse_special*/ true);
        if ((int) out.size() > g_aux_n_ctx) { out.resize((size_t) g_aux_n_ctx); }
        return out;
    }

    std::vector<llama_token> q = common_tokenize(vocab, query, false, false);
    std::vector<llama_token> d = common_tokenize(vocab, doc,   false, false);

    llama_token eos = llama_vocab_eos(vocab);
    if (eos == LLAMA_TOKEN_NULL) { eos = llama_vocab_sep(vocab); }
    const bool add_bos = llama_vocab_get_add_bos(vocab);
    const bool add_eos = llama_vocab_get_add_eos(vocab);
    const bool add_sep = llama_vocab_get_add_sep(vocab);

    // Make everything fit into the context: shorten the document first, then the query.
    const size_t extra = (add_bos ? 1 : 0) + (add_eos ? 2 : 0) + (add_sep ? 1 : 0);
    const size_t room  = (size_t) g_aux_n_ctx > extra + 2 ? (size_t) g_aux_n_ctx - extra : 2;
    if (q.size() + d.size() > room) {
        if (q.size() > room / 2) { q.resize(std::max<size_t>(1, room / 2)); }
        d.resize(room - q.size());
    }

    if (add_bos) { out.push_back(llama_vocab_bos(vocab)); }
    out.insert(out.end(), q.begin(), q.end());
    if (add_eos) { out.push_back(eos); }
    if (add_sep) { out.push_back(llama_vocab_sep(vocab)); }
    out.insert(out.end(), d.begin(), d.end());
    if (add_eos) { out.push_back(eos); }
    return out;
}

// ---------------------------------------------------------------------------
// JNI: class com.haven.chat.LlamaBridge
// ---------------------------------------------------------------------------
extern "C" {

// Call once at app start. Loads the CPU backends from the app's native lib folder.
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_init(JNIEnv * env, jobject, jstring native_lib_dir) {
    llama_log_set(log_cb, nullptr);
    const std::string dir = jstring_to_std(env, native_lib_dir);
    LOGI("loading backends from %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

// Returns 0 = ok, 1 = model file failed to load, 2 = context failed to create.
JNIEXPORT jint JNICALL
Java_com_haven_chat_LlamaBridge_loadModel(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    free_all();
    {
        std::lock_guard<std::mutex> lock(g_log_mu);
        g_log_buf.clear();
    }
    const std::string path = jstring_to_std(env, jpath);
    const int threads = std::max(1, std::min(16, (int) n_threads));

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU only
    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) { return 1; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        return 2;
    }
    g_n_ctx = (int) llama_n_ctx(g_ctx);

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g_tmpls = common_chat_templates_init(g_model, "");
    g_cached.clear();
    LOGI("model loaded, n_ctx = %d, threads = %d", g_n_ctx, threads);
    return 0;
}

// Change the CPU thread count without reloading the model.
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_setThreads(JNIEnv *, jobject, jint n_threads) {
    if (!g_ctx) { return; }
    const int threads = std::max(1, std::min(16, (int) n_threads));
    llama_set_n_threads(g_ctx, threads, threads);
    LOGI("threads set to %d", threads);
}

JNIEXPORT jstring JNICALL
Java_com_haven_chat_LlamaBridge_systemInfo(JNIEnv * env, jobject) {
    return ascii_jstring(env, llama_print_system_info());
}

// Start a new conversation with the given system prompt (UTF-8 bytes, may be empty).
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_resetChat(JNIEnv * env, jobject, jbyteArray jsystem) {
    g_msgs.clear();
    reset_kv();
    const std::string system = bytes_to_std(env, jsystem);
    if (!system.empty()) {
        common_chat_msg m;
        m.role    = "system";
        m.content = system;
        g_msgs.push_back(m);
    }
}

// ---------------------------------------------------------------------------
// Tools (Gemma decides when to call them; the app runs them)
// ---------------------------------------------------------------------------

// Removes all tools. Without tools Gemma just chats.
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_clearTools(JNIEnv *, jobject) {
    g_tools.clear();
}

// Adds one tool. parametersJson is a JSON schema, for example
// {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_addTool(JNIEnv * env, jobject, jstring jname,
                                        jbyteArray jdescription, jbyteArray jparameters) {
    common_chat_tool t;
    t.name        = jstring_to_std(env, jname);
    t.description = bytes_to_std(env, jdescription);
    t.parameters  = bytes_to_std(env, jparameters);
    if (t.parameters.empty()) { t.parameters = "{\"type\":\"object\",\"properties\":{}}"; }
    g_tools.push_back(t);
}

// Adds a message to the conversation without generating (to continue an old chat).
// role: "user" or "assistant".
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_appendMessage(JNIEnv * env, jobject, jstring jrole, jbyteArray jcontent) {
    common_chat_msg m;
    m.role    = jstring_to_std(env, jrole);
    m.content = bytes_to_std(env, jcontent);
    g_msgs.push_back(m);
}

// Adds the result of a tool call. The next generate("") call lets Gemma read it and answer.
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_addToolResult(JNIEnv * env, jobject, jstring jcall_id,
                                              jstring jname, jbyteArray jcontent) {
    common_chat_msg m;
    m.role         = "tool";
    m.tool_call_id = jstring_to_std(env, jcall_id);
    m.tool_name    = jstring_to_std(env, jname);
    m.content      = bytes_to_std(env, jcontent);
    g_msgs.push_back(m);
}

// What the last generate() produced (valid until the next generate()).
JNIEXPORT jbyteArray JNICALL
Java_com_haven_chat_LlamaBridge_lastContent(JNIEnv * env, jobject) {
    return to_jbytes(env, g_last.content);
}

JNIEXPORT jbyteArray JNICALL
Java_com_haven_chat_LlamaBridge_lastReasoning(JNIEnv * env, jobject) {
    return to_jbytes(env, g_last.reasoning);
}

JNIEXPORT jint JNICALL
Java_com_haven_chat_LlamaBridge_toolCallCount(JNIEnv *, jobject) {
    return (jint) g_last.calls.size();
}

JNIEXPORT jstring JNICALL
Java_com_haven_chat_LlamaBridge_toolCallName(JNIEnv * env, jobject, jint i) {
    if (i < 0 || (size_t) i >= g_last.calls.size()) { return env->NewStringUTF(""); }
    return ascii_jstring(env, g_last.calls[(size_t) i].name);
}

JNIEXPORT jstring JNICALL
Java_com_haven_chat_LlamaBridge_toolCallId(JNIEnv * env, jobject, jint i) {
    if (i < 0 || (size_t) i >= g_last.calls.size()) { return env->NewStringUTF(""); }
    return ascii_jstring(env, g_last.calls[(size_t) i].id);
}

// The arguments of a tool call as JSON text (UTF-8 bytes).
JNIEXPORT jbyteArray JNICALL
Java_com_haven_chat_LlamaBridge_toolCallArgs(JNIEnv * env, jobject, jint i) {
    if (i < 0 || (size_t) i >= g_last.calls.size()) { return to_jbytes(env, ""); }
    return to_jbytes(env, g_last.calls[(size_t) i].arguments);
}

// ---------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------

// Sends one user message and streams Gemma's reply through
//   listener.onDelta(kind, bytes)   kind 0 = answer text, kind 1 = thinking text
// If jtext is empty, no user message is added: Gemma continues after a tool result.
// Returns a one-line statistics text, or a text starting with "ERROR:".
// Afterwards lastContent(), lastReasoning() and the toolCall...() functions tell what came out.
JNIEXPORT jstring JNICALL
Java_com_haven_chat_LlamaBridge_generate(JNIEnv * env, jobject, jbyteArray jtext,
                                         jint max_new, jboolean thinking, jobject listener) {
    if (!g_ctx || !g_model || !g_smpl) {
        return env->NewStringUTF("ERROR: no model loaded");
    }
    g_stop = false;
    g_last = LastResult();

    jclass cls = env->GetObjectClass(listener);
    jmethodID on_delta = env->GetMethodID(cls, "onDelta", "(I[B)Z");
    if (!on_delta) {
        return env->NewStringUTF("ERROR: listener has no onDelta method");
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const size_t msgs_before = g_msgs.size();

    const std::string user_text = bytes_to_std(env, jtext);
    if (!user_text.empty()) {
        common_chat_msg user;
        user.role    = "user";
        user.content = user_text;
        g_msgs.push_back(user);
    }
    if (g_msgs.empty()) {
        return env->NewStringUTF("ERROR: nothing to answer");
    }

    // 1) Format the whole conversation (and the tools) with the model's own chat template.
    //    The session also knows how to read Gemma's answer: thinking, text and tool calls.
    std::unique_ptr<common_chat_session> session;
    std::vector<llama_token> tokens;
    try {
        common_chat_templates_inputs in;
        in.messages              = g_msgs;
        in.tools                 = g_tools;
        in.add_generation_prompt = true;
        in.use_jinja             = true;
        in.enable_thinking       = thinking;
        in.reasoning_format      = COMMON_REASONING_FORMAT_AUTO;
        in.add_bos               = llama_vocab_get_add_bos(vocab);
        in.add_eos               = llama_vocab_get_add_eos(vocab);
        session.reset(new common_chat_session(g_tmpls.get(), vocab, in));

        // Template text may contain special tokens; text from the user or from memories must not.
        bool first = true;
        for (const auto & part : session->prompt().parts) {
            std::vector<llama_token> t = common_tokenize(vocab, part.text, /*add_special*/ first,
                                                         /*parse_special*/ !part.is_input);
            tokens.insert(tokens.end(), t.begin(), t.end());
            first = false;
        }
    } catch (const std::exception & e) {
        g_msgs.resize(msgs_before);
        return ascii_jstring(env, std::string("ERROR: chat template failed: ") + e.what());
    }

    if (tokens.empty() || (int) tokens.size() + 16 >= g_n_ctx) {
        g_msgs.resize(msgs_before);
        char msg[200];
        snprintf(msg, sizeof(msg),
                 "ERROR: conversation too long (%d tokens, context is %d). Press New chat or choose a bigger context.",
                 (int) tokens.size(), g_n_ctx);
        return env->NewStringUTF(msg);
    }
    const int budget = std::min<int>(max_new, g_n_ctx - (int) tokens.size() - 1);

    // 2) Reuse the part of the KV cache that is identical to the new prompt.
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t n_keep = 0;
    while (n_keep < g_cached.size() && n_keep < tokens.size() && g_cached[n_keep] == tokens[n_keep]) {
        n_keep++;
    }
    if (n_keep >= tokens.size()) {
        n_keep = tokens.size() - 1;     // the last token must be decoded again to get logits
    }
    if (n_keep < g_cached.size()) {
        bool ok = n_keep > 0;
        if (ok) {
            // Sliding-window models drop old tokens; only roll back if the window is still intact.
            const int n_swa = llama_model_n_swa(g_model);
            if (n_swa > 0) {
                const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
                if (pos_min > std::max(0, (int) n_keep - n_swa)) { ok = false; }
            }
        }
        if (ok) { ok = llama_memory_seq_rm(mem, 0, (llama_pos) n_keep, -1); }
        if (ok) {
            g_cached.resize(n_keep);
        } else {
            reset_kv();                 // safe fallback: read the whole prompt again
            n_keep = 0;
        }
    }

    // 3) Read the new part of the prompt.
    const auto t_prefill = std::chrono::steady_clock::now();
    const int rc = decode_chunked(tokens, n_keep);
    const double prefill_s = seconds_since(t_prefill);
    const size_t n_new = tokens.size() - n_keep;
    if (rc == 1) {
        g_msgs.resize(msgs_before);
        return env->NewStringUTF("STOPPED while reading the prompt");
    }
    if (rc == 2) {
        g_msgs.resize(msgs_before);
        return env->NewStringUTF("ERROR: decoding the prompt failed (see log)");
    }

    // 4) Generate. Every piece goes to the session, which splits thinking / answer / tool calls.
    auto emit = [&](int kind, const std::string & text) -> bool {
        if (text.empty()) { return true; }
        jbyteArray arr = to_jbytes(env, text);
        const jboolean keep = env->CallBooleanMethod(listener, on_delta, (jint) kind, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep != 0;
    };

    common_chat_msg prev_msg;                 // the message as parsed so far
    auto emit_diffs = [&](const common_chat_msg & now) -> bool {
        bool keep = true;
        try {
            for (const auto & d : common_chat_msg_diff::compute_diffs(prev_msg, now)) {
                if (!emit(1, d.reasoning_content_delta)) { keep = false; }
                if (!emit(0, d.content_delta)) { keep = false; }
            }
        } catch (const std::exception & e) {
            LOGE("could not compute the text change: %s", e.what());
        }
        prev_msg = now;
        return keep;
    };

    common_chat_input chunk;                  // generated text not yet given to the session
    std::string raw;                          // everything generated (for stop texts and as a fallback)
    int n_gen = 0;
    bool failed = false;
    bool listener_stop = false;
    const auto t_gen = std::chrono::steady_clock::now();

    while (n_gen < budget && !g_stop && !listener_stop) {
        const llama_token id = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) { break; }

        const std::string piece = common_token_to_piece(vocab, id, true);
        raw += piece;
        chunk.append(piece, id);

        llama_token tok = id;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("decode failed while generating");
            reset_kv();
            failed = true;
            break;
        }
        g_cached.push_back(id);
        n_gen++;

        // Give the text to the session once it holds only complete UTF-8 characters.
        if (!chunk.empty() && utf8_complete_len(chunk.text) == chunk.text.size()) {
            try {
                const common_chat_msg now = session->feed(chunk);
                if (!emit_diffs(now)) { listener_stop = true; }
            } catch (const std::exception & e) {
                LOGE("parsing the answer failed: %s", e.what());
            }
            chunk = common_chat_input();
        }

        // Some templates define extra stop texts.
        for (const auto & stop : session->additional_stops()) {
            if (!stop.empty() && raw.size() >= stop.size() &&
                raw.compare(raw.size() - stop.size(), stop.size(), stop) == 0) {
                listener_stop = true;
            }
        }
    }
    const double gen_s = seconds_since(t_gen);
    const bool stopped = g_stop.load();

    // 5) The final message: thinking, answer text and tool calls.
    common_chat_msg final_msg;
    bool parsed = true;
    try {
        chunk.truncate(utf8_complete_len(chunk.text));     // drop a half character at the very end
        final_msg = session->finish(chunk);
        emit_diffs(final_msg);
    } catch (const std::exception & e) {
        LOGE("final parse failed: %s", e.what());
        parsed = false;
    }
    if (!parsed) {
        final_msg = common_chat_msg();
        final_msg.content = final_answer_only(raw);        // fallback: marker based split
    }

    g_last.content   = trim_ws(final_msg.content);
    g_last.reasoning = trim_ws(final_msg.reasoning_content);
    g_last.calls     = final_msg.tool_calls;
    for (auto & call : g_last.calls) {
        if (call.id.empty()) { call.id = "call_" + std::to_string(++g_call_counter); }
    }

    // 6) Remember the answer (without the thinking) in the conversation.
    if (!g_last.content.empty() || !g_last.calls.empty()) {
        common_chat_msg a;
        a.role       = "assistant";
        a.content    = g_last.content;
        a.tool_calls = g_last.calls;
        g_msgs.push_back(a);
    } else {
        g_msgs.resize(msgs_before);
    }

    if (failed) {
        return env->NewStringUTF("ERROR: decoding failed while generating (see log)");
    }
    char stats[256];
    snprintf(stats, sizeof(stats),
             "prompt %zu new tokens (%zu reused) %.1f t/s | answer %d tokens %.1f t/s%s",
             n_new, n_keep,
             prefill_s > 0.0 ? (double) n_new / prefill_s : 0.0,
             n_gen,
             gen_s > 0.0 ? (double) n_gen / gen_s : 0.0,
             stopped ? " | stopped" : "");
    return env->NewStringUTF(stats);
}

// Safe to call from any thread: asks the running generate() to finish.
JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_stop(JNIEnv *, jobject) {
    g_stop = true;
}

JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_unload(JNIEnv *, jobject) {
    free_all();
}

// Last warnings and errors printed by llama.cpp (ASCII only).
JNIEXPORT jstring JNICALL
Java_com_haven_chat_LlamaBridge_lastLog(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_log_mu);
    return ascii_jstring(env, g_log_buf);
}

// ---------------------------------------------------------------------------
// Search models (JNI)
// ---------------------------------------------------------------------------

// kind: 1 = bge-m3 embedder, 2 = reranker.
// Returns 0 = ok, 1 = model file failed to load, 2 = context failed to create,
// 3 = unknown kind, 4 = the main model is still loaded (unload it first: RAM).
JNIEXPORT jint JNICALL
Java_com_haven_chat_LlamaBridge_loadAux(JNIEnv * env, jobject, jstring jpath, jint kind, jint n_threads) {
    if (g_model || g_ctx) { return 4; }
    if (kind != 1 && kind != 2) { return 3; }
    free_aux();
    {
        std::lock_guard<std::mutex> lock(g_log_mu);
        g_log_buf.clear();
    }
    const std::string path = jstring_to_std(env, jpath);
    const int threads = std::max(1, std::min(16, (int) n_threads));

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU only
    g_aux_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_aux_model) { return 1; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = 2048;
    cparams.n_batch         = 2048;   // a whole text must fit into one batch
    cparams.n_ubatch        = 2048;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;
    cparams.embeddings      = true;
    if (kind == 2) { cparams.pooling_type = LLAMA_POOLING_TYPE_RANK; }   // like llama-server --reranking
    g_aux_ctx = llama_init_from_model(g_aux_model, cparams);
    if (!g_aux_ctx) {
        llama_model_free(g_aux_model);
        g_aux_model = nullptr;
        return 2;
    }
    g_aux_kind  = kind;
    g_aux_n_ctx = (int) llama_n_ctx(g_aux_ctx);
    LOGI("search model loaded: kind %d, n_ctx %d, threads %d", kind, g_aux_n_ctx, threads);
    return 0;
}

// One line of facts about the loaded search model (to check it is the right kind).
JNIEXPORT jstring JNICALL
Java_com_haven_chat_LlamaBridge_auxInfo(JNIEnv * env, jobject) {
    if (!g_aux_model || !g_aux_ctx) { return env->NewStringUTF("no search model loaded"); }
    char buf[200];
    snprintf(buf, sizeof(buf), "%s | vector size %d | pooling %d (2 = CLS, 4 = rank) | context %d",
             g_aux_kind == 1 ? "embedder" : "reranker",
             (int) llama_model_n_embd_out(g_aux_model),
             (int) llama_pooling_type(g_aux_ctx),
             g_aux_n_ctx);
    return env->NewStringUTF(buf);
}

// Embeds one text (UTF-8 bytes). Returns a normalized vector, or null on error.
JNIEXPORT jfloatArray JNICALL
Java_com_haven_chat_LlamaBridge_embed(JNIEnv * env, jobject, jbyteArray jtext) {
    if (!g_aux_model || !g_aux_ctx || g_aux_kind != 1) { return nullptr; }
    const std::vector<llama_token> tokens = embed_tokens(bytes_to_std(env, jtext));
    const float * e = aux_run(tokens);
    if (!e) { return nullptr; }

    const int n = (int) llama_model_n_embd_out(g_aux_model);
    double sum = 0.0;
    for (int i = 0; i < n; i++) { sum += (double) e[i] * (double) e[i]; }
    const float norm = sum > 0.0 ? (float) (1.0 / std::sqrt(sum)) : 1.0f;

    std::vector<float> v((size_t) n);
    for (int i = 0; i < n; i++) { v[(size_t) i] = e[i] * norm; }

    jfloatArray arr = env->NewFloatArray(n);
    env->SetFloatArrayRegion(arr, 0, n, v.data());
    return arr;
}

// Relevance score of one document for one query (both UTF-8 bytes). Higher = more relevant.
// Returns -1e6 on error.
JNIEXPORT jfloat JNICALL
Java_com_haven_chat_LlamaBridge_rerankScore(JNIEnv * env, jobject, jbyteArray jquery, jbyteArray jdoc) {
    if (!g_aux_model || !g_aux_ctx || g_aux_kind != 2) { return -1e6f; }
    const std::vector<llama_token> tokens = rerank_tokens(bytes_to_std(env, jquery), bytes_to_std(env, jdoc));
    const float * r = aux_run(tokens);
    if (!r) { return -1e6f; }
    return r[0];
}

JNIEXPORT void JNICALL
Java_com_haven_chat_LlamaBridge_unloadAux(JNIEnv *, jobject) {
    free_aux();
}

}  // extern "C"
