package com.weige.appstore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider：把下载好的 APK 以 content:// 暴露给系统安装器。
 * 手写而不是用 FileProvider，是为了不引入 androidx 依赖（无 gradle 构建）。
 */
public class ApkProvider extends ContentProvider {
    public static final String AUTH = "com.weige.appstore.apk";

    public static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTH)
                .appendPath(f.getName()).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File dir = new File(getContext().getCacheDir(), "apk");
        File f = new File(dir, uri.getLastPathSegment());
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
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
