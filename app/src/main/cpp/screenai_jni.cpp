// Cầu nối JNI giữa ứng dụng và llama.cpp: nạp mô hình GGUF (kèm mmproj nếu mô hình đọc được ảnh),
// dựng prompt bằng chat template của chính mô hình, sinh văn bản và báo tiến độ.
//
// Mọi chuỗi đi qua JNI dưới dạng byte UTF-8 chuẩn (không dùng NewStringUTF vì JNI dùng
// "modified UTF-8" – hỏng ký tự ngoài BMP như emoji, chữ Hán hiếm).

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ScreenAI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ScreenAI", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, "[ScreenAI] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, "[ScreenAI][E] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#define JNI_FN(name) Java_com_dichmanhinh_screentranslator_ai_LlamaNative_##name

static std::mutex g_err_mutex;
static std::string g_last_error;

static void set_error(const std::string & e) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_error = e;
    LOGE("%s", e.c_str());
}

static jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (arr && !s.empty()) env->SetByteArrayRegion(arr, 0, (jsize) s.size(), (const jbyte *) s.data());
    return arr;
}

static std::string from_bytes(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    jsize n = env->GetArrayLength(arr);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, (jbyte *) s.data());
    return s;
}

static std::string from_jstring(JNIEnv * env, jstring js) {
    if (!js) return {};
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string s(c ? c : "");
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeLastError)(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    return to_bytes(env, g_last_error);
}

#ifdef SCREENAI_STUB
// ------------------------------------------------------------------ máy 32-bit: không hỗ trợ

extern "C" JNIEXPORT jboolean JNICALL JNI_FN(nativeSupported)(JNIEnv *, jobject) { return JNI_FALSE; }
extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeInit)(JNIEnv * env, jobject, jstring) {
    return to_bytes(env, "unsupported (32-bit)");
}
extern "C" JNIEXPORT jlong JNICALL JNI_FN(nativeLoad)(JNIEnv *, jobject, jstring, jstring, jint, jint, jint) {
    set_error("Máy 32-bit không hỗ trợ AI trên máy");
    return 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeInfo)(JNIEnv * env, jobject, jlong) { return to_bytes(env, ""); }
extern "C" JNIEXPORT jboolean JNICALL JNI_FN(nativeHasVision)(JNIEnv *, jobject, jlong) { return JNI_FALSE; }
extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeMediaMarker)(JNIEnv * env, jobject) { return to_bytes(env, "<__media__>"); }
extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGenerate)(
        JNIEnv *, jobject, jlong, jbyteArray, jbyteArray, jbyteArray, jint, jint, jint,
        jfloat, jint, jfloat, jfloat, jfloat, jobject) {
    return nullptr;
}
extern "C" JNIEXPORT jintArray JNICALL JNI_FN(nativeStats)(JNIEnv *, jobject, jlong) { return nullptr; }
extern "C" JNIEXPORT void JNICALL JNI_FN(nativeCancel)(JNIEnv *, jobject, jlong) {}
extern "C" JNIEXPORT void JNICALL JNI_FN(nativeFree)(JNIEnv *, jobject, jlong) {}

#else
// ------------------------------------------------------------------ bản đầy đủ

#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

struct Stats {
    int prompt_tokens = 0;
    int prompt_ms = 0;
    int gen_tokens = 0;
    int gen_ms = 0;
    int reused_tokens = 0;
};

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr tmpls;
    mtmd_context * mctx = nullptr;
    int n_batch = 512;
    std::atomic<bool> cancel{false};
    std::mutex run_mutex;
    // Token đang nằm trong KV cache (prompt + phần đã sinh) – để dùng lại phần đầu prompt giống
    // nhau giữa các lần gọi (system prompt, hướng dẫn) thay vì tính lại.
    std::vector<llama_token> cached;
    Stats stats;

    ~Engine() {
        if (mctx) mtmd_free(mctx);
        tmpls.reset();
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

bool abort_cb(void * data) {
    auto * e = static_cast<Engine *>(data);
    return e->cancel.load();
}

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level < GGML_LOG_LEVEL_WARN) return;
#ifdef __ANDROID__
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "llama", "%s", text);
#else
    fputs(text, stderr);
#endif
}

