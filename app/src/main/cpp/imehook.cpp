// libimehook：对豆包输入法 libshell.so 中 sub_59953C（双拼键位→候选拼音）的 inline hook。
//
// 目标函数（1.5.4，vaddr 0x59953C，逆向结论见 doubaoime/docs/ANALYSIS.md §6.5）：
//   x0 = scheme*        首字节 = 方案槽 id（1..7，2=自然码，6=小鹤）
//   x1 = std::string*   键串（长度 1 或 2）
//   x8 = sret           std::vector<std::string>*（24B 大返回值，调用方空间）
//   纯函数：无状态、无锁、无 I/O。
//
// 三种模式：
//   0 关闭：直通
//   1 槽位：入口改写 scheme 首字节为目标槽 id，调用原函数后恢复（启用隐藏内置方案）
//   2 自定义：先改写 id 到目标槽（统领单函数但按 id 选表，改写即「切换到该槽」），
//             命中自定义表则自行构造返回 vector，未命中回退该槽的原生展开结果
//
// 注意：sub_59953C 是 7 个槽共用的唯一收口，但表由 *scheme 的 id 选择，
// 因此「注入某个槽」= 在这个收口上按 id 生效，hook 本身不需要 7 份。

#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "dobby.h"

#define LOG_TAG "ImeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------- libc++ 运行时布局（与分析文档一致） ----------
// std::string（24B）：短串 raw[0] = len<<1（偶数），数据从 +1；
//                    长串 raw[0] 为奇数，+8 = len，+16 = data。
struct SpStr {
    char raw[24];
};
// std::vector<std::string>（24B）：{begin, end, cap}
struct SpVec {
    SpStr *b, *e, *c;
};

static std::string spToStr(const SpStr* s) {
    const auto* r = reinterpret_cast<const unsigned char*>(s->raw);
    if ((r[0] & 1u) == 0) return std::string(reinterpret_cast<const char*>(r + 1), r[0] >> 1);
    uint64_t len = 0;
    std::memcpy(&len, r + 8, 8);
    const char* data = nullptr;
    std::memcpy(&data, r + 16, 8);
    return std::string(data, len);
}

// 返回元素只用 SSO 短串（拼音音节最长 6，远小于 22），彻底避开堆生命周期。
static void writeSso(SpStr* dst, const std::string& s) {
    auto* r = reinterpret_cast<unsigned char*>(dst->raw);
    size_t n = s.size();
    if (n > 22) n = 22;
    r[0] = static_cast<unsigned char>(n << 1);
    std::memcpy(r + 1, s.data(), n);
    r[1 + n] = 0;
}

// vector 缓冲用 malloc：Android libc++ 默认分配器最终即 malloc/free，
// 引擎按移动语义析构时不会崩；长串不会出现（见上）。
static SpVec makeVec(const std::vector<std::string>& vs) {
    size_t n = vs.size();
    auto* arr = static_cast<SpStr*>(std::malloc(n * sizeof(SpStr)));
    for (size_t i = 0; i < n; i++) writeSso(arr + i, vs[i]);
    return SpVec{arr, arr + n, arr + n};
}

// ---------- 配置 ----------
using Table = std::unordered_map<std::string, std::vector<std::string>>;

static std::atomic<int> g_mode{0};
static std::atomic<int> g_slot{2};
static std::shared_ptr<const Table> g_table = std::make_shared<const Table>();

// ---------- hook ----------
// 返回值 24B > 16B，编译器自动走 x8 sret，与原函数 ABI 一致（ANALYSIS §8.4 的陷阱）。
using ExpandFn = SpVec (*)(void* scheme, const SpStr* key);
static ExpandFn g_orig = nullptr;

// ---------- 诊断 ----------
// 常驻（始终开启）只有几个原子计数：热路径上一次 relaxed fetch_add，约 1~2ns。
// 详细记录需用户在设置页手动开启；此时每次调用多两次 clock_gettime + 一次加锁写定长数组，
// 仍不做 I/O、不拼字符串、不分配内存；落盘全部在后台线程完成。
static std::atomic<bool> g_diag{false};

