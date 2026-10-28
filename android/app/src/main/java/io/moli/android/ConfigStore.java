package io.moli.android;

import android.content.Context;
import java.io.File;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;

/** One native file, no Android driver preferences. Called only on EngineHost's queue. */
final class ConfigStore {
    final File directory;
    final ConfigurationTransaction.Disk disk;
    final ConfigurationTransaction transaction;
    ConfigStore(Context context) throws Exception {
        directory = ProtectedKey.dataDirectory(context, ProtectedKey.open(context));
        directory.mkdirs();
        disk = new ConfigurationTransaction.Disk(directory);
        transaction = new ConfigurationTransaction(disk);
    }
    String read() throws Exception {
        String current = disk.read("moli.toml", ConfigurationTransaction.FILE_LIMIT);
        // The first engine start writes the official starter configuration.
        if(current == null) throw new IllegalStateException("start_first");
        return current;
    }
    static JSONObject nativeCall(JSONObject input) throws Exception {
        JSONObject result = new JSONObject(NativeServer.configuration(input.toString()));
        if(result.has("error")) throw new IllegalStateException(result.getString("error"));
        return result;
    }
    void save(String expected, String id, JSONObject vars) throws Exception {
        transaction.recover();
        if(!Objects.equals(expected, read())) throw new IllegalStateException("conflict");
        String candidate = nativeCall(new JSONObject().put("action","prepare").put("text",expected).put("id",id).put("vars",vars)).getString("text");
        String name = "moli.toml.validation";
        try {
            disk.write(name, candidate);
            nativeCall(new JSONObject().put("action","validate").put("path",new File(directory,name).getAbsolutePath()));
        } finally { disk.remove(name); }
        transaction.commitExpected(expected,candidate);
    }
    void restore(String current, String previous) throws Exception { transaction.commitExpected(current, previous); }
}
