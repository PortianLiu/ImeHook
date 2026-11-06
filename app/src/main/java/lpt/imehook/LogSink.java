package lpt.imehook;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 诊断日志落盘，只写模块 App 自己的私有目录。
 *
 * 目标进程与模块 uid 不同，读不到这个目录，所以日志由目标进程经 ContentProvider 推过来，
 * 读写都发生在模块进程里；目标进程的热路径不碰文件。
 */
public final class LogSink {

    private static final String DIR = "logs";
    private static final String NAME = "imehook.log";
    private static final long MAX_BYTES = 512 * 1024;
    private static final Object LOCK = new Object();

    private LogSink() {
    }

    public static File file(Context ctx) {
        return new File(new File(ctx.getFilesDir(), DIR), NAME);
    }

    /** 追加日志；超过上限时滚动为 imehook.log.1（只保留两份）。 */
    public static void append(Context ctx, String lines) {
        if (lines == null || lines.isEmpty()) return;
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                File f = file(ctx);
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                if (f.length() > MAX_BYTES) {
                    File old = new File(dir, NAME + ".1");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                out = new FileOutputStream(f, true);
                out.write(lines.getBytes(StandardCharsets.UTF_8));
                if (!lines.endsWith("\n")) out.write('\n');
            } catch (Throwable ignored) {
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /** 文件尾部内容，供设置页查看/导出。 */
    public static String tail(Context ctx, int maxBytes) {
        File f = file(ctx);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            long skip = Math.max(0, f.length() - maxBytes);
            if (skip > 0) in.skip(skip);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context ctx) {
        synchronized (LOCK) {
            File f = file(ctx);
            f.delete();
            new File(f.getParentFile(), NAME + ".1").delete();
        }
    }
}