static std::atomic<uint64_t> g_nConvert{0};
static std::atomic<uint64_t> g_nCustomHit{0};
static std::atomic<uint64_t> g_nFallback{0};

struct DiagEvent {
    uint64_t tNs;       // 事件时刻（单调时钟）
    uint32_t durNs;     // 本次 hook 调用耗时
    uint16_t key;       // 原始按键字节（最多 2 个 ASCII 字符，低字节在前）
    uint8_t  keyLen;
    uint8_t  mode;      // 0 直通 1 槽位 2 自定义
    uint8_t  slot;
    uint8_t  kind;      // 0 直通 1 槽位改写 2 自定义命中 3 回退原生
    uint8_t  results;   // 返回候选数
};

constexpr size_t kDiagCap = 512;
static std::mutex g_diagMutex;
static DiagEvent g_diagBuf[kDiagCap];
static size_t g_diagLen = 0;
static uint64_t g_diagDropped = 0;

static inline uint64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull + static_cast<uint64_t>(ts.tv_nsec);
}

static void diagPush(const DiagEvent& e) {
    std::lock_guard<std::mutex> lock(g_diagMutex);
    if (g_diagLen < kDiagCap) g_diagBuf[g_diagLen++] = e;
    else g_diagDropped++;
}

// 只读 SSO 短串的前两个字节，避免为记录日志构造 std::string。
static void packKey(const SpStr* s, uint16_t& out, uint8_t& len) {
    const auto* r = reinterpret_cast<const unsigned char*>(s->raw);
    out = 0;
    len = 0;
    if ((r[0] & 1u) != 0) return;  // 非短串（实际不会出现）不记录内容
    size_t n = r[0] >> 1;
    if (n > 2) n = 2;
    len = static_cast<uint8_t>(n);
    if (n > 0) out |= static_cast<uint16_t>(r[1]);
    if (n > 1) out |= static_cast<uint16_t>(r[2]) << 8;
}

static SpVec expandHook(void* scheme, const SpStr* key) {
    const int mode = g_mode.load(std::memory_order_relaxed);
    g_nConvert.fetch_add(1, std::memory_order_relaxed);
    const bool diag = g_diag.load(std::memory_order_relaxed);
    const uint64_t t0 = diag ? nowNs() : 0;

    // 前几次调用打点，便于确认钩子确实命中、以及改写后的 id 是否生效
    static std::atomic<int> s_calls{0};
    int n = s_calls.fetch_add(1, std::memory_order_relaxed);
    if (n < 3) {
        LOGI("call#%d mode=%d origId=%d targetId=%d key=%s", n, mode,
             static_cast<int>(*static_cast<unsigned char*>(scheme)),
             g_slot.load(std::memory_order_relaxed), spToStr(key).c_str());
    }

    uint8_t kind = 0;
    SpVec r;
    if (mode == 1) {
        kind = 1;
        auto* id = static_cast<unsigned char*>(scheme);
        unsigned char origId = *id;
        *id = static_cast<unsigned char>(g_slot.load(std::memory_order_relaxed));
        r = g_orig(scheme, key);
        *id = origId;
    } else if (mode == 2) {
        auto* id = static_cast<unsigned char*>(scheme);
        unsigned char origId = *id;
        *id = static_cast<unsigned char>(g_slot.load(std::memory_order_relaxed));

        auto tbl = std::atomic_load(&g_table);
        std::string k = spToStr(key);
        auto it = tbl->find(k);
        if (it != tbl->end()) {
            *id = origId;
            r = makeVec(it->second);
            kind = 2;
            g_nCustomHit.fetch_add(1, std::memory_order_relaxed);
        } else {
            // 未覆盖的键回退到目标槽的原生结果（此时 id 已被改成目标槽）
            r = g_orig(scheme, key);
            *id = origId;
            kind = 3;
            g_nFallback.fetch_add(1, std::memory_order_relaxed);
        }
    } else {
        r = g_orig(scheme, key);
    }

    if (diag) {
        DiagEvent e{};
        e.tNs = t0;
        e.durNs = static_cast<uint32_t>(nowNs() - t0);
        e.mode = static_cast<uint8_t>(mode);
        e.slot = static_cast<uint8_t>(g_slot.load(std::memory_order_relaxed));
        e.kind = kind;
        e.results = (r.e > r.b) ? static_cast<uint8_t>(std::min<size_t>(r.e - r.b, 255)) : 0;
        packKey(key, e.key, e.keyLen);
        diagPush(e);
    }
    return r;
}

