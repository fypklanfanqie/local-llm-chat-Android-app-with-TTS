// MNN 后端 Android JNI 桥接（移植自 MNN `apps/Android/MnnLlmChat` 的 llm_session + llm_mnn_jni）。
// 编译产物 libmnn_jni.so，由 MnnBridge 加载（依赖 c++_shared -> libMNN.so -> 本库）。
// CMake 见 cpp/CMakeLists.txt 的 mnn_jni target。
//
// 设计要点（stepping 算法，对齐 MnnLlmChat llm_session.cpp::ResponseWithHistory）：
//  1) 模型加载：`Llm::createLLM(config.json)` -> `set_config(文档对齐的运行期配置)` -> `load()`。
//     backend_type 取自 MnnBackend.MnnMode（cpu/opencl/qnn），qnn 需 libMNN.so 含 BUILD_QNN=ON
//     且运行时加载 libQnnHtp*.so，否则 load() 失败、nativeCreate 返回 0、BackendManager 回退。
//     set_config 走 config_.merge() 且在 load() 前 -> 覆盖模型自带 config.json 与任何旧值，于
//     load()->initRuntime()->setRuntimeHint() 生效。键值依据 MNN 官方文档
//     https://mnn-docs.readthedocs.io/en/latest/transformers/llm.html 「运行时配置」一节，详见
//     nativeCreate 内注释。
//  2) 聊天模板：MNN 按各模型自带 llm_config.json/tokenizer 应用（Qwen=ChatML，Llama/Gemma/Phi
//     各异），故 nativeGenerateStream 接收消息列表 JSON 而非预格式化 ChatML 串，调用
//     `Llm::response(ChatMessages, &os, "<eop>", 0)` 由 MNN 套模板后 prefill。
//  3) 流式 + 真·中断（stepping）：prefill 用 `response(history, &os, "<eop>", 0)`（max_new_tokens=0
//     仅 prefill，<eop> 为停止串），随后循环 `generate(1)` 逐 token decode。每步之间轮询
//     shouldAbort()——命中即 break，**1 个 token 内停止**（取代旧版"let-finish 跑完剩余 token"）。
//     MNN 把 response() 传入的 ostream 存入 LlmContext，generate(1) 继续向其写 token，故流式回调
//     不断。UTF-8 字符边界切分由 Utf8StreamProcessor 处理（CJK/emoji 残缺多字节序列）。
//  4) <eop> / EOS 检测：本 libMNN.so（对齐 MNN 源码 ArGeneration::generate）仅在命中 EOS token
//     时把 end_with("<eop>") 写入流（is_stop 分支），每步 generate(1) 末尾置 status=
//     MAX_TOKENS_FINISHED（无论是否 EOS）。故 <eop> 入流（pending_eop）即真·EOS -> finalizePendingEop
//     置 generate_text_end 退出循环；CHECK_LLM_RUNNING 不拦 MAX_TOKENS_FINISHED、is_stop 防 EOS 后再
//     decode，故无需复位 status。（MnnLlmChat 的 resolveAndroidSteppingEop 是为「每步吐 <eop>」的另一
//     预编译运行时写的怪癖处理，本构建无该怪癖，套用反而会清掉真·EOS 导致跑到 max_tokens。）
//  5) KV 缓存对齐（多轮前缀复用的关键）：正常结束时把 assistant 回写 history 后
//     `syncPromptCache(history)`——MNN 用此对齐内部 mCachedPromptText（含后处理后的回复），
//     下一轮 response(full_history) 命中前缀 -> reuse_kv 仅 prefill 新增 token。中断时
//     `eraseHistory(kv_before_decode, 0)` 回滚到 prefill 后状态，使下一轮前缀仍命中缓存
//     （否则被取消的半句会污染前缀，导致全量重 prefill）。
//  6) 采样参数：temperature/top_p/repetition_penalty 在 load() 前 set_config（采样器在 load 内
//     一次性构建）。nativeGenerateStream 内 best-effort 再 set_config 一次（部分构建按 response
//     重建采样器则生效，否则 no-op）。temperature 改值由 Kotlin BackendManager 纳入重载指纹触发重载。

#include <jni.h>
#include <string>
#include <vector>
#include <utility>
#include <ostream>
#include <streambuf>
#include <functional>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <cstdint>
#include <chrono>
#include <sstream>
#include <android/log.h>

#include "llm/llm.hpp"

#define MNN_JNI_TAG "MnnJni"
#define MNN_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  MNN_JNI_TAG, __VA_ARGS__)
#define MNN_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  MNN_JNI_TAG, __VA_ARGS__)
#define MNN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MNN_JNI_TAG, __VA_ARGS__)

// ===== 运行时溯源编译定义（由 CMakeLists 注入；守卫以防未注入时的裸编译）=====
// 与 Kotlin MnnBridge.MnnRuntimeInfo 对齐：abiVersion 不符即 JNI 契约不兼容。
#ifndef CHAT_MNN_COMMIT
#define CHAT_MNN_COMMIT "unknown"
#endif
#ifndef CHAT_MNN_JNI_ABI
#define CHAT_MNN_JNI_ABI 0
#endif
#ifndef CHAT_MNN_BUILD_ID
#define CHAT_MNN_BUILD_ID "unknown"
#endif

using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;
using MNN::Transformer::ChatMessage;
using MNN::Transformer::ChatMessages;

// ===== JNI 类/方法缓存（首次调用 ensure_jni_cache 取 MnnBridge 类与 nativeCallback/shouldAbort 方法 ID）=====
static jclass    g_bridge_cls       = nullptr;
static jmethodID g_callback_mid     = nullptr;
static jmethodID g_should_abort_mid = nullptr;

// 最近一次 nativeCreate 的加载失败原因（供 nativeGetLastError 回传 Kotlin，定位「所有后端加载失败」根因）。
// nativeCreate 由 MnnBackend.mnnMutex 串行（三类 MNN 后端共享），且 nativeGetLastError 紧随 nativeCreate
// 失败返回后立即调用，无并发覆盖。load 成功时清空。
static std::string g_last_load_error;

