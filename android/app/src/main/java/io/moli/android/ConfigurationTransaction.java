package io.moli.android;

import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Stopped-engine transaction. A durable undo journal precedes all replacements.
 * A crash before journal removal restores the complete old generation on next access.
 * Only configuration files are allowed; keys, vault and approvals never enter here. */
final class ConfigurationTransaction {
    static final int FILE_LIMIT = 524288, JOURNAL_LIMIT = 4194304;
    static final String JOURNAL = "android-configuration.undo.json";
    static final Set<String> FILES = Set.of("moli.toml", "home.json", "android-concordance.json", "android-concordance.legacy.json", "android-demo.json");
    interface IO {
        String read(String name, int limit) throws Exception;
        void write(String name, String text) throws Exception;
        void remove(String name) throws Exception;
    }
    static class Disk implements IO {
        final File dir;
        Disk(File dir) { this.dir = dir; }
        void syncDirectory() throws Exception {
            try (FileChannel channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) { channel.force(true); }
        }
        public String read(String name, int limit) throws Exception {
            Path path = new File(dir, name).toPath();
            java.nio.file.attribute.BasicFileAttributes attributes;
            try { attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) { return null; }
            if (!attributes.isRegularFile() || attributes.size() > limit) throw new IOException("configuration_storage");
            try (InputStream in = Files.newInputStream(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("configuration_storage"); out.write(buffer, 0, n); }
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
            }
        }
        public void write(String name, String text) throws Exception {
            Path target = new File(dir, name).toPath(), temporary = new File(dir, name + ".prepare-new").toPath();
            if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary)) throw new IOException("configuration_storage");
            try (FileOutputStream out = new FileOutputStream(temporary.toFile())) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
        }
        public void remove(String name) throws Exception { Files.deleteIfExists(new File(dir, name).toPath()); syncDirectory(); }
    }
    private final IO io;
    ConfigurationTransaction(IO io) { this.io = io; }
    private static Set<String> keys(JSONObject object) {
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = object.keys(); it.hasNext();) keys.add(it.next());
        return keys;
    }
    private static String value(JSONObject object, String key) throws Exception {
        Object v = object.get(key);
        if (v == JSONObject.NULL) return null;
        if (!(v instanceof String) || ((String)v).getBytes(StandardCharsets.UTF_8).length > FILE_LIMIT) throw new IOException("configuration_storage");
        return (String)v;
    }
    void recover() throws Exception { synchronized (ConfigurationTransaction.class) { recoverLocked(); } }
    private void recoverLocked() throws Exception {
        String text = io.read(JOURNAL, JOURNAL_LIMIT);
        if (text == null) return;
        JSONObject journal = new JSONObject(text), old = journal.getJSONObject("old"), next = journal.getJSONObject("next");
        if (journal.getInt("version") != 1 || !keys(old).equals(keys(next)) || !FILES.containsAll(keys(old)) || old.length() == 0) throw new IOException("configuration_storage");
        // Validate the complete journal before writing even the first file.
        for (String name : keys(old)) { value(old, name); value(next, name); }
        // Do not overwrite unrelated edits made after a process interruption.
        for (String name : keys(old)) {
            String current = io.read(name, FILE_LIMIT);
            if (!Objects.equals(current, value(old, name)) && !Objects.equals(current, value(next, name))) throw new IOException("configuration_conflict");
        }
        for (String name : new TreeSet<>(keys(old))) {
            String before = value(old, name);
            if (before == null) io.remove(name); else io.write(name, before);
        }
        io.remove(JOURNAL);
    }
    void commit(Map<String,String> changes) throws Exception { synchronized (ConfigurationTransaction.class) { commitLocked(changes); } }
    private void commitLocked(Map<String,String> changes) throws Exception {
        recover();
        if (!FILES.containsAll(changes.keySet())) throw new IOException("configuration_storage");
        JSONObject old = new JSONObject(), next = new JSONObject();
        for (Map.Entry<String,String> entry : changes.entrySet()) {
            String text = entry.getValue();
            if (text == null || text.getBytes(StandardCharsets.UTF_8).length > FILE_LIMIT) throw new IOException("configuration_storage");
            String before = io.read(entry.getKey(), FILE_LIMIT);
            if (Objects.equals(before, text)) continue;
            old.put(entry.getKey(), before == null ? JSONObject.NULL : before); next.put(entry.getKey(), text);
        }
        if (old.length() == 0) return;
        String journal = new JSONObject().put("version", 1).put("old", old).put("next", next).toString();
        if (journal.getBytes(StandardCharsets.UTF_8).length > JOURNAL_LIMIT) throw new IOException("configuration_storage");
        io.write(JOURNAL, journal);
        try {
            for (String name : new TreeSet<>(keys(next))) io.write(name, next.getString(name));
            io.remove(JOURNAL);
        } catch (Exception failure) {
            try { recover(); } catch (Exception pending) { /* Durable journal retained; next access must recover or stop. */ }
            throw new IOException("configuration_storage");
        }
    }
}
