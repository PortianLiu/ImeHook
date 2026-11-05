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
     * @param slot  目标槽位 id（1..7），仅 mode=1 生效
     * @param table 自定义映射表，格式：每行 key=py1,py2（仅 mode=2 生效）
     */
    public static native void nativeConfigure(int mode, int slot, String table);

    /**
     * 尝试在 libshell.so 上安装 hook。库未加载时返回 false，可稍后重试。
     *
     * @param targetVersionCode 目标 App 的 versionCode，用于查偏移表
     */
    public static native boolean nativeTryInstall(long targetVersionCode);
}