static void ensure_jni_cache(JNIEnv *env) {
    if (g_bridge_cls) return;
    jclass local = env->FindClass("com/chatbyyourside/llm/backend/MnnBridge");
    if (!local) { MNN_LOGE("FindClass MnnBridge 失败"); return; }
    g_bridge_cls = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    g_callback_mid = env->GetStaticMethodID(g_bridge_cls, "nativeCallback", "([BI)V");
    g_should_abort_mid = env->GetStaticMethodID(g_bridge_cls, "shouldAbort", "()Z");
    if (!g_callback_mid || !g_should_abort_mid) {
        MNN_LOGE("取 MnnBridge 方法 ID 失败 cb=%p abort=%p", g_callback_mid, g_should_abort_mid);
    }
}

// 把一段字节推给 Java（ByteArray，Kotlin 侧 String(bytes, UTF_8) 解码）并携带当前已生成 token 数。
// gen_len 为真实 token 数（LlmContext::gen_seq_len / stepping 步数），批处理后回调次数≠token 数，
// 供 Kotlin 浮窗实时 tps 用（MnnBackend 不再用回调次数冒充 token 数）。
static void push_bytes(JNIEnv *env, const char *data, int len, jint gen_len) {
    if (len <= 0 || !g_bridge_cls || !g_callback_mid) return;
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) return;
    env->SetByteArrayRegion(arr, 0, len, (const jbyte *)data);
    env->CallStaticVoidMethod(g_bridge_cls, g_callback_mid, arr, gen_len);
    env->DeleteLocalRef(arr);
    // Kotlin 回调若抛异常（如协程取消/上层断言），清掉 pending 异常避免后续 JNI 调用崩
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// 轮询 Kotlin 侧 MnnBridge.abort 静态标志（stepping 循环每步检测，实现真·中断）。
static bool should_abort(JNIEnv *env) {
    if (!g_bridge_cls || !g_should_abort_mid) return false;
    jboolean v = env->CallStaticBooleanMethod(g_bridge_cls, g_should_abort_mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return v == JNI_TRUE;
}

// ===== UTF-8 流式处理（移植自 MnnLlmChat utf8_stream_processor.hpp / llm_stream_buffer.hpp）=====

// streambuf：MNN response()/generate(1) 逐 token 写入 -> 回调 (char*, len)。
class LlmStreamBuffer : public std::streambuf {
public:
    using CallBack = std::function<void(const char *str, size_t len)>;
    explicit LlmStreamBuffer(CallBack callback) : callback_(std::move(callback)) {}
protected:
    std::streamsize xsputn(const char *s, std::streamsize n) override {
        if (callback_) callback_(s, (size_t)n);
        return n;
    }
    int_type overflow(int_type c) override {
        if (c != traits_type::eof() && callback_) {
            char ch = (char)c;
            callback_(&ch, 1);
        }
        return c;
    }
private:
    CallBack callback_ = nullptr;
};

// 缓冲字节，仅向下游吐出完整 UTF-8 字符（避免 CJK/emoji 多字节序列被截断）。
class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string &)> callback)
            : callback_(std::move(callback)) {}
    void processStream(const char *str, size_t len) {
        utf8Buffer_.append(str, len);
        size_t i = 0;
        std::string completeChars;
        while (i < utf8Buffer_.size()) {
            int length = utf8CharLength(static_cast<unsigned char>(utf8Buffer_[i]));
            if (length == 0 || i + length > utf8Buffer_.size()) break;
            completeChars.append(utf8Buffer_, i, length);
            i += length;
        }
        utf8Buffer_ = utf8Buffer_.substr(i);
        if (!completeChars.empty()) callback_(completeChars);
    }
    static int utf8CharLength(unsigned char byte) {
        if ((byte & 0x80) == 0) return 1;
        if ((byte & 0xE0) == 0xC0) return 2;
        if ((byte & 0xF0) == 0xE0) return 3;
        if ((byte & 0xF8) == 0xF0) return 4;
        return 0;
    }
private:
    std::string utf8Buffer_;
    std::function<void(const std::string &)> callback_;
};

// ===== 流式批处理（Task 4 Step 2）：缓冲完整 UTF-8 字符，按字节/时间阈值批量回调 =====
// 首个完整可见字符立即 flush（首 delta 即时性），随后按 maxBytes/maxMs 聚合，EOS/收尾时 flush()
// 清空缓冲不丢字。上游 Utf8StreamProcessor 已保证传入的是完整字符，此处绝不切分 UTF-8 边界。
// 批处理策略是"仅本次生成"的：阈值经 nativeGenerateStream 参数传入（Balanced 16ms/256B；
// Maximum Speed 24–32ms/512–1024B），与 load 配置分离。
class StreamBatcher {
public:
    using Callback = std::function<void(const std::string &)>;
    StreamBatcher(Callback cb, int maxBytes, int maxMs)
            : cb_(std::move(cb)),
              maxBytes_(maxBytes > 0 ? maxBytes : 256),
              maxMs_(maxMs > 0 ? maxMs : 16) {}

    // 追加一个完整 UTF-8 字符；首个字符立即推，其余按阈值聚合，达标时内部 flush。
    void add(const std::string &utf8Char) {
        if (!firstFlushed_) {
            flushString(utf8Char);
            firstFlushed_ = true;
            return;
        }
        if (buffer_.empty()) firstCharTime_ = now();
        buffer_ += utf8Char;
        if ((int)buffer_.size() >= maxBytes_ || elapsedMsSince(firstCharTime_) >= maxMs_) {
            flush();
        }
    }

    // 强制 flush 剩余缓冲（EOS / abort / max tokens / 错误收尾时调用，保证不丢字）。
    void flush() {
        if (buffer_.empty()) return;
        flushString(buffer_);
        buffer_.clear();
    }

    int callbackCount() const { return callbackCount_; }
    size_t pushedBytes() const { return pushedBytes_; }

private:
    Callback cb_;
    int maxBytes_;
    int maxMs_;
    std::string buffer_;
    std::chrono::steady_clock::time_point firstCharTime_{};
    bool firstFlushed_ = false;
    int callbackCount_ = 0;
    size_t pushedBytes_ = 0;

    void flushString(const std::string &s) {
        if (s.empty() || !cb_) return;
        cb_(s);
        callbackCount_++;
        pushedBytes_ += s.size();
    }

