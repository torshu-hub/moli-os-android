package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import static org.junit.Assert.*;

/** Independent synthetic UI fixtures only; never part of the shipped application. */
final class FreshEquipmentFixture {
    static void create(Context context) throws Exception {
        assertEquals("io.moli.android.app.preview.automatic.v0130", context.getPackageName());
        assertTrue("Disposable emulator only", android.os.Build.FINGERPRINT.contains("generic")
                || android.os.Build.MODEL.contains("sdk"));
        assertEquals(EngineHost.State.STOPPED, EngineHost.state());
        assertTrue(context.getSharedPreferences("host", Context.MODE_PRIVATE).edit()
                .putBoolean("wanted", false).commit());
        EngineHost.maintain(() -> {
            // save({}) intentionally retains inactive equipment in production.
            // Independent test classes need a genuinely fresh synthetic installation.
            // Preserve screenshots/weather reports consumed by the existing runner.
            File files = context.getFilesDir();
            File[] generations = files.listFiles(f -> f.isDirectory()
                    && (f.getName().equals("moli") || f.getName().startsWith("restored-")));
            assertNotNull(generations);
            for (File generation : generations) {
                assertTrue(generation.getCanonicalPath().startsWith(files.getCanonicalPath() + "/"));
                try (Stream<Path> paths = Files.walk(generation.toPath())) {
                    for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                        Files.delete(path);
                }
            }
            new AtomicFile(new File(context.getNoBackupFilesDir(), "moli-key.wrap")).delete();
            assertTrue(context.getSharedPreferences("meross-draft", Context.MODE_PRIVATE).edit().clear().commit());
            ProtectedKey.createPreview(context);
            assertTrue(ProfileStore.data(context).mkdir());
            return null;
        }).get();
    }
    private FreshEquipmentFixture() {}
}