std::string token_piece(const llama_vocab * vocab, llama_token t, bool special) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, special);
    if (n < 0) {
        std::string s((size_t) -n, '\0');
        n = llama_token_to_piece(vocab, t, s.data(), (int32_t) s.size(), 0, special);
        if (n < 0) return {};
        s.resize((size_t) n);
        return s;
    }
    return std::string(buf, (size_t) n);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> out((size_t) n);
    int r = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, add_special, true);
    if (r < 0) return {};
    out.resize((size_t) r);
    return out;
}

/** Dựng prompt bằng chat template (Jinja) có sẵn trong file GGUF; tắt chế độ "suy nghĩ". */
std::string apply_template(Engine & e, const std::string & system, const std::string & user) {
    std::vector<common_chat_msg> msgs;
    if (!system.empty()) {
        common_chat_msg m;
        m.role = "system";
        m.content = system;
        msgs.push_back(m);
    }
    common_chat_msg u;
    u.role = "user";
    u.content = user;
    msgs.push_back(u);

    try {
        common_chat_templates_inputs in;
        in.messages = msgs;
        in.add_generation_prompt = true;
        in.use_jinja = true;
        in.enable_thinking = false;
        auto params = common_chat_templates_apply(e.tmpls.get(), in);
        return params.prompt.str();
    } catch (const std::exception & ex) {
        LOGE("jinja template failed: %s – thử template cũ", ex.what());
    }
    try {
        common_chat_templates_inputs in;
        in.messages = msgs;
        in.add_generation_prompt = true;
        in.use_jinja = false;
        auto params = common_chat_templates_apply(e.tmpls.get(), in);
        return params.prompt.str();
    } catch (const std::exception & ex) {
        LOGE("legacy template failed: %s – dùng văn bản thô", ex.what());
    }
    return system.empty() ? user + "\n" : system + "\n\n" + user + "\n";
}

/** Bỏ token BOS dạng chữ ở đầu prompt (sẽ được thêm lại khi token hóa). */
std::string strip_bos_text(Engine & e, const std::string & prompt) {
    llama_token bos = llama_vocab_bos(e.vocab);
    if (bos == LLAMA_TOKEN_NULL) return prompt;
    std::string b = token_piece(e.vocab, bos, true);
    if (!b.empty() && prompt.compare(0, b.size(), b) == 0) return prompt.substr(b.size());
    return prompt;
}

/** Đưa các token prompt vào mô hình, dùng lại phần đầu đã có trong KV cache nếu được. */
bool eval_text_prompt(Engine & e, const std::vector<llama_token> & toks) {
    llama_memory_t mem = llama_get_memory(e.ctx);
    size_t keep = 0;
    while (keep < e.cached.size() && keep < toks.size() && e.cached[keep] == toks[keep]) keep++;
    // luôn tính lại ít nhất token cuối để có logits
    if (keep >= toks.size()) keep = toks.size() - 1;
    if (keep > 0 && !llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) keep = 0;
    if (keep == 0) llama_memory_clear(mem, true);
    e.cached.resize(keep);
    e.stats.reused_tokens = (int) keep;

    for (size_t i = keep; i < toks.size(); i += (size_t) e.n_batch) {
        int n = (int) std::min((size_t) e.n_batch, toks.size() - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(toks.data() + i), n);
        int r = llama_decode(e.ctx, batch);
        if (r != 0) {
            llama_memory_clear(mem, true);
            e.cached.clear();
            set_error(r == 2 ? "cancelled" : "llama_decode failed (" + std::to_string(r) + ")");
            return false;
        }
        e.cached.insert(e.cached.end(), toks.begin() + (long) i, toks.begin() + (long) i + n);
    }
    return true;
}