    static std::chrono::steady_clock::time_point now() {
        return std::chrono::steady_clock::now();
    }

    static long long elapsedMsSince(const std::chrono::steady_clock::time_point &t) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t).count();
    }
};

// ===== stepping 流状态（移植自 MnnLlmChat llm_session.cpp AndroidSteppingStreamState）=====
// processChunk 处理一个完整 UTF-8 字符：普通文本 -> full_text 累积 + StreamBatcher 批处理回调 + 每字符
// 轮询 abort；<eop> -> flush 缓冲后挂起。finalizePendingEop 收尾：置 generate_text_end（<eop> 由
// ArGeneration is_stop 分支写出，不推 UI、不入 full_text）。
struct AndroidSteppingStreamState {
    JNIEnv *env;
    StreamBatcher &batcher;
    bool &generate_text_end;
    bool &stop_requested;
    std::string &full_text;
    const char *result_log_tag;
    bool pending_eop = false;

    void processChunk(const std::string &utf8Char) {
        const bool is_eop = utf8Char.find("<eop>") != std::string::npos;
        if (is_eop) {
            batcher.flush();  // EOS 收尾前把已缓冲内容推给 UI（不丢字）
            pending_eop = true;
            return;
        }
        full_text.append(utf8Char);
        batcher.add(utf8Char);
        // 每字符轮询 shouldAbort（不受批处理缓冲影响）：取消/策略停止在 1 token 内响应，
        // 无需等下一次 flush。
        if (env) stop_requested = stop_requested || should_abort(env);
    }

    void finalizePendingEop() {
        if (!pending_eop) return;
        MNN_LOGI("%s (gen=%zu bytes)", result_log_tag, full_text.size());
        generate_text_end = true;
        pending_eop = false;
    }
};

// EOS 检测（本 libMNN.so 行为，对齐 MNN 源码 ArGeneration::generate）：
//  - generate(1) 解码 1 个 token；命中 EOS token 时才把 end_with("<eop>") 写入流（is_stop 分支），
//    并置 status=MAX_TOKENS_FINISHED（每步都置，无论是否 EOS）。故 pending_eop（<eop> 入流）即真·EOS。
//  - CHECK_LLM_RUNNING 不拦 MAX_TOKENS_FINISHED，generate(int) 内 is_stop(current_token) 又防 EOS 后
//    再 decode，故无需手动复位 status；循环只需在 pending_eop 命中时收尾即可。
//  （MnnLlmChat 的 resolveAndroidSteppingEop/restoreAndroidSteppingStatusIfNeeded 是为「每步吐 <eop>」
//   的另一预编译运行时写的怪癖处理；本构建无该怪癖，套用反而会清掉真·EOS 导致跑到 max_tokens。）

// ===== 消息列表 JSON 解析 =====
// schema: [{"role":"user","content":"..."},...]（MnnBridge.toMessagesJson 产出）
// 仅处理本 schema：扫描 "role"/"content" 键取其字符串值，按数组顺序构造 ChatMessages。
static std::string parse_json_string_at(const std::string &s, size_t &i) {
    // 入口 s[i] == '"'
    if (i < s.size() && s[i] == '"') i++;
    std::string out;
    while (i < s.size() && s[i] != '"') {
        char c = s[i];
        if (c == '\\' && i + 1 < s.size()) {
            char e = s[i + 1];
            switch (e) {
                case '"':  out.push_back('"');  break;
                case '\\': out.push_back('\\'); break;
                case '/':  out.push_back('/');  break;
                case 'n':  out.push_back('\n'); break;
                case 'r':  out.push_back('\r'); break;
                case 't':  out.push_back('\t'); break;
                case 'b':  out.push_back('\b'); break;
                case 'f':  out.push_back('\f'); break;
                case 'u': {
                    if (i + 5 < s.size()) {
                        char hex[5] = {s[i+2], s[i+3], s[i+4], s[i+5], 0};
                        unsigned int cp = (unsigned int)strtoul(hex, nullptr, 16);
                        if (cp < 0x80) {
                            out.push_back((char)cp);
                        } else if (cp < 0x800) {
                            out.push_back((char)(0xC0 | (cp >> 6)));
                            out.push_back((char)(0x80 | (cp & 0x3F)));
                        } else {
                            out.push_back((char)(0xE0 | (cp >> 12)));
                            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
                            out.push_back((char)(0x80 | (cp & 0x3F)));
                        }
                        i += 4;
                    }
                    break;
                }
                default: out.push_back(e); break;
            }
            i += 2;
        } else {
            out.push_back(c);
            i++;
        }
    }
    if (i < s.size()) i++;  // 跳过闭合引号
    return out;
}

static void parse_messages(const std::string &s, ChatMessages &out) {
    size_t i = 0;
    while (i < s.size()) {
        size_t role_pos = s.find("\"role\"", i);
        if (role_pos == std::string::npos) break;
        size_t p = role_pos + 6;
        while (p < s.size() && (s[p] == ' ' || s[p] == ':' || s[p] == '\t' ||
                                s[p] == '\n' || s[p] == '\r')) p++;
        if (p >= s.size() || s[p] != '"') { i = role_pos + 6; continue; }
        std::string role = parse_json_string_at(s, p);

        size_t content_pos = s.find("\"content\"", p);
        if (content_pos == std::string::npos) break;
        p = content_pos + 9;
        while (p < s.size() && (s[p] == ' ' || s[p] == ':' || s[p] == '\t' ||
                                s[p] == '\n' || s[p] == '\r')) p++;
        if (p >= s.size() || s[p] != '"') { i = content_pos + 9; continue; }
        std::string content = parse_json_string_at(s, p);

        out.emplace_back(role, content);
        i = p;
    }
}

