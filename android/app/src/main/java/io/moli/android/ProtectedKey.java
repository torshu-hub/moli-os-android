package io.moli.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore wraps the portable Moli key; the hardware key is never the Moli key. */
final class ProtectedKey {
    private static final String ALIAS = "moli.master.wrap.v1";
    private ProtectedKey() {}

    static boolean exists(Context context) {
        File base = file(context).getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists();
    }
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "moli-key.wrap"));
    }
    static String randomHex() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        StringBuilder text = new StringBuilder(64);
        for (byte b : value) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    private static KeyStore store() throws Exception {
        KeyStore result = KeyStore.getInstance("AndroidKeyStore");
        result.load(null);
        return result;
    }
    static synchronized void create(Context context, String pin) throws Exception {
        if (exists(context)) throw new IllegalStateException("Key already exists");
        File data = new File(context.getFilesDir(), "moli");
        // Missing wrapping material beside an existing vault must never rekey it.
        if (new File(data, "secrets.enc").exists()) throw new IllegalStateException("Existing vault");
        File[] generations = context.getFilesDir().listFiles((dir, name) -> name.startsWith("restored-"));
        if (generations == null) throw new IllegalStateException("Cannot inspect existing data");
        for (File generation : generations)
            if (new File(generation, "secrets.enc").exists()) throw new IllegalStateException("Existing recovered vault");
        install(context, new JSONObject().put("master", randomHex()).put("pin", pin));
    }
    static File dataDirectory(Context context, JSONObject keys) throws Exception {
        String directory = keys.optString("directory", "moli");
        if (!directory.equals("moli") && !directory.matches("restored-[a-f0-9-]{36}"))
            throw new IllegalStateException("Invalid data generation");
        return new File(context.getFilesDir(), directory);
    }
    /** Commit point: an atomic wrapped record selects both the data generation and its key. */
    static synchronized void install(Context context, JSONObject credentials) throws Exception {
        if (exists(context)) throw new IllegalStateException("Existing installation");
        validate(credentials);
        KeyStore store = store();
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        SecretKey wrapper = (SecretKey) store.getKey(ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrapper);
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(credentials.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer record = ByteBuffer.allocate(4 + iv.length + ciphertext.length);
        record.putInt(iv.length).put(iv).put(ciphertext);
        AtomicFile target = file(context);
        FileOutputStream output = target.startWrite();
        try { output.write(record.array()); target.finishWrite(output); }
        catch (Exception failure) { target.failWrite(output); throw failure; }
    }
    static void validate(JSONObject credentials) throws Exception {
        if (!credentials.getString("master").matches("[a-f0-9]{64}") ||
                !credentials.getString("pin").matches("[0-9]{4,32}"))
            throw new IllegalStateException("Invalid recovery credentials");
    }
    static synchronized JSONObject open(Context context) throws Exception {
        byte[] bytes = file(context).readFully();
        if (bytes.length < 32 || bytes.length > 4096) throw new IllegalStateException("Invalid wrapped key");
        ByteBuffer record = ByteBuffer.wrap(bytes);
        int count = record.getInt();
        if (count != 12 || record.remaining() < count + 16) throw new IllegalStateException("Invalid IV");
        byte[] iv = new byte[count]; record.get(iv);
        byte[] ciphertext = new byte[record.remaining()]; record.get(ciphertext);
        SecretKey wrapper = (SecretKey) store().getKey(ALIAS, null);
        if (wrapper == null) throw new IllegalStateException("Missing wrapping key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrapper, new GCMParameterSpec(128, iv));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        JSONObject credentials = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        validate(credentials);
        return credentials;
    }
}
