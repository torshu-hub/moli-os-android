package io.moli.android;

import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public final class ConfigurationTransactionTest {
    static final class Crash extends Error {}
    static final class Faults extends ConfigurationTransaction.Disk {
        int calls, failAt = -1; boolean crash, exhausted;
        Faults(File dir) { super(dir); }
        void after() throws IOException {
            if (++calls == failAt) { if (crash) throw new Crash(); exhausted = true; throw new IOException("disk full"); }
        }
        @Override public void write(String name, String text) throws Exception {
            if (exhausted) throw new IOException("disk full");
            super.write(name, text); after();
        }
        @Override public void remove(String name) throws Exception {
            if (exhausted) throw new IOException("disk full");
            super.remove(name); after();
        }
    }
    static Map<String,String> next() {
        Map<String,String> next = new LinkedHashMap<>();
        next.put("moli.toml", "[[driver]]\nid='external'\nkind='helpers'\n");
        next.put("home.json", "{\"rooms\":[{\"name\":\"Fixture\",\"protected\":true}]}");
        next.put("android-concordance.json", "{\"version\":1,\"drivers\":{},\"home\":{}}");
        next.put("android-concordance.legacy.json", "{\"version\":1,\"config\":\"legacy\"}");
        next.put("android-demo.json", "{\"fixture\":true}");
        return next;
    }
    static void writeText(Path path, String text) throws Exception { Files.write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    static String readText(Path path) throws Exception { return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8); }
    static Path fixture() throws Exception {
        Path dir = Files.createTempDirectory("moli-concordance-test-");
        writeText(dir.resolve("moli.toml"), "legacy TOML");
        writeText(dir.resolve("home.json"), "{\"rooms\":[{\"name\":\"Legacy\"}]}");
        writeText(dir.resolve("android-integrations.json"), "{\"approved\":true}");
        writeText(dir.resolve("trusted-profiles.toml"), "# exact-byte approval fixture");
        Files.write(dir.resolve("secrets.enc"), new byte[]{0,1,2,3,4});
        Files.write(dir.resolve("history.sqlite"), new byte[]{9,8,7,6});
        return dir;
    }
    static void untouched(Path dir) throws Exception {
        assertEquals("{\"approved\":true}", readText(dir.resolve("android-integrations.json")));
        assertEquals("# exact-byte approval fixture", readText(dir.resolve("trusted-profiles.toml")));
        assertArrayEquals(new byte[]{0,1,2,3,4}, Files.readAllBytes(dir.resolve("secrets.enc")));
        assertArrayEquals(new byte[]{9,8,7,6}, Files.readAllBytes(dir.resolve("history.sqlite")));
    }
    static void cleanup(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    @Test public void processLossAtEveryReplacementRecoversOneCompleteGeneration() throws Exception {
        // Journal + five replacements + commit-marker removal. Fresh process after each crash.
        for (int step = 1; step <= 7; step++) {
            Path dir = fixture();
            try {
                Faults disk = new Faults(dir.toFile()); disk.failAt = step; disk.crash = true;
                try { new ConfigurationTransaction(disk).commit(next()); fail("injected crash"); } catch (Crash expected) {}
                ConfigurationTransaction.Disk restarted = new ConfigurationTransaction.Disk(dir.toFile());
                ConfigurationTransaction transaction = new ConfigurationTransaction(restarted);
                transaction.recover(); transaction.recover(); // Interrupted migration is reversible and recovery idempotent.
                if (step == 7) {
                    for (var entry : next().entrySet()) assertEquals(entry.getValue(), restarted.read(entry.getKey(), 524288));
                } else {
                    assertEquals("legacy TOML", readText(dir.resolve("moli.toml")));
                    assertEquals("{\"rooms\":[{\"name\":\"Legacy\"}]}", readText(dir.resolve("home.json")));
                    assertFalse(Files.exists(dir.resolve("android-concordance.json")));
                    assertFalse(Files.exists(dir.resolve("android-concordance.legacy.json")));
                    assertFalse(Files.exists(dir.resolve("android-demo.json")));
                }
                untouched(dir); assertFalse(Files.exists(dir.resolve(ConfigurationTransaction.JOURNAL)));
                transaction.commit(next()); transaction.commit(next());
                untouched(dir); assertFalse(Files.exists(dir.resolve(ConfigurationTransaction.JOURNAL)));
            } finally { cleanup(dir); }
        }
    }
    @Test public void preferencesAndHomeRecoverTogetherAtEveryNewCommitBoundary() throws Exception {
        Map<String,String> values=next();
        values.put("android-integrations.json","{\"demo\":false,\"retained\":{\"demo\":true}}");
        values.put("android-integrations.previous.json","{\"demo\":true}");
        values.put("android-equipment.json","{\"meross\":[],\"future\":42}");
        for(int step=1;step<=values.size()+2;step++) {
            Path dir=fixture();
            try {
                Faults disk=new Faults(dir.toFile()); disk.failAt=step; disk.crash=true;
                Map<String,String> before=new LinkedHashMap<>();for(String name:values.keySet())before.put(name,disk.read(name,524288));
                try {new ConfigurationTransaction(disk).commit(values);fail();}catch(Crash expected){}
                ConfigurationTransaction.Disk restarted=new ConfigurationTransaction.Disk(dir.toFile());
                new ConfigurationTransaction(restarted).recover();
                Map<String,String> expected=step==values.size()+2?values:before;
                for(String name:values.keySet())assertEquals(name,expected.get(name),restarted.read(name,524288));
                assertArrayEquals(new byte[]{0,1,2,3,4},Files.readAllBytes(dir.resolve("secrets.enc")));
            }finally{cleanup(dir);}
        }
    }
    @Test public void exhaustedStorageKeepsJournalUntilRecoveryCanRestoreOldFiles() throws Exception {
        Path dir = fixture();
        try {
            Faults disk = new Faults(dir.toFile()); disk.failAt = 4;
            try { new ConfigurationTransaction(disk).commit(next()); fail(); } catch (IOException expected) {}
            assertTrue(Files.exists(dir.resolve(ConfigurationTransaction.JOURNAL)));
            new ConfigurationTransaction(new ConfigurationTransaction.Disk(dir.toFile())).recover();
            assertEquals("legacy TOML", readText(dir.resolve("moli.toml")));
            assertFalse(Files.exists(dir.resolve("android-concordance.json"))); untouched(dir);
        } finally { cleanup(dir); }
    }
    @Test public void malformedJournalAndConcurrentEditBlockBeforeAnyReplacement() throws Exception {
        Path dir = fixture();
        try {
            Faults disk = new Faults(dir.toFile()); disk.failAt = 3; disk.crash = true;
            try { new ConfigurationTransaction(disk).commit(next()); } catch (Crash expected) {}
            byte[] home = Files.readAllBytes(dir.resolve("home.json"));
            writeText(dir.resolve("moli.toml"), "unrelated edit");
            try { new ConfigurationTransaction(new ConfigurationTransaction.Disk(dir.toFile())).recover(); fail(); } catch (IOException expected) {}
            assertEquals("unrelated edit", readText(dir.resolve("moli.toml")));
            assertArrayEquals(home, Files.readAllBytes(dir.resolve("home.json"))); untouched(dir);
            writeText(dir.resolve(ConfigurationTransaction.JOURNAL), "{broken");
            try { new ConfigurationTransaction(new ConfigurationTransaction.Disk(dir.toFile())).recover(); fail(); } catch (JSONException expected) {}
            assertEquals("unrelated edit", readText(dir.resolve("moli.toml")));
        } finally { cleanup(dir); }
    }
    @Test public void boundsInvalidUtf8AndPathsFailBeforeJournalOrReplacement() throws Exception {
        Path dir = fixture();
        try {
            ConfigurationTransaction.Disk disk = new ConfigurationTransaction.Disk(dir.toFile());
            ConfigurationTransaction transaction = new ConfigurationTransaction(disk);
            for (Map<String,String> bad : List.of(Map.of("../outside", "invalid"), Map.of("home.json", "x".repeat(524289)))) {
                try { transaction.commit(bad); fail(); } catch (IOException expected) {}
                assertFalse(Files.exists(dir.resolve(ConfigurationTransaction.JOURNAL)));
            }
            Files.write(dir.resolve("home.json"), new byte[]{(byte)0xc3, (byte)0x28});
            try { transaction.commit(next()); fail(); } catch (java.nio.charset.CharacterCodingException expected) {}
            assertEquals("legacy TOML", readText(dir.resolve("moli.toml"))); untouched(dir);
        } finally { cleanup(dir); }
    }
}
