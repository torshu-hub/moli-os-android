package io.moli.android;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

final class BackupStorage {
    private BackupStorage() {}
    static void exportTo(Context context, char[] password, OutputStream output) throws Exception {
        JSONObject keys = ProtectedKey.open(context);
        BackupArchive.exportTo(ProfileStore.data(context), keys, password, output);
    }
    static void restoreFresh(Context context, InputStream input, char[] password) throws Exception {
        File legacy = new File(context.getFilesDir(), "moli");
        if (ProtectedKey.exists(context) || (legacy.exists() && (!legacy.isDirectory() || legacy.list() == null || legacy.list().length != 0)))
            throw new IOException("Restoration requires a fresh installation");
        // Staging and active directories share a filesystem. Only the wrapped record activates it.
        File staging = new File(context.getFilesDir(), "restored-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Cannot stage restore");
        boolean committed = false;
        try {
            JSONObject keys = BackupArchive.importTo(input, password, staging);
            keys.put("directory", staging.getName());
            // Reopening the Activity after recovery must not silently activate drivers.
            if (!context.getSharedPreferences("host", Context.MODE_PRIVATE).edit().putBoolean("wanted", false).commit())
                throw new IOException("Cannot persist stopped state");
            ProtectedKey.install(context, keys);
            committed = true;
        } finally {
            if (!committed) discard(staging);
        }
    }
    /** Only disposable staging trees owned by this operation; never an active data tree. */
    private static void discard(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot clean staging");
            for (File child : children) discard(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