// ---------- 定位策略 ----------
// 路径 1（快）：版本表精确命中 + 序言签名校验，零扫描开销。
// 路径 2（兜底）：特征码扫描 RX 段，应对未知版本/变体包；不依赖任何外部工具。
// 两条都失败才判定不支持，拒绝 hook（宁可失效也不误钩）。

struct TargetInfo {
    long versionCode;
    uint32_t offset;      // sub_59953C 的 vaddr
    uint8_t sig[16];      // 函数序言前 16 字节
};

// 内置兜底表：targets.txt 缺失/为空/解析失败时使用。
// 正常情况下由 Java 侧从存储读入偏移表并通过 nativeSetTargets 下发。
static const TargetInfo kBuiltinTargets[] = {
        // 豆包输入法 1.5.4
        {100504010L, 0x59953Cu,
         {0xff, 0x43, 0x03, 0xd1, 0xfd, 0x7b, 0x07, 0xa9,
          0xfc, 0x6f, 0x08, 0xa9, 0xfa, 0x67, 0x09, 0xa9}},
        // 1.5.3：未收录，由特征码扫描兜底
};

// 运行时偏移表：由 nativeSetTargets 从存储下发，进程启动读入一次后常驻内存。
static std::vector<TargetInfo> g_targets;
static std::mutex g_targetsMutex;

// ---- 特征码：函数体内「方案槽选表」的指令骨架（与 tools/verify_sig.py 同步）----
// 1.5.4 @0x599648（函数头 +0x10C）：
//   SUB  W8, W8, #1          ; CMP  W8, #6        ; B.HI <rel>
//   SXTB X8, W8              ; ADRP X9, <page>    ; ADD X9, X9, #<off>
//   LDR  X21, [X9,X8,LSL#3]  ; B    <rel>
// 只通配地址/跳转偏移字段，寄存器分配全保留：版本变动导致失配时宁可拒绝 hook。
static const uint8_t kSigPattern[] = {
        0x08, 0x05, 0x00, 0x51, 0x1f, 0x19, 0x00, 0x71,
        0x08, 0x00, 0x00, 0x54, 0x08, 0x1d, 0x40, 0x93,
        0x09, 0x00, 0x00, 0x90, 0x29, 0x01, 0x00, 0x91,
        0x35, 0x79, 0x68, 0xf8, 0x00, 0x00, 0x00, 0x14,
};
static const uint8_t kSigMask[] = {
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0x1f, 0x00, 0x00, 0xff, 0xff, 0xff, 0xff, 0xff,
        0x1f, 0x00, 0x00, 0x9f, 0xff, 0x03, 0x00, 0xff,
        0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0xfc,
};
constexpr size_t kSigLen = sizeof(kSigPattern);
// 锚点距函数头的合理范围（1.5.4 实测 0x10C 与 0x1A0）
constexpr size_t kFuncMaxBacktrack = 0x400;

// 序言：SUB SP, SP, #imm（byte3=0xD1, Rn=Rd=SP → byte0=0xFF, byte1 低 2 位=11）
static bool isSubSp(uint32_t w) { return (w & 0xFF0003FFu) == 0xD10003FFu; }
// 紧跟 STP X29, X30, [SP, #imm]（byte3=0xA9, byte0=0xFD, byte1 低 3 位=011）
static bool isStpFpLr(uint32_t w) { return (w & 0xFF0007FFu) == 0xA90003FDu; }

static uintptr_t g_textStart = 0;
static size_t g_textSize = 0;

static uintptr_t findFuncStart(uintptr_t anchor) {
    const uintptr_t lo = anchor - std::min<uintptr_t>(kFuncMaxBacktrack, anchor - g_textStart);
    for (uintptr_t p = anchor - 4; p >= lo; p -= 4) {
        uint32_t w0, w1;
        std::memcpy(&w0, reinterpret_cast<const void*>(p), 4);
        std::memcpy(&w1, reinterpret_cast<const void*>(p + 4), 4);
        if (isSubSp(w0) && isStpFpLr(w1)) return p;
    }
    return 0;
}