// ===== 诊断：读取模型目录下的配置文件前若干字节打日志 =====
// config_path 指向模型目录里的 config.json。读它（及同目录 llm_config.json）前 2KB 打日志，
// 便于在 logcat 核对模型自带配置是否含激进键（attention_mode/dynamic_option 等）--本工程
// set_config 已显式钉安全值覆盖，此日志仅用于核对模型原始配置，定位疑难时用。
static void log_model_config(const std::string &config_path) {
    auto dump = [](const std::string &path, const char *tag) {
        std::ifstream f(path, std::ios::binary);
        if (!f.is_open()) { MNN_LOGI("%s: (读不到 %s)", tag, path.c_str()); return; }
        std::string head(2048, '\0');
        f.read(&head[0], head.size());
        head.resize(f.gcount());
        MNN_LOGI("%s (%zu bytes head): %s", tag, head.size(), head.c_str());
    };
    dump(config_path, "model config.json");
    size_t slash = config_path.find_last_of('/');
    if (slash != std::string::npos) {
        dump(config_path.substr(0, slash) + "/llm_config.json", "model llm_config.json");
    }
}

// ===== GenerationSummary JSON 助手（Task 4 Step 3：nativeGenerateStream 返回紧凑摘要而非全量文本）=====

// JSON 字符串转义（异常消息/诊断文本可能含引号/换行，防止破坏摘要 JSON）。
static std::string json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out.push_back(c); break;
        }
    }
    return out;
}

// 进入 generate 之前就失败（null handle / 消息解析失败）时的兜底摘要（BACKEND_FAILURE）。
static std::string build_failure_summary(const char *stage, const std::string &message) {
    std::ostringstream oss;
    oss << "{\"v\":1,\"completionReason\":\"BACKEND_FAILURE\",\"promptTokens\":0,"
        << "\"generatedTokens\":0,\"prefillUs\":0,\"decodeUs\":0,\"reuseKv\":-1,"
        << "\"callbackCount\":0,\"callbackBytes\":0,\"firstDeltaUs\":null,"
        << "\"errorStage\":\"" << stage << "\",\"errorMessage\":\""
        << json_escape(message) << "\"}";
    return oss.str();
}

