package com.nyroos.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

public final class UpdateFiles extends ContentProvider {
    static final String AUTHORITY = "com.nyroos.core.updates";
    static final String APK_MIME = "application/vnd.android.package-archive";
    private static final Pattern APK = Pattern.compile("(?:os\\.nyro\\.launcher|com\\.nyroos\\.core)-[0-9]+\\.apk");

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return file(uri) == null ? null : APK_MIME; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File file = file(uri);
        if (file != null) {
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                values[i] = switch (columns[i]) {
                    case OpenableColumns.DISPLAY_NAME -> file.getName();
                    case OpenableColumns.SIZE -> file.length();
                    default -> null;
                };
            }
            cursor.addRow(values);
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        if (!"r".equals(mode) || file == null) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !APK.matcher(name).matches()) return null;
        File file = new File(new File(getContext().getCacheDir(), "app-updates"), name);
        return file.isFile() ? file : null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
