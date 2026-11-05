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

static SpVec expandHook(void* scheme, const SpStr* key) {
    int mode = g_mode.load(std::memory_order_acquire);

    // 前几次调用打点，便于确认钩子确实命中、以及改写后的 id 是否生效
    static std::atomic<int> s_calls{0};
    int n = s_calls.fetch_add(1, std::memory_order_relaxed);
    if (n < 3) {
        LOGI("call#%d mode=%d origId=%d targetId=%d key=%s", n, mode,
             static_cast<int>(*static_cast<unsigned char*>(scheme)),
             g_slot.load(std::memory_order_relaxed), spToStr(key).c_str());
    }

    if (mode == 1) {
        auto* id = static_cast<unsigned char*>(scheme);
        unsigned char origId = *id;
        *id = static_cast<unsigned char>(g_slot.load(std::memory_order_relaxed));
        SpVec r = g_orig(scheme, key);
        *id = origId;
        return r;
    }

    if (mode == 2) {
        auto* id = static_cast<unsigned char*>(scheme);
        unsigned char origId = *id;
        *id = static_cast<unsigned char>(g_slot.load(std::memory_order_relaxed));

        auto tbl = std::atomic_load(&g_table);
        std::string k = spToStr(key);
        auto it = tbl->find(k);
        if (it != tbl->end()) {
            *id = origId;
            return makeVec(it->second);
        }
        // 未覆盖的键回退到目标槽的原生结果（此时 id 已被改成目标槽）
        SpVec r = g_orig(scheme, key);
        *id = origId;
        return r;
    }

    return g_orig(scheme, key);
}

// ---------- 版本偏移表 + 特征码校验 ----------
struct TargetInfo {
    long versionCode;
    uint32_t offset;      // sub_59953C 的 vaddr
    uint8_t sig[16];      // 函数序言前 16 字节
};

static const TargetInfo kTargets[] = {
        // 豆包输入法 1.5.4
        {100504010L, 0x59953Cu,
         {0xff, 0x43, 0x03, 0xd1, 0xfd, 0x7b, 0x07, 0xa9,
          0xfc, 0x6f, 0x08, 0xa9, 0xfa, 0x67, 0x09, 0xa9}},
        // 1.5.3：sub_59953C 偏移待补（见 ANALYSIS §9）
};

// ---------- 安装 ----------
static uintptr_t g_base = 0;

static int phdrCb(struct dl_phdr_info* info, size_t, void*) {
    if (info->dlpi_name && std::strstr(info->dlpi_name, "libshell.so")) {
        g_base = static_cast<uintptr_t>(info->dlpi_addr);
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
    dl_iterate_phdr(phdrCb, nullptr);
    if (g_base == 0) return false;  // libshell.so 尚未加载，等重试

    const TargetInfo* t = nullptr;
    for (const auto& k : kTargets) {
        if (k.versionCode == versionCode) { t = &k; break; }
    }
    if (t == nullptr) {
        LOGE("unsupported target versionCode=%ld", versionCode);
        g_unsupported = true;  // 版本不对，重试无意义
        return false;
    }

    void* addr = reinterpret_cast<void*>(g_base + t->offset);
    if (std::memcmp(addr, t->sig, sizeof(t->sig)) != 0) {
        LOGE("signature mismatch at %p, refuse to hook", addr);
        g_unsupported = true;
        return false;
    }

    DobbyHook(addr, reinterpret_cast<dobby_dummy_func_t>(&expandHook),
              reinterpret_cast<dobby_dummy_func_t*>(&g_orig));
    g_installed = g_orig != nullptr;
    LOGI("hook %s at %p (base=0x%zx off=0x%x)",
         g_installed.load() ? "installed" : "FAILED",
         addr, g_base, t->offset);
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