// ===== JNI 导出 =====
// extern "C" 保证 JNI 名称查找不被 C++ name mangling 破坏（与 vulkan_jni.cpp 一致）。
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeCreate(
        JNIEnv *env, jobject thiz,
        jstring config_path, jstring backend_type,
        jint threads, jint context_len, jboolean lookahead,
        jfloat temperature, jfloat top_p, jfloat repeat_penalty) {

    ensure_jni_cache(env);

    // 构建标记：logcat 见此串即确认新 libmnn_jni.so 已部署（不见 = APK 仍带旧 .so，需重装）。
    // 用于排查"源码已改但症状依旧"--典型因 .cxx 缓存导致 .so 未真正重编（见 memory native-build-setup）。
    MNN_LOGI("mnn_jni build: batch-2026-08-09 (StreamBatcher 批处理 + 摘要返回; stepping prefill+generate(1)/<eop>/syncPromptCache/eraseHistory; jinja enable_thinking; mixed_samplers+penalty)");

    const char *cfg = env->GetStringUTFChars(config_path, nullptr);
    if (!cfg) return 0;
    std::string config_str(cfg);
    env->ReleaseStringUTFChars(config_path, cfg);

    const char *bt = env->GetStringUTFChars(backend_type, nullptr);
    std::string backend_str(bt ? bt : "cpu");
    if (bt) env->ReleaseStringUTFChars(backend_type, bt);

    MNN_LOGI("createLLM config=%s backend=%s threads=%d ctx=%d lookahead=%d temp=%.3f topP=%.3f rep=%.3f",
             config_str.c_str(), backend_str.c_str(), (int)threads, (int)context_len, (int)lookahead,
             (float)temperature, (float)top_p, (float)repeat_penalty);

    // 诊断：打模型自带 config.json / llm_config.json 内容，便于核对模型原始配置（本工程 set_config
    // 已显式覆盖激进键，此日志仅用于核对/定位疑难）。
    log_model_config(config_str);

    Llm *llm = Llm::createLLM(config_str);
    if (!llm) {
        g_last_load_error = "Llm::createLLM 返回 null（config.json 解析失败）";
        MNN_LOGE("%s", g_last_load_error.c_str());
        return 0;
    }

    // ===== set_config：按 MNN 官方文档「运行时配置」钉安全-快速默认（在 load() 前调用）=====
    // set_config 走 config_.merge()，**覆盖**模型自带 config.json 与任何旧值，于
    // load()->initRuntime()->setRuntimeHint() 生效。键值依据：
    //   https://mnn-docs.readthedocs.io/en/latest/transformers/llm.html#id「运行时配置」「配置项」
    //
    // cache_path：MNN 的 OpenCL/QNN 运行时把 tuned-kernel 缓存写到此文件。不设则默认相对
    //   `./mnn_cachefile.bin`，落在进程 CWD（Android 不可写）-> "Can't open file" + "Load Cache
    //   file error"，且回写缓存时可能崩在 PipelineModule::load（见 memory mnn-crash-cachepath）。
    //   放到模型目录内（config.json 所在目录，app 可写）消除该问题。
    std::string cache_path;
    size_t slash = config_str.find_last_of('/');
    if (slash != std::string::npos) {
        cache_path = config_str.substr(0, slash) + "/mnn_cachefile.bin";
    } else {
        cache_path = "mnn_cachefile.bin";
    }

    // thread_num：cpu/qnn 用用户值（Kotlin 侧已 min(用户, 大核数, 温度上限)，不超 4）。
    //   ⚠️ opencl 后端必须 68：文档「当选择opencl后端时，thread_num需设为68」--此值非线程数，
    //   而是 OpenCL buffer 存储 + tuning wide 模式编码。传用户值(4)会让 GPU 走错误模式，慢且异常。
    int thread_num;
    if (backend_str == "opencl") {
        thread_num = 68;
    } else {
        thread_num = threads > 0 ? (int)threads : 4;
    }

    // 通用键（所有后端设；GPU/NPU 对 CPU 专属键遵循默认/忽略，设了无害）：
    //  - precision="low"（文档默认，fp16/ARM82 路径，最快且安全）
    //  - memory="low"（文档默认，开启运行时量化）
    //  - use_mmap=true（文档「手机上建议设成true」，多 GB 权重按需 mmap，避免整权重读入 RAM 溢出）
    //  - reuse_kv=true（文档多轮对话复用 KV cache，第 2 轮起免重新 prefill 全历史，多轮提速）
    //  - attention_mode=8（文档「默认推荐」= FlashAttention + KV 不量化）。**显式钉 8 覆盖任何旧值/
    //    模型值**：旧版本曾设 10（flash + Q/K/V int8 KV 量化），对未带 int8 KV scale 的 taobao-mnn
    //    模型会损坏 KV 缓存 -> decode 退化为 "FFFF" 重复乱码。8 与 MNN 官方模型 config 默认一致，安全。
    //    （若个别模型仍乱码，把 8 改 0=关 FlashAttention 单行回退。）
    //  - dynamic_option=0（文档默认=不动态量化）。**显式钉 0 覆盖旧值**：旧版本曾设 2（激活 block
    //    动态 int8 量化），在不支持构建上走慢速回退 -> prefill 数分钟级（"运算很久才输出"即此）。
    //
    // 采样参数（temperature/topP/repetition_penalty）+ mixed_samplers 管线：
    //   MNN 采样器在 load() 内一次性构建（Sampler::Sampler -> configSampler -> buildPipeline），
    //   故必须 load() 前 set_config 才生效（nativeGenerateStream 里再 set 已来不及）。
    //   键名：topP/repetition_penalty 均生效--llmconfig.hpp 里 topP() 先查 top_p 再回退 topP，
    //   repetition_penalty() 同理，驼峰/snake_case 等价。temperature 由用户设置传入且可变 ->
    //   Kotlin BackendManager 纳入"重载指纹"，改值触发重载。topP/repeat_penalty 为常量。
    //
    //   ⚠️ 关键修复（复读循环根因）：sampler_type 默认 "mixed"，而 mixed_samplers 默认列表
    //   {"topK","tfs","typical","topP","min_p","temperature"} **不含 "penalty"**（见 sampler.hpp
    //   SamplerConfig::mixedSamplers）。buildPipeline() 只为列表内名字加 step，故默认情况下
    //   stepPenalty 根本不进管线 -> repetition_penalty 设了也是 no-op。小模型无重复惩罚时必陷入
    //   逐字复读循环（症状：复述 system prompt 角色卡身份段后无限循环，如"我是羽毛笔，本名拉菲艾拉…"）。
    //   这里显式把 "penalty" 加进 mixed_samplers 修复之：configMixed 会自动把 penalty 移到队首，
    //   在 topK/topP 等过滤前先施加重复惩罚（multiplicative logit /= rep，对正 logit）。
    //   末位保留 "temperature" -> select_type=temperature（随机采样，非 greedy）。
    std::string conf = "{\"backend_type\":\"" + backend_str +
                       "\",\"thread_num\":" + std::to_string(thread_num) +
                       ",\"cache_path\":\"" + cache_path + "\"" +
                       ",\"precision\":\"low\""
                       ",\"memory\":\"low\""
                       ",\"use_mmap\":true"
                       ",\"reuse_kv\":true"
                       ",\"attention_mode\":8"
                       ",\"dynamic_option\":0"
                       ",\"temperature\":" + std::to_string((double)temperature) +
                       ",\"topP\":" + std::to_string((double)top_p) +
                       ",\"repetition_penalty\":" + std::to_string((double)repeat_penalty) +
                       ",\"mixed_samplers\":[\"penalty\",\"topK\",\"tfs\",\"typical\",\"topP\",\"min_p\",\"temperature\"]";
    if (backend_str == "cpu") {
        // CPU 专属：power=high（BackendConfig Power_High，调度用大核）；kv_max_length 钉 context_len
        // 防 KV 无界增长（MNN 无 kvcache_limit 键，kv_max_length 为最接近的运行时键；不生效亦无害）。
        conf += ",\"power\":\"high\"";
        if (context_len > 0) {
            conf += ",\"kv_max_length\":" + std::to_string((int)context_len);
        }
        // Lookahead 投机解码（n-gram，无需 draft 模型）：用 prompt/历史 n-gram 预测若干 token，一次
        // 前向验证，命中即批量产出 -> CPU 上重复/代码类文本 1.5–3×。值见 llmconfig.hpp。
        // ⚠️ 默认关闭（见 Kotlin llmLookahead 设置项）：首轮无 n-gram 历史时 draft 全 miss，每步多跑
        // draft_predict_length 个前向却只产 1 token，在慢模型上反而数倍拖慢首条回复。多轮重复文本再开。
        // 须 load() 前设；改值需重载模型。
        if (lookahead) {
            conf += ",\"speculative_type\":\"lookahead\""
                    ",\"ngram_match_maxlen\":4"
                    ",\"draft_predict_length\":5";
        }
    }
    conf += "}";
    MNN_LOGI("set_config: %s", conf.c_str());
    if (!llm->set_config(conf)) {
        MNN_LOGW("set_config 失败（继续用模型默认）: %s", conf.c_str());
    }

    bool ok = false;
    std::string load_err;
    try {
        ok = llm->load();
    } catch (const std::exception &e) {
        load_err = std::string("Llm::load 异常: ") + e.what();
        MNN_LOGE("%s", load_err.c_str());
        ok = false;
    }
    // CPU 安全配置重试：激进配置 precision:"low"(fp16/ARM82) 与 memory:"low"(运行时量化) 在不支持该
    // 指令集/量化核的芯片上会导致 load() 失败。CPU 是 AUTO 回退链的末端兜底（恒在列），其失败即
    // 「所有后端均加载失败」-> 本地模型完全不可用。此处销毁失败实例后用「最小兼容配置」重建重试一次：
    //   丢 precision/memory（回退模型默认 fp32/无运行时量化，最兼容），
    //   保留 attention_mode=8/dynamic_option=0（防 KV int8 乱码/激活动态量化拖慢）、
    //       cache_path（防相对路径不可写崩在 PipelineModule::load）、
    //       mixed_samplers+penalty（防小模型复读循环）、use_mmap/reuse_kv（控内存/多轮复用）。
    // 仅 CPU 重试（GPU/NPU 失败本就回退 CPU；OpenCL/QNN 可用性问题换配置无益）。
    bool used_safe_retry = false;
    if (!ok && backend_str == "cpu") {
        MNN_LOGW("CPU 首次 load 失败，销毁后用安全配置(无 fp16/运行时量化)重建重试");
        Llm::destroy(llm);
        llm = Llm::createLLM(config_str);
        if (!llm) {
            g_last_load_error = "安全配置重试: Llm::createLLM 返回 null";
            MNN_LOGE("%s", g_last_load_error.c_str());
            return 0;
        }
        std::string safe_conf = "{\"backend_type\":\"cpu\""
            ",\"thread_num\":" + std::to_string(thread_num) +
            ",\"cache_path\":\"" + cache_path + "\"" +
            ",\"use_mmap\":true"
            ",\"reuse_kv\":true"
            ",\"attention_mode\":8"
            ",\"dynamic_option\":0"
            ",\"power\":\"high\""
            ",\"temperature\":" + std::to_string((double)temperature) +
            ",\"topP\":" + std::to_string((double)top_p) +
            ",\"repetition_penalty\":" + std::to_string((double)repeat_penalty) +
            ",\"mixed_samplers\":[\"penalty\",\"topK\",\"tfs\",\"typical\",\"topP\",\"min_p\",\"temperature\"]";
        if (context_len > 0) {
            safe_conf += ",\"kv_max_length\":" + std::to_string((int)context_len);
        }
        safe_conf += "}";
        MNN_LOGI("安全配置 set_config: %s", safe_conf.c_str());
        if (!llm->set_config(safe_conf)) {
            MNN_LOGW("安全配置 set_config 失败（继续用模型默认）");
        }
        try {
            ok = llm->load();
            if (ok) { load_err.clear(); used_safe_retry = true; }
        } catch (const std::exception &e) {
            load_err = std::string("安全配置 Llm::load 异常: ") + e.what();
            MNN_LOGE("%s", load_err.c_str());
            ok = false;
        }
    }
    if (!ok) {
        if (load_err.empty()) load_err = std::string("Llm::load() 失败 (backend=") + backend_str + ")";
        g_last_load_error = load_err;
        MNN_LOGE("%s", g_last_load_error.c_str());
        Llm::destroy(llm);
        return 0;
    }

    g_last_load_error.clear();
    MNN_LOGI("MNN 模型加载成功 backend=%s%s", backend_str.c_str(),
             (used_safe_retry ? " (经安全配置重试: 已回退 fp32/无运行时量化)" : ""));
    return (jlong)llm;
}

