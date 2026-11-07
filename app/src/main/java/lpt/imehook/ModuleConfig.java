package lpt.imehook;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 模块 App 与目标进程之间的配置。
 *
 * 为什么不用 XSharedPreferences：模块的 SharedPreferences 被 LSPosed 重定向到
 * /data/misc/apexdata/<uuid>/prefs/lpt.imehook/config.xml，权限 0660、属主是模块 uid，
 * 目标进程（另一个 uid）读不到，实测 makeWorldReadable() 也改不动别人的文件。
 * 因此改走 ContentProvider：配置写进模块私有的 config.txt，通过 Binder 只读暴露给目标进程，
 * 不受文件权限限制，且可在应用设置后立刻推送（目标进程注册 ContentObserver）。
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

    /** 关闭，完全原生行为 */
    public static final int MODE_OFF = 0;
    /** 把所有调用固定到内置隐藏槽位（改写 scheme id） */
    public static final int MODE_SLOT = 1;
    /** 完全自定义映射表（全槽生效；不使用 slot，未命中直接返回空） */
    public static final int MODE_CUSTOM = 2;

    private static final String HEADER_TABLE = "---table---";

    public int mode = MODE_OFF;
    public int slot = 2;
    public String table = "";
    /**
     * 重启信号。每次请求重启写入一个新值；目标进程发现值变化后自行退出
     * （自杀不需要 root，也不依赖 su）。
     */
    public String exit = "";
    /** 详细诊断日志：默认关闭，用户在设置页手动开启后才记录按键级事件。 */
    public boolean diag = false;
    /** 二级开关：详细日志开启时，额外记录展开结果的拼音内容。 */
    public boolean diagContent = false;

    private ModuleConfig() {
    }

    /** 目标进程侧：从模块的 ContentProvider 读取配置；失败时回落默认值（等于不干预）。 */
    public static ModuleConfig loadFromHookSide(Context ctx) {
        ModuleConfig c = new ModuleConfig();
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(CONFIG_URI);
            if (in == null) return c;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            c.parse(bos.toString("UTF-8"));
        } catch (Throwable t) {
            Log.w("ImeHook", "read config via provider failed: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return c;
    }

    /** 模块 App 侧：读取当前配置（走同一个通道，保证 UI 与目标进程看到的是同一份）。 */
    public static ModuleConfig load(Context ctx) {
        return loadFromHookSide(ctx);
    }

    /** 目标进程侧：从模块的 ContentProvider 读取偏移表；失败时返回空串（native 回落内置兜底表）。 */
    public static String loadTargets(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(TARGETS_URI);
            if (in == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            Log.w("ImeHook", "read targets via provider failed: " + t);
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 模块 APK 路径。目标进程受包可见性限制，PackageManager 查不到 lpt.imehook，
     * 因此由 Provider（运行在模块进程里）直接自报 sourceDir。
     */
    public static String moduleApkPath(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(APK_URI, null, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable t) {
            Log.w("ImeHook", "query module apk failed: " + t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 写入并通知目标进程；目标进程若在运行会立刻重建配置。 */
    public void publish(Context ctx) {
        try {
            File f = configFile(ctx);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(serialize().getBytes("UTF-8"));
            }
            ctx.getContentResolver().notifyChange(CONFIG_URI, null);
        } catch (Throwable t) {
            Log.e("ImeHook", "publish config failed: " + t);
        }
    }

    static File configFile(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static File targetsFile(Context ctx) {
        return new File(ctx.getFilesDir(), TARGETS_FILE_NAME);
    }

    private String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(mode).append('\n');
        sb.append("slot=").append(slot).append('\n');
        sb.append("diag=").append(diag ? 1 : 0).append('\n');
        sb.append("diagc=").append(diagContent ? 1 : 0).append('\n');
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
                case "diag":
                    diag = parseInt(v, 0) == 1;
                    break;
                case "diagc":
                    diagContent = parseInt(v, 0) == 1;
                    break;
                case "exit":
                    exit = v;
                    break;
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

    /** 目标进程侧轮询用：配置文件的版本号（修改时间），变化即表示用户改了设置。 */
    public static long configVersion(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CONFIG_URI, null, null, null, null);
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /** 供 Provider 返回版本号（配置与偏移表任一文件的修改时间，取较大者）。 */
    static long versionOf(Context ctx) {
        long cfg = configFile(ctx).lastModified();
        long tgt = targetsFile(ctx).lastModified();
        return Math.max(cfg, tgt);
    }
}