// 该模式在同函数内有 EXACT/PREFIX 两处对称出现（1.5.4: 0x599648/0x5996dc），
// 因此不要求唯一命中，而要求所有命中收敛到同一函数头；
// 分叉即误命中，返回 0 拒绝。
static uintptr_t scanForTarget() {
    uint32_t firstWord;
    std::memcpy(&firstWord, kSigPattern, 4);  // 首条指令精确匹配做预筛
    const auto* text = reinterpret_cast<const uint8_t*>(g_textStart);
    uintptr_t func = 0;
    int hits = 0;
    for (size_t i = 0; i + kSigLen <= g_textSize; i += 4) {
        uint32_t cur;
        std::memcpy(&cur, text + i, 4);
        if (cur != firstWord) continue;
        bool ok = true;
        for (size_t j = 4; j < kSigLen; j++) {
            if ((text[i + j] & kSigMask[j]) != kSigPattern[j]) { ok = false; break; }
        }
        if (!ok) continue;
        hits++;
        uintptr_t fs = findFuncStart(g_textStart + i);
        if (fs == 0 || (func != 0 && func != fs)) {
            LOGE("signature scan diverged (hit#%d anchor=0x%zx)", hits, g_textStart + i);
            return 0;
        }
        func = fs;
    }
    LOGI("signature scan: hits=%d func=0x%zx", hits, func);
    return func;
}

// ---------- 安装 ----------
static uintptr_t g_base = 0;

static int phdrCb(struct dl_phdr_info* info, size_t, void*) {
    if (info->dlpi_name && std::strstr(info->dlpi_name, "libshell.so")) {
        g_base = static_cast<uintptr_t>(info->dlpi_addr);
        for (int i = 0; i < info->dlpi_phnum; i++) {
            const auto& ph = info->dlpi_phdr[i];
            if (ph.p_type == PT_LOAD && (ph.p_flags & PF_X)) {
                g_textStart = g_base + ph.p_vaddr;
                g_textSize = ph.p_memsz;
                break;
            }
        }
        return 1;
    }
    return 0;
}

static std::mutex g_installMutex;
static std::atomic<bool> g_installed{false};
static std::atomic<bool> g_unsupported{false};

static bool tryInstall(long versionCode) {
    if (g_installed.load() || g_unsupported.load()) return g_installed.load();
    std::lock_guard<std::mutex> lock(g_installMutex);
    if (g_installed.load()) return true;

    g_base = 0;
    g_textStart = 0;
    g_textSize = 0;
    dl_iterate_phdr(phdrCb, nullptr);
    if (g_base == 0) return false;  // libshell.so 尚未加载，等重试

    // 路径 1：版本表 + 序言签名校验
    // 优先用存储下发的偏移表；为空则回落内置兜底表。
    const TargetInfo* targets = nullptr;
    size_t targetCount = 0;
    {
        std::lock_guard<std::mutex> lock(g_targetsMutex);
        if (!g_targets.empty()) {
            targets = g_targets.data();
            targetCount = g_targets.size();
        }
    }
    if (targets == nullptr) {
        targets = kBuiltinTargets;
        targetCount = sizeof(kBuiltinTargets) / sizeof(kBuiltinTargets[0]);
    }

    void* addr = nullptr;
    const char* how = "";
    for (size_t i = 0; i < targetCount; i++) {
        const auto& k = targets[i];
        if (k.versionCode != versionCode) continue;
        void* a = reinterpret_cast<void*>(g_base + k.offset);
        if (std::memcmp(a, k.sig, sizeof(k.sig)) == 0) {
            addr = a;
            how = "version-table";
        } else {
            LOGE("version %ld prologue sig mismatch, fall back to scan", versionCode);
        }
        break;
    }

    // 路径 2：特征码扫描
    if (addr == nullptr) {
        uintptr_t f = scanForTarget();
        if (f != 0) {
            addr = reinterpret_cast<void*>(f);
            how = "signature-scan";
        }
    }

    if (addr == nullptr) {
        LOGE("target not found (versionCode=%ld), unsupported", versionCode);
        g_unsupported = true;
        return false;
    }

    DobbyHook(addr, reinterpret_cast<dobby_dummy_func_t>(&expandHook),
              reinterpret_cast<dobby_dummy_func_t*>(&g_orig));
    g_installed = g_orig != nullptr;
    LOGI("hook %s at %p (base=0x%zx by=%s)",
         g_installed.load() ? "installed" : "FAILED", addr, g_base, how);
    return g_installed.load();
}

