package lpt.imehook;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 诊断日志落盘：按天一个文件，保留近 7 天，只写模块 App 私有目录。
 *
 * 目标进程与模块 uid 不同，由目标进程经 ContentProvider 推过来，写盘发生在模块进程。
 */
public final class LogSink {

    private static final String DIR = "logs";
    private static final String PREFIX = "imehook-";
    private static final String SUFFIX = ".log";
    private static final int KEEP_DAYS = 7;
    private static final Object LOCK = new Object();

    private static final ThreadLocal<SimpleDateFormat> DAY_FMT =
            ThreadLocal.withInitial(() -> {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                f.setTimeZone(TimeZone.getDefault());
                return f;
            });
    private static final ThreadLocal<SimpleDateFormat> TIME_FMT =
            ThreadLocal.withInitial(() -> {
                SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                f.setTimeZone(TimeZone.getDefault());
                return f;
            });

    private LogSink() {
    }

    public static String today() {
        return DAY_FMT.get().format(new Date());
    }

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR);
    }

    static File fileForDay(Context ctx, String day) {
        return new File(dir(ctx), PREFIX + day + SUFFIX);
    }

    /** 追加日志；每行前加本地墙钟时间。超过 7 天的文件在写入时清掉。 */
    public static void append(Context ctx, String lines) {
        if (lines == null || lines.isEmpty()) return;
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                purgeLocked(ctx);
                File f = fileForDay(ctx, today());
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                String stamped = stampLines(lines);
                out = new FileOutputStream(f, true);
                out.write(stamped.getBytes(StandardCharsets.UTF_8));
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

    /** 近 7 天内已有日志的日期，新→旧。 */
    public static List<String> listDays(Context ctx) {
        synchronized (LOCK) {
            purgeLocked(ctx);
            File d = dir(ctx);
            File[] files = d.listFiles();
            if (files == null || files.length == 0) return Collections.emptyList();
            List<String> days = new ArrayList<>();
            for (File f : files) {
                String name = f.getName();
                if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) continue;
                String day = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
                if (day.length() == 10) days.add(day);
            }
            Collections.sort(days, Collections.reverseOrder());
            return days;
        }
    }

    /** 读某一天的完整内容；不存在则空串。 */
    public static String readDay(Context ctx, String day) {
        if (day == null || day.isEmpty()) return "";
        synchronized (LOCK) {
            File f = fileForDay(ctx, day);
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /** 兼容旧调用：读今天日志的尾部。 */
    public static String tail(Context ctx, int maxBytes) {
        String all = readDay(ctx, today());
        if (all.length() <= maxBytes) return all;
        return all.substring(all.length() - maxBytes);
    }

    public static void clear(Context ctx) {
        synchronized (LOCK) {
            File d = dir(ctx);
            File[] files = d.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.getName().startsWith(PREFIX) && f.getName().endsWith(SUFFIX)) {
                    f.delete();
                }
            }
            // 顺带清掉旧版单文件滚动残留
            new File(d, "imehook.log").delete();
            new File(d, "imehook.log.1").delete();
        }
    }

    private static String stampLines(String lines) {
        String ts = TIME_FMT.get().format(new Date());
        StringBuilder sb = new StringBuilder(lines.length() + 16);
        int start = 0;
        while (start < lines.length()) {
            int nl = lines.indexOf('\n', start);
            int end = nl < 0 ? lines.length() : nl;
            if (end > start) {
                sb.append(ts).append(' ');
                sb.append(lines, start, end);
                sb.append('\n');
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return sb.toString();
    }

    private static void purgeLocked(Context ctx) {
        File d = dir(ctx);
        if (!d.isDirectory()) return;
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1));
        String oldestKeep = DAY_FMT.get().format(cal.getTime());
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) continue;
            String day = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
            if (day.compareTo(oldestKeep) < 0) f.delete();
        }
        // 旧版单文件
        new File(d, "imehook.log").delete();
        new File(d, "imehook.log.1").delete();
    }
}
