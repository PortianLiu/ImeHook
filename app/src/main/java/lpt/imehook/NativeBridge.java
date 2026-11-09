package lpt.imehook;

/**
 * JNI 桥。native 库不在目标 App 内，需先从模块 APK 解压再 System.load（见 HookEntry）。
 */
public final class NativeBridge {

    private NativeBridge() {
    }

    /**
     * 下发配置。
     *
     * @param mode  0=关闭 1=槽位改写 2=自定义表
     * @param slot  槽位 id（1..7）：mode=1 强制使用该槽；mode=2 不使用该参数
     * @param table 自定义映射表，格式：每行 key=py1,py2（仅 mode=2 生效）
     */
    public static native void nativeConfigure(int mode, int slot, String table);

    /**
     * 尝试在 libshell.so 上安装 hook。库未加载时返回 false，可稍后重试。
     * 定位顺序：版本偏移表（已知版本快路径）→ 特征码扫描（未知版本兜底）。
     *
     * @param targetVersionCode 目标 App 的 versionCode，用于查偏移表
     */
    public static native boolean nativeTryInstall(long targetVersionCode);

    /**
     * 下发版本→偏移表。进程启动时从存储读入一次后常驻 native 内存。
     *
     * @param targets 每行 versionCode=offset:signature（32 hex 字符），空字符串表示回落内置兜底表
     */
    public static native void nativeSetTargets(String targets);

    /**
     * 取走并清空按键级事件缓冲。每行一条：
     * t=单调ns d=耗时ns m=模式 s=槽位 k=按键 r=候选数 x=走向 v=展开拼音。
     */
    public static native String nativeDrainDiag();

    /**
     * 常驻快照：installed/unsupported/how/vc/mode/slot/table/converts/custom/miss。
     */
    public static native String nativeStats();
}
