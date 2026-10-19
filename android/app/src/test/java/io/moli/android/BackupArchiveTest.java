package io.moli.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public final class BackupArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "synthetic recovery words only".toCharArray();
    private JSONObject credentials() throws Exception {
        return new JSONObject().put("master", "ab".repeat(32)).put("pin", "4826");
    }
    private File data() throws Exception {
        File dir = temp.newFolder();
        Files.write(new File(dir, "moli.toml").toPath(), "[server]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return dir;
    }
    private byte[] export(File dir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); BackupArchive.exportTo(dir, credentials(), PASSWORD, out); return out.toByteArray();
    }
    private void rejected(byte[] archive, char[] password) throws Exception {
        try { BackupArchive.importTo(new ByteArrayInputStream(archive), password, temp.newFolder()); fail("Archive must be rejected"); }
        catch (IOException | org.json.JSONException expected) { /* Expected rejection, never print credentials. */ }
    }
    @Test public void roundTripIncludesNestedUnknownFilesAndSpansAuthenticatedRecords() throws Exception {
        File source = data(); File nested = new File(source, "recordings"); assertTrue(nested.mkdir());
        byte[] bytes = new byte[200000]; new Random(17).nextBytes(bytes);
        Files.write(new File(nested, "synthetic.bin").toPath(), bytes);
        Files.write(new File(source, "settings.json").toPath(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] archive = export(source);
        assertFalse(new String(archive, java.nio.charset.StandardCharsets.ISO_8859_1).contains(credentials().getString("master")));
        File target = temp.newFolder(); JSONObject recovered = BackupArchive.importTo(new ByteArrayInputStream(archive), PASSWORD, target);
        assertEquals(credentials().getString("master"), recovered.getString("master"));
        assertArrayEquals(bytes, Files.readAllBytes(new File(target, "recordings/synthetic.bin").toPath()));
        assertTrue(new File(target, "settings.json").isFile());
    }
    @Test public void wrongPasswordIsRejected() throws Exception { rejected(export(data()), "wrong recovery password".toCharArray()); }
    @Test public void corruptedCiphertextIsRejected() throws Exception { byte[] b=export(data()); b[60]^=1; rejected(b,PASSWORD); }
    @Test public void missingFinalRecordIsRejected() throws Exception { byte[] b=export(data()); rejected(Arrays.copyOf(b,b.length-20),PASSWORD); }
    @Test public void trailingBytesAreRejected() throws Exception { byte[] b=export(data()); rejected(Arrays.copyOf(b,b.length+1),PASSWORD); }
    @Test public void unsupportedEnvelopeVersionIsRejected() throws Exception { byte[] b=export(data()); b[7]='2'; rejected(b,PASSWORD); }
    @Test public void plaintextKeysAndSymlinksAreRefused() throws Exception {
        File dir=data(); Files.write(new File(dir,"master.key").toPath(),"synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try { export(dir); fail(); } catch (IOException expected) { }
        Files.delete(new File(dir,"master.key").toPath());
        Files.createSymbolicLink(new File(dir,"link").toPath(),new File(dir,"moli.toml").toPath());
        try { export(dir); fail(); } catch (IOException expected) { }
    }
    @Test public void authenticatedInvalidInventoryIsRefused() throws Exception {
        for (String name : new String[]{"../escape", "/escape", "x/../../escape", "master.key", "x\\escape"}) {
            JSONObject manifest = new JSONObject().put("format",1).put("engine","0.1.0").put("recovery",credentials())
                    .put("files",new JSONObject().put("moli.toml",new JSONObject().put("size",0).put("sha256","00".repeat(32)))
                    .put(name,new JSONObject().put("size",0).put("sha256","00".repeat(32))));
            rejected(seal(manifest), PASSWORD);
        }
    }
    @Test public void versionMissingFilesAndDigestMismatchAreRefused() throws Exception {
        JSONObject manifest = new JSONObject().put("format",1).put("engine","99.0.0").put("recovery",credentials())
                .put("files",new JSONObject().put("moli.toml",new JSONObject().put("size",0).put("sha256","00".repeat(32))));
        rejected(seal(manifest),PASSWORD);
        manifest.put("engine","0.1.0"); rejected(seal(manifest),PASSWORD); // missing payload
        rejected(seal(manifest,"data/moli.toml"),PASSWORD); // wrong hash
        rejected(seal(manifest,"data/moli.toml","data/extra"),PASSWORD); // undeclared payload
        manifest.getJSONObject("files").getJSONObject("moli.toml").put("size",BackupArchive.MAX_BYTES+1);
        rejected(seal(manifest),PASSWORD);
    }
    /** Build authenticated hostile archives without exposing a production test-only API. */
    private byte[] seal(JSONObject manifest, String... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Class<?> type=Class.forName("io.moli.android.BackupArchive$SealedOutput");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(OutputStream.class,char[].class); constructor.setAccessible(true);
        try (ZipOutputStream zip = new ZipOutputStream((OutputStream) constructor.newInstance(out,PASSWORD))) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            for (String name:entries) { zip.putNextEntry(new ZipEntry(name)); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
}
