package io.moli.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.Arrays;

/** Native SAF flow: no JavaScript bridge, storage permission or plaintext archive. */
public final class BackupActivity extends Activity {
    private static final int EXPORT = 31, IMPORT = 32;
    private char[] pending;
    private TextView status;
    private Button export, restore;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        TextView info = new TextView(this); info.setText(R.string.backup_info); root.addView(info);
        export = new Button(this); export.setText(R.string.backup_export); export.setOnClickListener(v -> password(EXPORT)); root.addView(export);
        restore = new Button(this); restore.setText(R.string.backup_import); restore.setOnClickListener(v -> password(IMPORT)); root.addView(restore);
        status = new TextView(this); root.addView(status); setContentView(root);
    }
    private void password(int operation) {
        if (EngineHost.state() != EngineHost.State.STOPPED) { status.setText(R.string.backup_stop_first); return; }
        if (operation == IMPORT && ProtectedKey.exists(this)) { status.setText(R.string.backup_fresh_only); return; }
        if (operation == EXPORT && !ProtectedKey.exists(this)) { status.setText(R.string.key_unavailable); return; }
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        EditText first = new EditText(this), second = new EditText(this);
        for (EditText field : new EditText[]{first, second}) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setSaveEnabled(false); field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        first.setHint(R.string.backup_password); fields.addView(first);
        if (operation == EXPORT) { second.setHint(R.string.backup_confirm); fields.addView(second); }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(operation == EXPORT ? R.string.backup_export : R.string.backup_import)
                .setMessage(R.string.backup_password_help).setView(fields).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = first.getText().toString();
            if (text.length() < 16 || text.length() > 1024 || (operation == EXPORT && !text.equals(second.getText().toString()))) {
                first.setError(getString(R.string.backup_password_invalid)); return;
            }
            wipe(); pending = text.toCharArray(); first.setText(""); second.setText(""); dialog.dismiss();
            Intent intent = new Intent(operation == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
            if (operation == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, "moli-recovery.molibak");
            try { startActivityForResult(intent, operation); }
            catch (ActivityNotFoundException failure) { wipe(); status.setText(R.string.backup_failed); }
        }));
        dialog.show();
    }
    @Override public void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null || pending == null) { wipe(); return; }
        char[] password = pending; pending = null;
        Context context = getApplicationContext(); android.net.Uri uri = intent.getData();
        export.setEnabled(false); restore.setEnabled(false); status.setText(R.string.backup_working);
        try {
            EngineHost.maintain(() -> {
                boolean success = false;
                try {
                    if (request == EXPORT) {
                        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new IOException("Missing document");
                            BackupStorage.exportTo(context, password, out);
                        }
                    } else if (request == IMPORT) {
                        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new IOException("Missing document");
                            BackupStorage.restoreFresh(context, in, password);
                        }
                    } else throw new IOException("Unexpected operation");
                    success = true;
                } catch (Exception | LinkageError failure) { /* Generic message only; no file/key/password logs. */ }
                finally { Arrays.fill(password, '\0'); }
                final boolean ok = success;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isDestroyed()) {
                        export.setEnabled(true); restore.setEnabled(true);
                        status.setText(ok ? R.string.backup_done : R.string.backup_failed);
                    }
                });
                return null;
            });
        } catch (IllegalStateException busy) {
            Arrays.fill(password, '\0'); export.setEnabled(true); restore.setEnabled(true); status.setText(R.string.backup_stop_first);
        }
    }
    private void wipe() { if (pending != null) { Arrays.fill(pending, '\0'); pending = null; } }
    @Override public void onDestroy() { wipe(); super.onDestroy(); }
}
