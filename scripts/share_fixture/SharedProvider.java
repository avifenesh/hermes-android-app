package dev.avifenesh.hermes.sharefixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** A single non-sensitive file, private to a separate test app until explicitly granted. */
public class SharedProvider extends ContentProvider {
    private File fixture;
    @Override public boolean onCreate() {
        fixture = new File(getContext().getCacheDir(), "shared.txt");
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            out.write("Shared from a separate test UID.\n".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return true;
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) {
        String[] names = columns != null ? columns : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor result = new MatrixCursor(names);
        Object[] row = new Object[names.length];
        for (int i = 0; i < names.length; i++) row[i] = OpenableColumns.SIZE.equals(names[i]) ? fixture.length() : "shared.txt";
        result.addRow(row);
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !"/shared.txt".equals(uri.getPath())) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