bool eval_image_prompt(Engine & e, const std::string & prompt, const unsigned char * rgb, int w, int h,
                       llama_pos & n_past) {
    llama_memory_clear(llama_get_memory(e.ctx), true);
    e.cached.clear();
    e.stats.reused_tokens = 0;

    mtmd::bitmap_ptr bmp(mtmd_bitmap_init((uint32_t) w, (uint32_t) h, rgb));
    if (!bmp) { set_error("mtmd_bitmap_init failed"); return false; }
    mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
    mtmd_input_text txt;
    txt.text = prompt.c_str();
    txt.text_len = prompt.size();
    txt.add_special = true;
    txt.parse_special = true;
    const mtmd_bitmap * bitmaps[] = { bmp.get() };
    int32_t r = mtmd_tokenize(e.mctx, chunks.get(), &txt, bitmaps, 1);
    if (r != 0) { set_error("mtmd_tokenize failed (" + std::to_string(r) + ")"); return false; }
    e.stats.prompt_tokens = (int) mtmd_helper_get_n_tokens(chunks.get());
    if (e.stats.prompt_tokens >= (int) llama_n_ctx(e.ctx) - 8) {
        set_error("prompt too long: " + std::to_string(e.stats.prompt_tokens) + " tokens");
        return false;
    }
    llama_pos new_past = 0;
    r = mtmd_helper_eval_chunks(e.mctx, e.ctx, chunks.get(), 0, 0, e.n_batch, true, &new_past);
    if (r != 0) {
        llama_memory_clear(llama_get_memory(e.ctx), true);
        set_error(e.cancel.load() ? "cancelled" : "mtmd_helper_eval_chunks failed (" + std::to_string(r) + ")");
        return false;
    }
    n_past = new_past;
    return true;
}

llama_sampler * make_sampler(Engine & e, float temp, int top_k, float top_p, float min_p, float repeat) {
    auto * s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        if (repeat > 1.0f) {
            llama_sampler_chain_add(s, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(s, llama_sampler_init_penalties(llama_vocab_n_tokens(e.vocab), 64, repeat, 0.0f, 0.0f));
        }
        llama_sampler_chain_add(s, llama_sampler_init_greedy());
        return s;
    }
    if (top_k > 0) llama_sampler_chain_add(s, llama_sampler_init_top_k(top_k));
    if (repeat > 1.0f) {
        llama_sampler_chain_add(s, llama_sampler_init_penalties(llama_vocab_n_tokens(e.vocab), 64, repeat, 0.0f, 0.0f));
    }
    if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(s, llama_sampler_init_top_p(top_p, 1));
    if (min_p > 0.0f) llama_sampler_chain_add(s, llama_sampler_init_min_p(min_p, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s, llama_sampler_init_dist(1234));
    return s;
}

/** Độ dài phần đầu của [s] là chuỗi UTF-8 hoàn chỉnh (không cắt giữa một ký tự). */
size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();
    // tìm byte đầu của ký tự cuối cùng (lùi tối đa 3 byte nối tiếp 10xxxxxx)
    size_t i = n;
    for (int back = 0; i > 0 && back < 4; back++) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {
            size_t lead = i - 1;
            size_t len = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
            return (n - lead >= len) ? n : lead;
        }
        i--;
    }
    return n;
}

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL JNI_FN(nativeSupported)(JNIEnv *, jobject) { return JNI_TRUE; }

extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeInit)(JNIEnv * env, jobject, jstring jdir) {
    static std::once_flag once;
    std::string dir = from_jstring(env, jdir);
    std::call_once(once, [&]() {
        llama_log_set(log_cb, nullptr);
        mtmd_helper_log_set(log_cb, nullptr);
        if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str());
        else ggml_backend_load_all();
        llama_backend_init();
    });
    std::string info = llama_print_system_info();
    return to_bytes(env, info);
}

