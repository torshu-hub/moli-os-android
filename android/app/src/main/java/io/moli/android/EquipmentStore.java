package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import org.json.JSONObject;

/** User-approved local reads, included in the existing encrypted engine backup. */
final class EquipmentStore {
    private EquipmentStore() {}
    static synchronized JSONObject read(Context context) throws Exception {
        File file = new File(ProfileStore.data(context), "android-equipment.json");
        JSONObject input = file.exists()
                ? new JSONObject(ProfileStore.bounded(new AtomicFile(file).openRead(), 4096)) : new JSONObject();
        return ProfileStore.nativeResult(input.toString(), 3);
    }
    static synchronized void save(Context context, JSONObject input) throws Exception {
        JSONObject checked = ProfileStore.nativeResult(input.toString(), 3);
        ProfileStore.atomic(new File(ProfileStore.data(context), "android-equipment.json"), checked.toString());
    }
}
