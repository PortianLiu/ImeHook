package lpt.imehook;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

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
                }
            }
        });
    }

    private static void init(Context ctx) throws Exception {
        loadNative(ctx);

        ModuleConfig cfg = ModuleConfig.loadFromHookSide();
        NativeBridge.nativeConfigure(cfg.mode, cfg.slot, cfg.table);

        long vc = versionCodeOf(ctx, TARGET);
        boolean installed = NativeBridge.nativeTryInstall(vc);
        XposedBridge.log("[ImeHook] configured: mode=" + cfg.mode + " slot=" + cfg.slot
                + " tableLen=" + (cfg.table == null ? 0 : cfg.table.length())
                + " versionCode=" + vc + " installedNow=" + installed);

        // libshell.so 可能在 Application.onCreate 之后才加载，钩 loadLibrary 做延迟重试
        hookLoadLibraryRetry(vc);
    }

    /** 从模块 APK 中解压 native 库到目标 App 私有目录后加载。 */
    private static void loadNative(Context ctx) throws Exception {
        if (sNativeLoaded) return;
        ApplicationInfo ai = ctx.getPackageManager()
                .getApplicationInfo(ModuleConfig.PACKAGE, 0);
        File dst = new File(ctx.getFilesDir(), "imehook/" + LIB_NAME);
        if (!dst.exists()) {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (ZipFile zip = new ZipFile(ai.sourceDir)) {
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
                try {
                    NativeBridge.nativeTryInstall(versionCode);
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
        } catch (Throwable t) {
            XposedBridge.log("[ImeHook] hook loadLibrary0(Class,String) failed: " + t);
        }
    }
}
