package io.github.gamesbyian.chunks.external;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Private fixture provider in the test APK. Callers need an explicit Android URI grant. */
public final class ExternalTorrentTestProvider extends ContentProvider {
    static final String MIME_TYPE = "application/octet-stream";
    private static final String SUFFIX = ".external-torrent-proof";

    public static Uri uri(Context testApp, String slot) {
        return new Uri.Builder().scheme("content").authority(testApp.getPackageName() + SUFFIX)
                .appendPath("opaque").appendPath(slot).build();
    }

    static File file(Context testApp, String slot) {
        return new File(testApp.getFilesDir(), "external-" + slot + ".torrent");
    }

    public static byte[] torrent(String name) {
        return ("d8:announce" + bencodeString("https://tracker.example/announce")
                + "4:infod6:lengthi5e4:name" + bencodeString(name)
                + "12:piece lengthi16384e6:pieces20:01234567890123456789ee")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String bencodeString(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File source(Uri uri) {
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !"opaque".equals(path.get(0))) {
            return null;
        }
        String slot = path.get(1);
        if (!"first".equals(slot) && !"second".equals(slot)) {
            return null;
        }
        File source = file(getContext(), slot);
        return source.isFile() ? source : null;
    }

    @Override
    public String getType(Uri uri) {
        return source(uri) == null ? null : MIME_TYPE;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file = source(uri);
        if (file == null) {
            return null;
        }
        String[] fields = projection != null ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(fields, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String field : fields) {
            switch (field) {
                case OpenableColumns.DISPLAY_NAME:
                    row.add("external.torrent");
                    break;
                case OpenableColumns.SIZE:
                    row.add(file.length());
                    break;
                default:
                    row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Test source is read-only");
        }
        File file = source(uri);
        if (file == null) {
            throw new FileNotFoundException("Missing test document");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("No source mutation");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No source mutation");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No source mutation");
    }
}
