package lpt.imehook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TARGET = "com.bytedance.android.doubaoime";
    private static final String LIB_NAME = "libimehook.so";
    private static final String LIB_ZIP_ENTRY = "lib/arm64-v8a/" + LIB_NAME;

    private static volatile boolean sNativeLoaded = false;
    private static volatile boolean sLazyInstalled = false;
    private static Context sCtx = null;
    /** 启动时读到的重启信号；之后只有值变化才认为自己被要求退出，避免自杀重启死循环。 */
    private static volatile String sExitMark = null;
    /** 详细日志开关（由设置页控制）；只有这里为 true 时才把按键级事件排空出去。 */
    private static volatile boolean sDiag = false;

    /** 常驻日志：安装/配置/异常等低频事件，始终开启。 */
    private static void pushLog(Context ctx, String lines) {
        if (lines == null || lines.isEmpty()) return;
        try {
            Bundle b = new Bundle();
            b.putString("lines", lines);
            ctx.getContentResolver().call(ModuleConfig.LOG_URI, "appendLog", null, b);
        } catch (Throwable ignored) {
            // 模块进程被停止/未安装时静默丢弃，不影响输入法
        }
    }

    private static void drainDiag(Context ctx) {
        if (!sNativeLoaded) return;
        try {
            String s = NativeBridge.nativeDrainDiag();
            pushLog(ctx, s);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;

        XposedBridge.log("[ImeHook] loaded into " + lpparam.packageName + " (" + lpparam.processName + ")");

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    init((Context) param.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log("[ImeHook] init failed: " + t);
                    pushLog(sCtx, "[error] init failed: " + t);
                }
            }
        });
    }

    private static void init(Context ctx) throws Exception {
        sCtx = ctx.getApplicationContext();
        try {
            loadNative(sCtx);
        } catch (Throwable t) {
            XposedBridge.log("[ImeHook] load native failed: " + t);
            pushLog(sCtx, "[error] load native failed: " + t);
        }

        long vc = versionCodeOf(sCtx, TARGET);

        // 先灌配置与偏移表（applyConfig 内部会调 nativeSetTargets），再尝试安装 hook
        applyConfig(sCtx, true);

        boolean installed = false;
        if (sNativeLoaded) {
            try {
                installed = NativeBridge.nativeTryInstall(vc);
            } catch (Throwable t) {
                XposedBridge.log("[ImeHook] install failed: " + t);
                pushLog(sCtx, "[error] install failed: " + t);
            }
        }
        XposedBridge.log("[ImeHook] versionCode=" + vc + " nativeLoaded=" + sNativeLoaded
                + " installedNow=" + installed);
        // 常驻基线：装了哪个版本、钩子是否装上都记一条，与详细日志开关无关
        String stats = "";
        if (sNativeLoaded) {
            try {
                stats = NativeBridge.nativeStats();
            } catch (Throwable ignored) {
            }
        }
        pushLog(sCtx, "[baseline] versionCode=" + vc + " nativeLoaded=" + sNativeLoaded
                + " installedNow=" + installed + " " + stats);

        // libshell.so 可能在 Application.onCreate 之后才加载，钩 loadLibrary 做延迟重试
        hookLoadLibraryRetry(vc);
        // 模块改配置后立即生效，无需重启目标 App
        observeConfig(sCtx);
    }

    private static void applyConfig(Context ctx, boolean first) {
        ModuleConfig cfg = ModuleConfig.loadFromHookSide(ctx);
        if (sNativeLoaded) {
            try {
                NativeBridge.nativeConfigure(cfg.mode, cfg.slot, cfg.table);
            } catch (Throwable t) {
                XposedBridge.log("[ImeHook] configure failed: " + t);
            }
            // 偏移表：启动时读入一次，配置/偏移表变化时同步更新；空则 native 回落内置兜底表
            try {
                NativeBridge.nativeSetTargets(ModuleConfig.loadTargets(ctx));
            } catch (Throwable t) {
                XposedBridge.log("[ImeHook] setTargets failed: " + t);
            }
            try {
                NativeBridge.nativeSetDiag(cfg.diag);
                NativeBridge.nativeSetDiagContent(cfg.diagContent);
            } catch (Throwable t) {
                XposedBridge.log("[ImeHook] setDiag failed: " + t);
            }
        }
        sDiag = cfg.diag;
        XposedBridge.log("[ImeHook] configured: mode=" + cfg.mode + " slot=" + cfg.slot
                + " tableLen=" + cfg.table.length() + " diag=" + cfg.diag
                + " diagc=" + cfg.diagContent
                + (first ? " (startup)" : " (reload)"));
        if (!first) {
            pushLog(ctx, "[baseline] config applied mode=" + cfg.mode + " slot=" + cfg.slot
                    + " tableLen=" + cfg.table.length() + " diag=" + cfg.diag
                    + " diagc=" + cfg.diagContent);
        }

        String mark = cfg.exit;
        if (first) {
            sExitMark = mark;
            return;
        }
        if (!mark.isEmpty() && !mark.equals(sExitMark)) {
            sExitMark = mark;
            XposedBridge.log("[ImeHook] restart requested, killing own process");
            pushLog(ctx, "[baseline] restart requested by user");
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                Process.killProcess(Process.myPid());
            }).start();
        }
    }

    private static void observeConfig(final Context ctx) {
        HandlerThread t = new HandlerThread("imehook-config");
        t.start();
        final Handler h = new Handler(t.getLooper());
        h.post(new Runnable() {
            private long last = Long.MIN_VALUE;
            private int tick = 0;

            @Override
            public void run() {
                long v = ModuleConfig.configVersion(ctx);
                if (last == Long.MIN_VALUE) {
                    last = v;
                } else if (v != last) {
                    last = v;
                    applyConfig(ctx, false);
                }
                // 详细日志只在用户开启后才有数据，每 3 秒排空一次；排空与落盘都在后台线程
                if (sDiag) {
                    if (++tick >= 3) {
                        tick = 0;
                        drainDiag(ctx);
                    }
                } else {
                    tick = 0;
                }
                h.postDelayed(this, 1000);
            }
        });
    }

    /** 从模块 APK 中解压 native 库到目标 App 私有目录后加载。 */
    private static void loadNative(Context ctx) throws Exception {
        if (sNativeLoaded) return;
        String apk = ModuleConfig.moduleApkPath(ctx);
        if (apk == null) throw new IllegalStateException("module apk path unavailable");
        // 文件名带上 APK 修改时间：模块升级后自动换新库，避免加载旧缓存
        File dst = new File(ctx.getFilesDir(),
                "imehook/libimehook-" + new File(apk).lastModified() + ".so");
        if (!dst.exists()) {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry(LIB_ZIP_ENTRY);
                if (entry == null) throw new IllegalStateException(LIB_ZIP_ENTRY + " not in module apk");
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
        System.load(dst.getAbsolutePath());
        sNativeLoaded = true;
    }

    private static long versionCodeOf(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static void hookLoadLibraryRetry(final long versionCode) {
        XC_MethodHook retry = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sLazyInstalled) return;
                try {
                    if (!sNativeLoaded) loadNative(sCtx);
                    if (NativeBridge.nativeTryInstall(versionCode)) {
                        sLazyInstalled = true;
                        // 装上了才把配置灌进去（libshell 之前配置是空转）
                        applyConfig(sCtx, false);
                        XposedBridge.log("[ImeHook] hook installed after libshell loaded");
                        pushLog(sCtx, "[baseline] hook installed after libshell loaded " + NativeBridge.nativeStats());
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "loadLibrary0",
                    ClassLoader.class, String.class, retry);
        } catch (Throwable t) {
            XposedBridge.log("[ImeHook] hook loadLibrary0(ClassLoader,String) failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "loadLibrary0",
                    Class.class, String.class, retry);
        } catch (Throwable ignored) {
        }
    }
}
