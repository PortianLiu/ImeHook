package lpt.imehook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读配置通道：把模块私有的 config.txt 通过 Binder 暴露给目标进程。
 * 目标进程与模块 uid 不同，无法直接读模块的数据目录，而 ContentProvider 不受文件权限限制。
 * 只实现读取：openFile 读配置正文，query 返回版本号（文件修改时间）。
 */
public final class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f;
        if ("targets".equals(uri.getLastPathSegment())) {
            f = ModuleConfig.targetsFile(getContext());
        } else {
            f = ModuleConfig.configFile(getContext());
        }
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if ("apk".equals(uri.getLastPathSegment())) {
            MatrixCursor apk = new MatrixCursor(new String[]{"path"});
            Context ctx = getContext();
            if (ctx != null) apk.addRow(new Object[]{ctx.getApplicationInfo().sourceDir});
            return apk;
        }
        MatrixCursor c = new MatrixCursor(new String[]{"version"});
        c.addRow(new Object[]{ModuleConfig.versionOf(getContext())});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
