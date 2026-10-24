package io.moli.android;

import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Portable v1: authenticated bounded records containing a ZIP; no plaintext key file. */
final class BackupArchive {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int CHUNK = 65536, ITERATIONS = 600000, MAX_FILES = 10000;
    private static final byte[] MAGIC = "MOLIBAK1".getBytes(StandardCharsets.US_ASCII);
    private BackupArchive() {}

    static void exportTo(File data, JSONObject credentials, char[] password, OutputStream output) throws Exception {
        ProtectedKey.validate(credentials);
        if (!new File(data, "moli.toml").isFile()) throw new IOException("No initialized data");
        Map<String, File> files = new TreeMap<>();
        inventory(data, data, files, 0);
        JSONObject entries = new JSONObject();
        long total = 0;
        for (Map.Entry<String, File> entry : files.entrySet()) {
            File f = entry.getValue(); total += f.length();
            if (total > MAX_BYTES) throw new IOException("Backup too large");
            entries.put(entry.getKey(), new JSONObject().put("size", f.length()).put("sha256", hash(f)));
        }
        // Deliberately export only portable credentials, never the local generation path.
        JSONObject recovery = new JSONObject().put("master", credentials.getString("master")).put("pin", credentials.getString("pin"));
        if(credentials.has("format")) recovery.put("format",credentials.getInt("format"));
        JSONObject manifest = new JSONObject().put("format", 1).put("engine", "0.1.0")
                .put("files", entries).put("recovery", recovery);
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (manifestBytes.length > 4 * 1024 * 1024) throw new IOException("Manifest too large");
        try (ZipOutputStream zip = new ZipOutputStream(new SealedOutput(output, password))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifestBytes); zip.closeEntry();
            byte[] buffer = new byte[CHUNK];
            for (Map.Entry<String, File> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("data/" + entry.getKey()));
                try (InputStream input = new FileInputStream(entry.getValue())) {
                    int n; while ((n = input.read(buffer)) != -1) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
        }
    }
    /** Destination is a newly created private staging directory, never the active tree. */
    static JSONObject importTo(InputStream input, char[] password, File staging) throws Exception {
        if (!staging.isDirectory() || Objects.requireNonNull(staging.list()).length != 0)
            throw new IOException("Restore needs empty staging");
        try (SealedInput sealed = new SealedInput(input, password); ZipInputStream zip = new ZipInputStream(sealed)) {
            ZipEntry first = zip.getNextEntry();
            if (first == null || !first.getName().equals("manifest.json")) throw new IOException("Missing manifest");
            JSONObject manifest = new JSONObject(new String(readBounded(zip, 4 * 1024 * 1024), StandardCharsets.UTF_8));
            if (manifest.getInt("format") != 1 || !manifest.getString("engine").equals("0.1.0"))
                throw new IOException("Incompatible backup");
            JSONObject credentials = manifest.getJSONObject("recovery"); ProtectedKey.validate(credentials);
            JSONObject inventory = manifest.getJSONObject("files");
            if (inventory.length() == 0 || inventory.length() > MAX_FILES || !inventory.has("moli.toml"))
                throw new IOException("Invalid inventory");
            Set<String> remaining = new HashSet<>();
            long expected = 0;
            for (Iterator<String> it = inventory.keys(); it.hasNext();) {
                String name = it.next(); validName(name); remaining.add(name);
                JSONObject info = inventory.getJSONObject(name); long size = info.getLong("size");
                if (size < 0 || size > MAX_BYTES || !info.getString("sha256").matches("[a-f0-9]{64}"))
                    throw new IOException("Invalid inventory entry");
                expected += size;
                if (expected > MAX_BYTES) throw new IOException("Restore too large");
            }
            ZipEntry entry; byte[] buffer = new byte[CHUNK];
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                if (entry.isDirectory() || !path.startsWith("data/")) throw new IOException("Unexpected entry");
                String name = path.substring(5); validName(name);
                if (!remaining.remove(name)) throw new IOException("Unknown or duplicate entry");
                JSONObject info = inventory.getJSONObject(name);
                File target = new File(staging, name);
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Cannot create directory");
                long size = 0; MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        size += n; if (size > info.getLong("size")) throw new IOException("Expanded entry too large");
                        digest.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                    out.getFD().sync();
                }
                if (size != info.getLong("size") || !hex(digest.digest()).equals(info.getString("sha256")))
                    throw new IOException("File integrity mismatch");
            }
            if (!remaining.isEmpty()) throw new IOException("Missing files");
            // ZipInputStream stops at the central directory. Authenticate all remaining records,
            // including the mandatory final record, before the caller may commit anything.
            while (sealed.read(buffer) != -1) { /* authenticated discard */ }
            return credentials;
        }
    }
    private static void inventory(File root, File dir, Map<String, File> files, int depth) throws Exception {
        if (depth > 12) throw new IOException("Tree too deep");
        File[] children = dir.listFiles(); if (children == null) throw new IOException("Cannot read data");
        for (File file : children) {
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("Symlinks are not portable");
            if (file.isDirectory()) inventory(root, file, files, depth + 1);
            else if (file.isFile()) {
                String name = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                validName(name); files.put(name, file);
                if (files.size() > MAX_FILES) throw new IOException("Too many files");
            } else throw new IOException("Unsupported data file");
        }
    }
    private static void validName(String name) throws IOException {
        if (name.isEmpty() || name.length() > 1024 || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("Unsafe path");
        String[] parts = name.split("/", -1);
        if (parts.length > 13) throw new IOException("Path too deep");
        for (String part : parts)
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals("master.key"))
                throw new IOException("Unsafe or plaintext key path");
    }
    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException("Manifest too large"); out.write(b, 0, n); }
        return out.toByteArray();
    }
    private static String hash(File f) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[CHUNK];
        try (InputStream in = new FileInputStream(f)) { int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        return hex(digest.digest());
    }
    private static String hex(byte[] b) {
        StringBuilder out = new StringBuilder(); for (byte value : b) out.append(String.format(Locale.ROOT, "%02x", value & 255)); return out.toString();
    }
    private static SecretKey derive(char[] password, byte[] header) throws Exception {
        if (password.length < 16 || password.length > 1024) throw new IOException("Use a strong recovery password");
        PBEKeySpec spec = new PBEKeySpec(password, Arrays.copyOfRange(header, 8, 24), ITERATIONS, 256);
        byte[] bytes = null;
        try {
            bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(bytes, "AES");
        } finally { spec.clearPassword(); if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
    private static byte[] crypt(int mode, SecretKey key, byte[] header, int seq, int length, byte[] data) throws Exception {
        byte[] nonce = ByteBuffer.allocate(12).put(header, 24, 8).putInt(seq).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header); cipher.updateAAD(ByteBuffer.allocate(8).putInt(seq).putInt(length).array());
        return cipher.doFinal(data);
    }
    private static final class SealedOutput extends OutputStream {
        private final DataOutputStream out;
        private final byte[] header = new byte[32], pending = new byte[CHUNK];
        private final SecretKey key; private int used, seq; private long total; private boolean closed;
        SealedOutput(OutputStream output, char[] password) throws Exception {
            out = new DataOutputStream(output); new SecureRandom().nextBytes(header); System.arraycopy(MAGIC, 0, header, 0, 8);
            key = derive(password, header); out.write(header);
        }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int count = Math.min(len, CHUNK - used); System.arraycopy(b, off, pending, used, count);
                used += count; off += count; len -= count;
                if (used == CHUNK) record(used);
            }
        }
        private void record(int length) throws IOException {
            total += length; if (total > MAX_BYTES + 8 * 1024 * 1024) throw new IOException("Archive too large");
            try { out.writeInt(length); out.write(crypt(Cipher.ENCRYPT_MODE, key, header, seq++, length, Arrays.copyOf(pending, length))); used = 0; }
            catch (GeneralSecurityException e) { throw new IOException("Encryption failed"); }
            catch (Exception e) { throw new IOException("Encryption failed"); }
        }
        @Override public void close() throws IOException {
            if (closed) return; closed = true;
            try { if (used > 0) record(used); record(0); out.flush(); }
            finally { Arrays.fill(pending, (byte)0); out.close(); }
        }
    }
    private static final class SealedInput extends InputStream {
        private final DataInputStream in; private final byte[] header = new byte[32]; private final SecretKey key;
        private byte[] plain = new byte[0]; private int pos, seq; private long total; private boolean ended;
        SealedInput(InputStream input, char[] password) throws Exception {
            in = new DataInputStream(input); in.readFully(header);
            if (!Arrays.equals(MAGIC, Arrays.copyOf(header, 8))) throw new IOException("Unsupported archive version");
            key = derive(password, header);
        }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) == -1 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (pos == plain.length) {
                if (ended) return -1;
                if (seq >= 8321) throw new IOException("Too many encrypted records");
                int length = in.readInt();
                if (length < 0 || length > CHUNK) throw new IOException("Invalid record length");
                total += length; if (total > MAX_BYTES + 8 * 1024 * 1024) throw new IOException("Archive too large");
                byte[] ciphertext = new byte[length + 16]; in.readFully(ciphertext);
                try { Arrays.fill(plain, (byte)0); plain = crypt(Cipher.DECRYPT_MODE, key, header, seq++, length, ciphertext); }
                catch (Exception e) { throw new IOException("Password or archive integrity failure"); }
                pos = 0;
                if (length == 0) { ended = true; if (in.read() != -1) throw new IOException("Trailing data"); return -1; }
            }
            int count = Math.min(len, plain.length - pos); System.arraycopy(plain, pos, b, off, count); pos += count; return count;
        }
        @Override public void close() throws IOException { Arrays.fill(plain, (byte)0); in.close(); }
    }
}