extern "C" JNIEXPORT jlong JNICALL JNI_FN(nativeLoad)(
        JNIEnv * env, jobject, jstring jmodel, jstring jmmproj, jint n_ctx, jint n_threads, jint image_max_tokens) {
    std::string model_path = from_jstring(env, jmodel);
    std::string mmproj_path = from_jstring(env, jmmproj);
    try {
        auto * e = new Engine();
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        e->model = llama_model_load_from_file(model_path.c_str(), mp);
        if (!e->model) {
            delete e;
            set_error("Không nạp được mô hình: " + model_path);
            return 0;
        }
        e->vocab = llama_model_get_vocab(e->model);

        int threads = n_threads > 0 ? n_threads : 4;
        llama_context_params cp = llama_context_default_params();
        int train = llama_model_n_ctx_train(e->model);
        cp.n_ctx = (uint32_t) (train > 0 && n_ctx > train ? train : n_ctx);
        cp.n_batch = (uint32_t) e->n_batch;
        cp.n_ubatch = (uint32_t) e->n_batch;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.no_perf = true;
        cp.abort_callback = abort_cb;
        cp.abort_callback_data = e;
        e->ctx = llama_init_from_model(e->model, cp);
        if (!e->ctx) {
            delete e;
            set_error("Không tạo được ngữ cảnh (thiếu bộ nhớ?)");
            return 0;
        }
        e->tmpls = common_chat_templates_init(e->model, "");

        if (!mmproj_path.empty()) {
            mtmd_context_params mcp = mtmd_context_params_default();
            mcp.use_gpu = false;
            mcp.n_threads = threads;
            mcp.print_timings = false;
            mcp.warmup = false;
            if (image_max_tokens > 0) mcp.image_max_tokens = image_max_tokens;
            e->mctx = mtmd_init_from_file(mmproj_path.c_str(), e->model, mcp);
            if (!e->mctx) LOGE("mmproj load failed: %s – chỉ dùng chữ", mmproj_path.c_str());
            else if (!mtmd_support_vision(e->mctx)) {
                mtmd_free(e->mctx);
                e->mctx = nullptr;
            }
        }
        LOGI("model loaded: ctx=%u threads=%d vision=%d", llama_n_ctx(e->ctx), threads, e->mctx ? 1 : 0);
        return (jlong) (intptr_t) e;
    } catch (const std::exception & ex) {
        set_error(std::string("load: ") + ex.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeInfo)(JNIEnv * env, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e) return to_bytes(env, "");
    char arch[128] = {0};
    llama_model_meta_val_str(e->model, "general.architecture", arch, sizeof(arch));
    char desc[256] = {0};
    llama_model_desc(e->model, desc, sizeof(desc));
    std::string s = std::string("arch=") + arch + ";desc=" + desc +
                    ";ctx=" + std::to_string(llama_n_ctx(e->ctx)) +
                    ";params=" + std::to_string(llama_model_n_params(e->model)) +
                    ";vision=" + (e->mctx ? "1" : "0");
    const char * tmpl = llama_model_chat_template(e->model, nullptr);
    std::string t = tmpl ? std::string(tmpl).substr(0, 48) : std::string("(none)");
    for (auto & c : t) if (c == '\n' || c == ';') c = ' ';
    s += ";template=" + t;
    return to_bytes(env, s);
}

extern "C" JNIEXPORT jboolean JNICALL JNI_FN(nativeHasVision)(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    return e && e->mctx ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeMediaMarker)(JNIEnv * env, jobject) {
    return to_bytes(env, mtmd_default_marker());
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGenerate)(
        JNIEnv * env, jobject, jlong h, jbyteArray jsystem, jbyteArray juser,
        jbyteArray jrgb, jint w, jint img_h, jint max_tokens,
        jfloat temp, jint top_k, jfloat top_p, jfloat min_p, jfloat repeat, jobject listener) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e) { set_error("engine not loaded"); return nullptr; }
    std::lock_guard<std::mutex> lock(e->run_mutex);
    e->cancel.store(false);
    e->stats = Stats();

    jmethodID on_progress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        on_progress = env->GetMethodID(cls, "onProgress", "(I)Z");
        env->DeleteLocalRef(cls);
        if (!on_progress) { env->ExceptionClear(); listener = nullptr; }
    }

    llama_sampler * smpl = nullptr;
    try {
        std::string system = from_bytes(env, jsystem);
        std::string user = from_bytes(env, juser);
        std::string prompt = strip_bos_text(*e, apply_template(*e, system, user));
#ifndef __ANDROID__
        // Kiểm thử trên máy tính: SCREENAI_DEBUG=<file> ghi prompt và kết quả vào file
        const char * dbg = getenv("SCREENAI_DEBUG");
        if (dbg && *dbg) {
            if (FILE * f = fopen(dbg, "a")) { fprintf(f, "=== PROMPT\n%s\n", prompt.c_str()); fclose(f); }
        }
#endif

        const int64_t t0 = now_ms();
        llama_pos n_past = 0;
        const bool with_image = jrgb && e->mctx && w > 0 && img_h > 0;
        if (with_image) {
            jsize n = env->GetArrayLength(jrgb);
            if ((long) n < (long) w * img_h * 3) { set_error("image buffer too small"); return nullptr; }
            std::vector<unsigned char> rgb((size_t) n);
            env->GetByteArrayRegion(jrgb, 0, n, (jbyte *) rgb.data());
            if (!eval_image_prompt(*e, prompt, rgb.data(), w, img_h, n_past)) return nullptr;
        } else {
            // Mô hình không đọc ảnh: bỏ dấu đánh dấu ảnh khỏi prompt
            const std::string marker = mtmd_default_marker();
            for (size_t p; (p = prompt.find(marker)) != std::string::npos;) prompt.erase(p, marker.size());
            auto toks = tokenize(e->vocab, prompt, true);
            if (toks.empty()) { set_error("tokenize failed"); return nullptr; }
            e->stats.prompt_tokens = (int) toks.size();
            if ((int) toks.size() >= (int) llama_n_ctx(e->ctx) - 8) {
                set_error("prompt too long: " + std::to_string(toks.size()) + " tokens");
                return nullptr;
            }
            if (!eval_text_prompt(*e, toks)) return nullptr;
            n_past = (llama_pos) toks.size();
        }
        const int64_t t1 = now_ms();
        e->stats.prompt_ms = (int) (t1 - t0);

        int budget = (int) llama_n_ctx(e->ctx) - (int) n_past - 4;
        int limit = max_tokens > 0 ? std::min((int) max_tokens, budget) : budget;
        smpl = make_sampler(*e, temp, top_k, top_p, min_p, repeat);

        std::string out;
        int n_gen = 0;
        bool stopped = false;
        while (n_gen < limit) {
            if (e->cancel.load()) { stopped = true; break; }
            llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
            if (llama_vocab_is_eog(e->vocab, tok)) break;
            out += token_piece(e->vocab, tok, false);
            n_gen++;
            llama_batch batch = llama_batch_get_one(&tok, 1);
            if (llama_decode(e->ctx, batch) != 0) {
                if (e->cancel.load()) stopped = true;
                break;
            }
            if (!with_image) e->cached.push_back(tok);
            if (listener && (n_gen % 8 == 0)) {
                jboolean go = env->CallBooleanMethod(listener, on_progress, (jint) n_gen);
                if (env->ExceptionCheck()) { env->ExceptionClear(); go = JNI_FALSE; }
                if (!go) { stopped = true; break; }
            }
        }
        llama_sampler_free(smpl);
        smpl = nullptr;
        e->stats.gen_tokens = n_gen;
        e->stats.gen_ms = (int) (now_ms() - t1);
        if (with_image) {
            // ảnh không nằm trong bộ nhớ đệm prompt chữ: lần sau tính lại từ đầu
            llama_memory_clear(llama_get_memory(e->ctx), true);
            e->cached.clear();
        }
        if (stopped && e->cancel.load()) { set_error("cancelled"); return nullptr; }
        out.resize(utf8_complete_prefix(out));
#ifndef __ANDROID__
        if (dbg && *dbg) {
            if (FILE * f = fopen(dbg, "a")) {
                fprintf(f, "=== OUTPUT (%d tok, %d ms)\n%s\n\n", n_gen, e->stats.gen_ms, out.c_str());
                fclose(f);
            }
        }
#endif
        return to_bytes(env, out);
    } catch (const std::exception & ex) {
        if (smpl) llama_sampler_free(smpl);
        llama_memory_clear(llama_get_memory(e->ctx), true);
        e->cached.clear();
        set_error(std::string("generate: ") + ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jintArray JNICALL JNI_FN(nativeStats)(JNIEnv * env, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e) return nullptr;
    jint v[5] = { e->stats.prompt_tokens, e->stats.prompt_ms, e->stats.gen_tokens, e->stats.gen_ms, e->stats.reused_tokens };
    jintArray arr = env->NewIntArray(5);
    env->SetIntArrayRegion(arr, 0, 5, v);
    return arr;
}

extern "C" JNIEXPORT void JNICALL JNI_FN(nativeCancel)(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    if (e) e->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL JNI_FN(nativeFree)(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) (intptr_t) h;
    if (!e) return;
    e->cancel.store(true);
    { std::lock_guard<std::mutex> lock(e->run_mutex); }
    delete e;
}

#endif // SCREENAI_STUB