JNIEXPORT jstring JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGenerateStream(
        JNIEnv *env, jobject thiz,
        jlong handle, jstring messages_json,
        jint max_tokens, jfloat temperature,
        jfloat top_p, jfloat repeat_penalty,
        jboolean enable_thinking,
        jint batch_bytes, jint batch_ms) {

    Llm *llm = (Llm *)handle;
    if (!llm) return env->NewStringUTF(build_failure_summary("LOAD", "null handle").c_str());

    const char *jstr = env->GetStringUTFChars(messages_json, nullptr);
    if (!jstr) return env->NewStringUTF(build_failure_summary("LOAD", "messages_json 不可读").c_str());
    std::string json(jstr);
    env->ReleaseStringUTFChars(messages_json, jstr);

    ChatMessages history;
    parse_messages(json, history);
    if (history.empty()) {
        MNN_LOGE("消息列表解析为空，放弃推理");
        return env->NewStringUTF(build_failure_summary("LOAD", "消息列表解析为空").c_str());
    }

    // belt-and-suspenders：采样参数已在 nativeCreate 的 load() 前 set_config（保证生效，键名
    // topP/repetition_penalty 已修正）。此处按调用方参数再设一次 best-effort--若某些 MNN 构建
    // 按 response() 重建采样器则按此生效；若采样器确为 load() 一次性构建（本构建的行为），则此调用
    // 为 no-op，不影响正确性。键名同样用实测正确的 topP / repetition_penalty。
    char sample_conf[160];
    snprintf(sample_conf, sizeof(sample_conf),
             "{\"temperature\":%.4f,\"topP\":%.4f,\"repetition_penalty\":%.4f}",
             (double)temperature, (double)top_p, (double)repeat_penalty);
    llm->set_config(sample_conf);

    // 深度思考开关：经 jinja context 的 enable_thinking 控制（Qwen3 / DeepSeek-R1 等推理模型的 chat 模板据此
    // 决定是否在 generation prompt 末尾插入 <think> 前缀）。set_config -> setChatTemplate ->
    // set_chat_template_context 立即更新 tokenizer 上下文，下一次 apply_chat_template（下方 response 内）即生效，
    // **无需重载模型**。enable_thinking=false 时模板注入空 <think>\n\n</think> 前缀，模型跳过推理直接作答；
    // true 时插入 <think>\n，模型生成 reasoning 后 </think> 再作答。
    // 仅对含 enable_thinking 分支的 jinja 模板生效；Llama/Gemma 等无此分支的模板忽略该上下文，无害。
    // 出处：MNN dflash.cpp:84 set_config({"jinja":{"context":{"enable_thinking":false}}})；llm.cpp setChatTemplate。
    const char *think_conf = (enable_thinking == JNI_TRUE)
        ? "{\"jinja\":{\"context\":{\"enable_thinking\":true}}}"
        : "{\"jinja\":{\"context\":{\"enable_thinking\":false}}}";
    if (!llm->set_config(think_conf)) {
        MNN_LOGW("set_config enable_thinking 失败（继续用模型默认）: %s", think_conf);
    }

    int n = max_tokens > 0 ? (int)max_tokens : 2048;

    bool stop_requested = false;
    bool generate_text_end = false;
    std::string full_text;

    // 流式批处理（Task 4 Step 2）：首个完整可见字符立即 flush（首 delta 即时性），其余按
    // batch_bytes/batch_ms 聚合后再回调。策略是"仅本次生成"的，经参数由 Kotlin 传入
    // （Task 6 性能模式接入前先用 Balanced 默认 256B/16ms），与 load 配置分离。
    auto t_start = std::chrono::steady_clock::now();
    long long first_delta_us = -1;
    // 回调用真实已生成 token 数（供浮窗实时 tps）。批处理后回调次数≠token 数，不能再用回调计数。
    // LlmContext::gen_seq_len 在解码线程同步读（无并发），flush 时已含正在生成的 token，最准确；
    // 读失败回退 stepping 步数计数（gen_len_cb，每步 generate(1) 后更新）。
    int gen_len_cb = 0;
    StreamBatcher batcher(
        [&env, &t_start, &first_delta_us, llm, &gen_len_cb](const std::string &batch) {
            if (first_delta_us < 0) {
                first_delta_us = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - t_start).count();
            }
            int gen_len_now = gen_len_cb;
            try {
                const LlmContext *c = llm->getContext();
                if (c) gen_len_now = c->gen_seq_len;
            } catch (...) {}
            push_bytes(env, batch.data(), (int)batch.size(), gen_len_now);
        },
        (int)batch_bytes, (int)batch_ms);

    AndroidSteppingStreamState stream_state{
            env, batcher, generate_text_end, stop_requested, full_text,
            "nativeGenerateStream Result"
    };
    Utf8StreamProcessor processor([&stream_state](const std::string &ch) {
        stream_state.processChunk(ch);
    });
    LlmStreamBuffer stream_buffer([&processor](const char *s, size_t len) {
        processor.processStream(s, len);
    });
    std::ostream os(&stream_buffer);

    // 清掉上一轮可能残留的终态，确保 prefill 顺利进入（response()->generate_init 会再置 RUNNING，
    // 此处仅双保险）。本构建无「每步吐 <eop>」怪癖，不用 MnnLlmChat 的 resolveAndroidSteppingEop。
    {
        const LlmContext *ctx = llm->getContext();
        if (ctx && (ctx->status == LlmStatus::MAX_TOKENS_FINISHED ||
                    ctx->status == LlmStatus::NORMAL_FINISHED)) {
            const_cast<LlmContext *>(ctx)->status = LlmStatus::RUNNING;
        }
    }

    const char *error_stage = nullptr;
    std::string error_message;

    MNN_LOGI("stepping prefill: msgs=%zu max_tokens=%d", history.size(), n);
    // prefill only（max_new_tokens=0），<eop> 作停止串。MNN 按模型 chat 模板格式化 history，
    // 并与上一轮 mCachedPromptText 做前缀匹配 -> reuse_kv 仅 prefill 新增 token。ostream 存入
    // LlmContext，供后续 generate(1) 继续写入。
    try {
        llm->response(history, &os, "<eop>", 0);
    } catch (const std::exception &e) {
        MNN_LOGE("prefill response 异常: %s", e.what());
        if (env->ExceptionCheck()) env->ExceptionClear();
        error_stage = "PREFILL";
        error_message = json_escape(e.what());
    }

    // prefill 后、decode 前的 KV 长度：中断时据此回滚生成的 token，使下一轮前缀仍命中缓存。
    size_t kv_before_decode = 0;
    try { kv_before_decode = llm->getCurrentHistory(); } catch (...) {}

    int current_size = 0;
    // 逐 token decode：每步 generate(1) 后若 <eop> 入流（pending_eop）即真·EOS -> 收尾退出；
    // shouldAbort() 命中即 break，1 个 token 内停止，取代旧版 let-finish。
    while (!stop_requested && !generate_text_end && current_size < n) {
        try {
            llm->generate(1);
        } catch (const std::exception &e) {
            MNN_LOGE("generate(1) 异常: %s", e.what());
            if (env->ExceptionCheck()) env->ExceptionClear();
            error_stage = "DECODE";
            error_message = json_escape(e.what());
            break;
        }
        current_size++;
        gen_len_cb = current_size;  // 回调 gen_len 回退值（flush 首选 LlmContext::gen_seq_len）
        if (stream_state.pending_eop) {
            stream_state.finalizePendingEop();  // 真·EOS：<eop> 由 ArGeneration is_stop 分支写出
        }
    }
    stream_state.finalizePendingEop();  // 兜底（到 max_tokens 未遇 EOS，或循环被 break）
    batcher.flush();                    // 收尾 flush：abort / max_tokens / 错误路径均不丢已缓冲内容

    // KV 缓存对齐（多轮前缀复用关键）：
    //  - 正常结束：把 assistant 回复并入 history 后 syncPromptCache——MNN 据此对齐 mCachedPromptText
    //    （含本次回复），下一轮 response(full_history) 命中前缀 -> 仅 prefill 新 user。
    //  - 中断：eraseHistory(kv_before_decode, 0) 回滚到 prefill 后状态，避免被取消的半句污染前缀
    //    导致下一轮全量重 prefill。current_size==0（prefill 即被取消/未产 token）则无需回滚。
    //  - 策略截断（Kotlin onToken 返回 false -> abort）：走中断分支，显式失效 prompt cache，
    //    绝不 sync 被丢弃的生成后缀。
    if (!stop_requested) {
        if (!full_text.empty()) {
            history.emplace_back("assistant", full_text);
        }
        try {
            llm->syncPromptCache(history);
        } catch (const std::exception &e) {
            MNN_LOGW("syncPromptCache 异常（忽略）: %s", e.what());
        }
    } else if (current_size > 0) {
        try {
            llm->eraseHistory(kv_before_decode, 0);
        } catch (const std::exception &e) {
            MNN_LOGW("eraseHistory 异常（忽略）: %s", e.what());
        }
        MNN_LOGI("中断：已回滚 KV 至 prefill 后状态 kv_before=%zu generated=%d", kv_before_decode, current_size);
    }

    // 指标 + 摘要：prompt_len（本轮 prefill 的 token 数，应远小于完整历史=前缀复用生效）/gen_len/
    // reuse_kv（本轮是否命中前缀缓存）。多轮中若 prompt_len 始终≈完整历史且 reuse_kv=0，说明前缀
    // 复用未生效，需排查 syncPromptCache / 历史一致性。
    int prompt_len = 0, gen_len = 0, reuse = -1;
    long long prefill_us = 0, decode_us = 0;
    {
        const LlmContext *ctx = llm->getContext();
        if (ctx) {
            prompt_len = ctx->prompt_len;
            gen_len = ctx->gen_seq_len;
            prefill_us = (long long)ctx->prefill_us;
            decode_us = (long long)ctx->decode_us;
            try { reuse = llm->reuse_kv() ? 1 : 0; } catch (...) { reuse = -1; }
            MNN_LOGI("response done: prompt_len=%d gen_len=%d all_seq_len=%d reuse_kv=%d "
                     "prefill_us=%lld decode_us=%lld stopped=%d cb=%d/%zu first_delta_us=%lld",
                     prompt_len, gen_len, ctx->all_seq_len, reuse,
                     prefill_us, decode_us, (int)stop_requested,
                     batcher.callbackCount(), batcher.pushedBytes(), first_delta_us);
        }
    }

    // 完成原因（native best-effort；Kotlin 侧有更高优先级推导：策略截断/用户取消/后端失败）。
    const char *reason = "EOS";
    if (error_stage != nullptr) {
        reason = "BACKEND_FAILURE";
    } else if (stop_requested) {
        reason = "USER_CANCEL";  // Kotlin 侧区分 USER_CANCEL / POLICY_TRUNCATION（onToken 返回 false）
    } else if (!generate_text_end) {
        reason = "MAX_TOKENS";   // 循环因 current_size >= n 退出且未遇 EOS
    }

    // 紧凑版本化 GenerationSummary JSON（替代全量文本字节数组）。full_text 仅在 native 内部
    // 供 syncPromptCache 使用，不再整份拷贝回 Kotlin。
    std::ostringstream oss;
    oss << "{\"v\":1,\"completionReason\":\"" << reason << "\""
        << ",\"promptTokens\":" << prompt_len
        << ",\"generatedTokens\":" << gen_len
        << ",\"prefillUs\":" << prefill_us
        << ",\"decodeUs\":" << decode_us
        << ",\"reuseKv\":" << reuse
        << ",\"callbackCount\":" << batcher.callbackCount()
        << ",\"callbackBytes\":" << batcher.pushedBytes()
        << ",\"firstDeltaUs\":" << (first_delta_us >= 0 ? std::to_string(first_delta_us) : "null")
        << ",\"errorStage\":" << (error_stage ? ("\"" + std::string(error_stage) + "\"") : "null")
        << ",\"errorMessage\":" << (error_message.empty() ? "null" : ("\"" + error_message + "\""))
        << "}";
    MNN_LOGI("nativeGenerateStream summary: %s", oss.str().c_str());
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT void JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    // 真·中断标志在 Kotlin 侧 MnnBridge.abort（stepping 循环每步经 shouldAbort 轮询，1 token 内停）。
    // 此处仅日志，保留接口供未来扩展。
    (void)env; (void)thiz; (void)handle;
    MNN_LOGI("nativeStop（stepping：shouldAbort 已置位，循环将在 1 token 内退出）");
}