// ---------- JNI ----------
static std::string trim(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\r");
    if (a == std::string::npos) return "";
    size_t b = s.find_last_not_of(" \t\r");
    return s.substr(a, b - a + 1);
}

// 格式：每行 key=py1,py2；空行与 # 开头的行忽略
static void parseTable(const std::string& all, Table& out) {
    size_t pos = 0;
    while (pos < all.size()) {
        size_t nl = all.find('\n', pos);
        std::string line = all.substr(pos, nl == std::string::npos ? nl : nl - pos);
        pos = nl == std::string::npos ? all.size() : nl + 1;

        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;

        std::string key = trim(line.substr(0, eq));
        std::vector<std::string> vals;
        size_t vp = eq + 1;
        while (vp <= line.size()) {
            size_t comma = line.find(',', vp);
            std::string v = trim(line.substr(vp, comma == std::string::npos ? comma : comma - vp));
            if (!v.empty()) vals.push_back(std::move(v));
            if (comma == std::string::npos) break;
            vp = comma + 1;
        }
        if (!key.empty() && !vals.empty()) out[key] = std::move(vals);
    }
}

// ---------- 偏移表解析 ----------
// 格式：每行 versionCode=offset:signature（32 个十六进制字符 = 16 字节）
//   例：100504010=0x59953C:ff4303d1fd7b07a9fc6f08a9fa6709a9
// 空行与 # 开头的行忽略。offset 支持 0x 前缀或纯十六进制。
static int hexVal(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool parseHexByte(const char*& p, uint8_t& out) {
    int hi = hexVal(p[0]);
    int lo = hexVal(p[1]);
    if (hi < 0 || lo < 0) return false;
    out = static_cast<uint8_t>((hi << 4) | lo);
    p += 2;
    return true;
}

static void parseTargets(const std::string& all, std::vector<TargetInfo>& out) {
    size_t pos = 0;
    while (pos < all.size()) {
        size_t nl = all.find('\n', pos);
        std::string line = all.substr(pos, nl == std::string::npos ? nl : nl - pos);
        pos = nl == std::string::npos ? all.size() : nl + 1;

        line = trim(line);
        if (line.empty() || line[0] == '#') continue;

        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string vcStr = trim(line.substr(0, eq));
        std::string rest = trim(line.substr(eq + 1));

        size_t colon = rest.find(':');
        if (colon == std::string::npos) continue;
        std::string offStr = trim(rest.substr(0, colon));
        std::string sigStr = trim(rest.substr(colon + 1));
        if (sigStr.size() != 32) continue;

        TargetInfo t{};
        try {
            t.versionCode = std::stol(vcStr);
        } catch (...) { continue; }

        try {
            std::string h = offStr;
            if (h.size() > 2 && h[0] == '0' && (h[1] == 'x' || h[1] == 'X')) h = h.substr(2);
            t.offset = static_cast<uint32_t>(std::stoul(h, nullptr, 16));
        } catch (...) { continue; }

        const char* p = sigStr.c_str();
        bool ok = true;
        for (int i = 0; i < 16; i++) {
            if (!parseHexByte(p, t.sig[i])) { ok = false; break; }
        }
        if (!ok) continue;

        out.push_back(t);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_lpt_imehook_NativeBridge_nativeConfigure(JNIEnv* env, jclass,
                                              jint mode, jint slot, jstring table) {
    g_mode.store(mode, std::memory_order_release);
    g_slot.store(slot, std::memory_order_relaxed);

    auto t = std::make_shared<Table>();
    if (table != nullptr) {
        const char* s = env->GetStringUTFChars(table, nullptr);
        if (s != nullptr) {
            parseTable(s, *t);
            env->ReleaseStringUTFChars(table, s);
        }
    }
    std::atomic_store(&g_table, std::shared_ptr<const Table>(t));
    LOGI("configured: mode=%d slot=%d tableEntries=%zu", mode, slot, t->size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_lpt_imehook_NativeBridge_nativeTryInstall(JNIEnv*, jclass, jlong versionCode) {
    return tryInstall(versionCode) ? JNI_TRUE : JNI_FALSE;
}

// 下发偏移表。进程启动时从存储读入一次后常驻内存；后续用户手填/网络更新可再次调用覆盖。
// 为空时 native 会自动回落内置兜底表，不会导致 hook 失败。
extern "C" JNIEXPORT void JNICALL
Java_lpt_imehook_NativeBridge_nativeSetTargets(JNIEnv* env, jclass, jstring targets) {
    std::vector<TargetInfo> parsed;
    if (targets != nullptr) {
        const char* s = env->GetStringUTFChars(targets, nullptr);
        if (s != nullptr) {
            parseTargets(s, parsed);
            env->ReleaseStringUTFChars(targets, s);
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_targetsMutex);
        g_targets = std::move(parsed);
    }
    LOGI("setTargets: %zu entries loaded", g_targets.size());
}

// ---------- 诊断 JNI ----------
extern "C" JNIEXPORT void JNICALL
Java_lpt_imehook_NativeBridge_nativeSetDiag(JNIEnv*, jclass, jboolean on) {
    g_diag.store(on == JNI_TRUE, std::memory_order_release);
    if (on != JNI_TRUE) {
        // 关闭时丢掉未排空的详细日志，避免残留按键内容
        std::lock_guard<std::mutex> lock(g_diagMutex);
        g_diagLen = 0;
        g_diagDropped = 0;
    }
}

/** 取走并清空详细日志缓冲；返回空串表示没有新事件。 */
extern "C" JNIEXPORT jstring JNICALL
Java_lpt_imehook_NativeBridge_nativeDrainDiag(JNIEnv* env, jclass) {
    std::string out;
    uint64_t dropped;
    {
        std::lock_guard<std::mutex> lock(g_diagMutex);
        dropped = g_diagDropped;
        g_diagDropped = 0;
        char keybuf[3] = {0, 0, 0};
        for (size_t i = 0; i < g_diagLen; i++) {
            const DiagEvent& e = g_diagBuf[i];
            keybuf[0] = (e.keyLen > 0) ? static_cast<char>(e.key & 0xFF) : 0;
            keybuf[1] = (e.keyLen > 1) ? static_cast<char>((e.key >> 8) & 0xFF) : 0;
            out += "t=";
            out += std::to_string(e.tNs);
            out += " d=";
            out += std::to_string(e.durNs);
            out += " m=";
            out += std::to_string(e.mode);
            out += " s=";
            out += std::to_string(e.slot);
            out += " k=";
            out += keybuf;
            out += " r=";
            out += std::to_string(e.results);
            out += " x=";
            out += std::to_string(e.kind);
            out += '\n';
        }
        g_diagLen = 0;
    }
    if (dropped > 0) {
        out += "dropped=";
        out += std::to_string(static_cast<unsigned long long>(dropped));
        out += '\n';
    }
    return env->NewStringUTF(out.c_str());
}

/** 常驻计数快照：钩子是否装上、调用/命中/回退次数。 */
extern "C" JNIEXPORT jstring JNICALL
Java_lpt_imehook_NativeBridge_nativeStats(JNIEnv* env, jclass) {
    std::string s;
    s += "installed=";
    s += g_installed.load() ? "1" : "0";
    s += " unsupported=";
    s += g_unsupported.load() ? "1" : "0";
    s += " converts=";
    s += std::to_string(static_cast<unsigned long long>(g_nConvert.load()));
    s += " custom=";
    s += std::to_string(static_cast<unsigned long long>(g_nCustomHit.load()));
    s += " fallback=";
    s += std::to_string(static_cast<unsigned long long>(g_nFallback.load()));
    return env->NewStringUTF(s.c_str());
}
