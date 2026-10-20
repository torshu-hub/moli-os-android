package io.moli.android;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** A single synthetic document for the real ACTION_OPEN_DOCUMENT result path. */
public final class SyntheticDocumentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/toml"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        c.addRow(new Object[]{"synthetic-temperature.toml"}); return c;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !"/profile".equals(uri.getPath())) throw new FileNotFoundException();
        File document = new File(getContext().getCacheDir(),"synthetic-document.toml");
        try (InputStream in = getContext().getAssets().open("synthetic-temperature.toml"); FileOutputStream out = new FileOutputStream(document)) {
            byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer,0,n);
        } catch (IOException failure) { throw new FileNotFoundException("synthetic fixture unavailable"); }
        return ParcelFileDescriptor.open(document,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