JNIEXPORT void JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeRelease(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    Llm *llm = (Llm *)handle;
    if (!llm) return;
    try {
        Llm::destroy(llm);
    } catch (const std::exception &e) {
        MNN_LOGE("Llm::destroy 异常（忽略）: %s", e.what());
    }
    MNN_LOGI("MNN 模型已释放");
}

JNIEXPORT jfloatArray JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGetMetrics(
        JNIEnv *env, jobject thiz, jlong handle) {
    // 返回 [tokensPerSecond, prefillUs, decodeUs, promptLen, genLen, reuseKv]
    // reuseKv: 最近一次 response 是否复用了 KV 前缀缓存（1=是/0=否/-1=取不到），供验证多轮前缀复用。
    float tps = 0.f, prefill_us = 0.f, decode_us = 0.f, prompt_len = 0.f, gen_len = 0.f, reuse_kv = 0.f;
    Llm *llm = (Llm *)handle;
    if (llm) {
        const LlmContext *ctx = llm->getContext();
        if (ctx) {
            prefill_us = (float)ctx->prefill_us;
            decode_us  = (float)ctx->decode_us;
            prompt_len = (float)ctx->prompt_len;
            gen_len    = (float)ctx->gen_seq_len;
            if (decode_us > 0.f && gen_len > 0.f) {
                tps = gen_len / (decode_us / 1e6f);
            }
        }
        try { reuse_kv = llm->reuse_kv() ? 1.f : 0.f; } catch (...) { reuse_kv = -1.f; }
    }
    float metrics[6] = {tps, prefill_us, decode_us, prompt_len, gen_len, reuse_kv};
    jfloatArray result = env->NewFloatArray(6);
    if (result) env->SetFloatArrayRegion(result, 0, 6, metrics);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGetLastError(
        JNIEnv *env, jobject thiz) {
    // 返回最近一次 nativeCreate 的加载失败原因（g_last_load_error）。空串=无错误/上次成功。
    // 供 MnnBackend.initialize 在 nativeCreate 返回 0 时取真实原因填 lastErrorMessage，再由
    // BackendManager 汇总上报，定位「所有后端均加载失败」的芯片相关根因。
    (void)thiz;
    return env->NewStringUTF(g_last_load_error.c_str());
}

// 运行时信息握手：回传 JNI ABI 版本、钉定 MNN commit、native build ID 与能力集。
// Kotlin 侧 MnnBridge 加载库后解析一次（nativeGetRuntimeInfo），与期望 ABI/commit 不符时
// 置 nativeAvailable=false 并暴露诊断（runtimeDiagnostic），在模型加载前拦截不兼容的 native 栈。
// 能力集反映本 libMNN.so 钉定构建的编译期特性（LLM/低内存/OpenCL/ARM82 开启，QNN 关闭）；
// stream_batching_v1 在流式批处理改造（后续任务）落地后追加。
// JSON 形如 {"abiVersion":1,"mnnCommit":"af0142...","nativeBuildId":"...","capabilities":["mmap",...]}
JNIEXPORT jstring JNICALL
Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGetRuntimeInfo(
        JNIEnv *env, jclass clazz) {
    (void)clazz;
    static const char *kFmt =
        "{\"abiVersion\":%d,"
        "\"mnnCommit\":\"%s\","
        "\"nativeBuildId\":\"%s\","
        "\"capabilities\":[\"mmap\",\"cached_mmap\",\"reuse_kv\",\"opencl\",\"arm82\"]}";
    char buf[512];
    snprintf(buf, sizeof(buf), kFmt,
             (int)CHAT_MNN_JNI_ABI, CHAT_MNN_COMMIT, CHAT_MNN_BUILD_ID);
    return env->NewStringUTF(buf);
}

}  // extern "C"
