package lpt.imehook;

import android.content.ContentProviderClient;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 模块 App 与目标进程之间的配置。
 *
 * 为什么不用 XSharedPreferences：模块的 SharedPreferences 被 LSPosed 重定向到
 * /data/misc/apexdata/<uuid>/prefs/lpt.imehook/config.xml，权限 0660、属主是模块 uid，
 * 目标进程（另一个 uid）读不到。因此走 ContentProvider。
 *
 * 目标进程访问 Provider 必须用 {@link ContentResolver#acquireUnstableContentProviderClient}：
 * 默认的稳定连接会在模块进程被杀时让系统<strong>连带杀掉</strong>输入法进程
 * （"depends on provider … in dying proc"），冷启动后再读失败就会表现为「一杀后台注入失效」。
 * 读成功后镜像到目标 filesDir，模块不可用时懒加载镜像，失败不降级为 mode=0。
 */
public final class ModuleConfig {

    public static final String PACKAGE = "lpt.imehook";
    /** 模块自用的 SharedPreferences（方案库、提示信息，只在本进程读）。 */
    public static final String PREFS = "config";

    public static final String AUTHORITY = "lpt.imehook.config";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    public static final Uri TARGETS_URI = Uri.parse("content://" + AUTHORITY + "/targets");
    public static final Uri APK_URI = Uri.parse("content://" + AUTHORITY + "/apk");
    /** 目标进程把日志行推到模块 App 落盘的通道（call("appendLog")）。 */
    public static final Uri LOG_URI = Uri.parse("content://" + AUTHORITY + "/log");
    public static final String FILE_NAME = "config.txt";
    public static final String TARGETS_FILE_NAME = "targets.txt";

    /** 目标进程内镜像目录名（位于目标 filesDir 下）。 */
    public static final String HOOK_CACHE_DIR = "imehook";
    public static final String CACHED_CONFIG_NAME = "cached-config.txt";
    public static final String CACHED_TARGETS_NAME = "cached-targets.txt";

    /** 关闭，完全原生行为 */
    public static final int MODE_OFF = 0;
    /** 把所有调用固定到内置隐藏槽位（改写 scheme id） */
    public static final int MODE_SLOT = 1;
    /** 完全自定义映射表（全槽生效；不使用 slot，未命中直接返回空） */
    public static final int MODE_CUSTOM = 2;

    private static final String HEADER_TABLE = "---table---";
    private static final String TAG = "ImeHook";

    public int mode = MODE_OFF;
    public int slot = 2;
    public String table = "";
    /**
     * 重启信号。每次请求重启写入一个新值；目标进程发现值变化后自行退出
     * （自杀不需要 root，也不依赖 su）。
     */
    public String exit = "";

    private ModuleConfig() {
    }

    /** 注入侧一次加载的结果：区分「有数据」与「通道失败且无缓存」。 */
    public static final class HookLoad {
        /** 解析后的配置；{@link #present} 为 false 时仅为默认空配置，不应覆盖已生效的 native 状态。 */
        public final ModuleConfig config;
        /** 偏移表正文；无数据时为空串（native 可回落内置表）。 */
        public final String targets;
        /** true：Provider 或本地镜像至少提供了一份可用配置正文。 */
        public final boolean present;
        /** true：配置正文来自 Provider（并已尝试刷新镜像）。 */
        public final boolean fromProvider;
        /** 镜像文件绝对路径（便于日志核对是否写到目标 filesDir）。 */
        public final String cachePath;

        HookLoad(ModuleConfig config, String targets, boolean present, boolean fromProvider,
                 String cachePath) {
            this.config = config;
            this.targets = targets != null ? targets : "";
            this.present = present;
            this.fromProvider = fromProvider;
            this.cachePath = cachePath != null ? cachePath : "";
        }
    }

    /**
     * 目标进程侧：优先 unstable Provider，成功则镜像；失败则读本地镜像。
     * 二者皆无或远程空壳不足以覆盖镜像时 {@link HookLoad#present}=false。
     */
    public static HookLoad loadForHook(Context ctx) {
        File cacheCfg = cacheConfigFile(ctx);
        String cachePath = cacheCfg.getAbsolutePath();
        String remoteCfg = readUriTextUnstable(ctx, CONFIG_URI);
        String remoteTargets = readUriTextUnstable(ctx, TARGETS_URI);

        if (remoteCfg != null && looksLikeConfig(remoteCfg)) {
            mirrorText(cacheCfg, remoteCfg);
            if (remoteTargets != null) {
                mirrorText(cacheTargetsFile(ctx), remoteTargets);
            }
            ModuleConfig c = new ModuleConfig();
            c.parse(remoteCfg);
            String targets = remoteTargets != null ? remoteTargets : readCacheText(cacheTargetsFile(ctx));
            Log.i(TAG, "loadForHook provider ok mode=" + c.mode + " tableLen=" + c.table.length()
                    + " mirrored=" + cacheCfg.isFile() + " path=" + cachePath);
            return new HookLoad(c, targets != null ? targets : "", true, true, cachePath);
        }
        if (remoteCfg != null) {
            Log.w(TAG, "provider returned unusable config (len=" + remoteCfg.length()
                    + "), keep cache if any");
        }

        String cachedCfg = readCacheText(cacheCfg);
        String cachedTargets = readCacheText(cacheTargetsFile(ctx));
        if (cachedCfg != null && looksLikeConfig(cachedCfg)) {
            Log.i(TAG, "config provider unavailable, using hook-side cache path=" + cachePath);
            ModuleConfig c = new ModuleConfig();
            c.parse(cachedCfg);
            return new HookLoad(c, cachedTargets != null ? cachedTargets : "", true, false, cachePath);
        }
        return new HookLoad(new ModuleConfig(), cachedTargets != null ? cachedTargets : "",
                false, false, cachePath);
    }

    /** 模块 App 侧：读本进程私有 config.txt（与 Provider 同源文件）。 */
    public static ModuleConfig load(Context ctx) {
        ModuleConfig c = new ModuleConfig();
        String text = readCacheText(configFile(ctx));
        if (text == null) {
            // 设置页与 Provider 同进程时也可走 unstable，避免特殊 ROM 差异
            text = readUriTextUnstable(ctx, CONFIG_URI);
        }
        if (text != null) c.parse(text);
        return c;
    }

    /** 模块 App 侧读偏移表。 */
    public static String loadTargets(Context ctx) {
        String text = readCacheText(targetsFile(ctx));
        if (text == null) text = readUriTextUnstable(ctx, TARGETS_URI);
        return text != null ? text : "";
    }

    /**
     * 模块 APK 路径。必须走 unstable client，否则稳定引用会在模块被杀时拖死输入法进程。
     */
    public static String moduleApkPath(Context ctx) {
        ContentProviderClient client = null;
        Cursor c = null;
        try {
            client = ctx.getContentResolver().acquireUnstableContentProviderClient(AUTHORITY);
            if (client == null) return null;
            c = client.query(APK_URI, null, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable t) {
            Log.w(TAG, "query module apk failed: " + t);
        } finally {
            if (c != null) c.close();
            releaseQuietly(client);
        }
        return null;
    }

    /** 目标进程推日志：unstable call，模块死了就丢弃，绝不拖死输入法。 */
    public static void appendLogUnstable(Context ctx, String lines) {
        if (lines == null || lines.isEmpty()) return;
        ContentProviderClient client = null;
        try {
            client = ctx.getContentResolver().acquireUnstableContentProviderClient(AUTHORITY);
            if (client == null) return;
            Bundle b = new Bundle();
            b.putString("lines", lines);
            client.call("appendLog", null, b);
        } catch (Throwable ignored) {
        } finally {
            releaseQuietly(client);
        }
    }

    /** 写入并通知目标进程；目标进程若在运行会经 ContentObserver 立刻重建配置。 */
    public void publish(Context ctx) {
        try {
            File f = configFile(ctx);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            byte[] bytes = serialize().getBytes("UTF-8");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
                out.getFD().sync();
            }
            ctx.getContentResolver().notifyChange(CONFIG_URI, null);
        } catch (Throwable t) {
            Log.e(TAG, "publish config failed: " + t);
        }
    }

    /** 偏移表文件更新后调用，通知目标进程重载。 */
    public static void notifyTargetsChanged(Context ctx) {
        try {
            ctx.getContentResolver().notifyChange(TARGETS_URI, null);
        } catch (Throwable t) {
            Log.e(TAG, "notify targets failed: " + t);
        }
    }

    static File configFile(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static File targetsFile(Context ctx) {
        return new File(ctx.getFilesDir(), TARGETS_FILE_NAME);
    }

    static File hookCacheDir(Context ctx) {
        return new File(ctx.getFilesDir(), HOOK_CACHE_DIR);
    }

    static File cacheConfigFile(Context ctx) {
        return new File(hookCacheDir(ctx), CACHED_CONFIG_NAME);
    }

    static File cacheTargetsFile(Context ctx) {
        return new File(hookCacheDir(ctx), CACHED_TARGETS_NAME);
    }

    /** 供 Provider 返回版本号（配置与偏移表任一文件的修改时间，取较大者）。 */
    static long versionOf(Context ctx) {
        long cfg = configFile(ctx).lastModified();
        long tgt = targetsFile(ctx).lastModified();
        return Math.max(cfg, tgt);
    }

    private String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(mode).append('\n');
        sb.append("slot=").append(slot).append('\n');
        sb.append("exit=").append(exit).append('\n');
        sb.append(HEADER_TABLE).append('\n');
        sb.append(table);
        if (!table.isEmpty() && !table.endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    private void parse(String text) {
        int sep = text.indexOf(HEADER_TABLE);
        String head = sep < 0 ? text : text.substring(0, sep);
        if (sep >= 0) {
            int from = sep + HEADER_TABLE.length();
            table = text.substring(Math.min(from, text.length())).trim();
        }
        for (String line : head.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "mode":
                    mode = parseInt(v, mode);
                    break;
                case "slot":
                    slot = parseInt(v, slot);
                    break;
                case "exit":
                    exit = v;
                    break;
                // 旧版 diag/diagc 键忽略：按键日志已常开，不再落配置
                default:
                    break;
            }
        }
        if (mode < MODE_OFF || mode > MODE_CUSTOM) mode = MODE_OFF;
        if (slot < 1 || slot > 7) slot = 2;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    /** 至少要有 mode= 行，避免空串/半截读被当成「成功」并覆盖镜像。 */
    private static boolean looksLikeConfig(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        return t.contains("mode=");
    }

    private static String readUriTextUnstable(Context ctx, Uri uri) {
        ContentProviderClient client = null;
        ParcelFileDescriptor pfd = null;
        try {
            client = ctx.getContentResolver().acquireUnstableContentProviderClient(AUTHORITY);
            if (client == null) return null;
            pfd = client.openFile(uri, "r");
            if (pfd == null) return null;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                pfd = null; // 已交给 AutoCloseInputStream
                return readAll(in);
            }
        } catch (Throwable t) {
            Log.w(TAG, "unstable read " + uri + " failed: " + t);
            return null;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
            releaseQuietly(client);
        }
    }

    private static String readCacheText(File f) {
        if (f == null || !f.isFile() || f.length() == 0) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Throwable t) {
            Log.w(TAG, "read cache " + f + " failed: " + t);
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static void mirrorText(File dst, String text) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "mkdirs failed: " + parent);
            }
            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            byte[] bytes = text.getBytes("UTF-8");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!tmp.renameTo(dst)) {
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            Log.i(TAG, "mirrored " + bytes.length + " bytes -> " + dst.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "mirror to " + dst + " failed: " + t);
        }
    }

    private static void releaseQuietly(ContentProviderClient client) {
        if (client == null) return;
        try {
            client.release();
        } catch (Throwable ignored) {
        }
    }
}
