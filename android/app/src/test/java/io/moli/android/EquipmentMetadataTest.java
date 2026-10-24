package io.moli.android;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class EquipmentMetadataTest {
    @Test public void effectiveSettingsPreserveOpaqueMetadataByInternalId() throws Exception {
        JSONObject old=new JSONObject("{\"future\":{\"version\":7},\"printer\":{\"host\":\"192.168.1.20\",\"room\":\"Fixture room\"},\"meross\":[{\"id\":\"one\",\"name\":\"old\",\"annotation\":{\"value\":42}},{\"id\":\"two\",\"annotation\":{\"value\":11}}]}");
        JSONObject next=new JSONObject("{\"printer\":{\"host\":\"192.168.1.21\",\"enabled\":false},\"meross\":[{\"id\":\"two\",\"name\":\"renamed\",\"enabled\":true},{\"id\":\"one\",\"enabled\":false}]}");
        JSONObject merged=EquipmentStore.preserve(old,next);
        assertEquals(7,merged.getJSONObject("future").getInt("version"));
        assertEquals("Fixture room",merged.getJSONObject("printer").getString("room"));
        assertEquals("192.168.1.21",merged.getJSONObject("printer").getString("host"));
        assertEquals(11,merged.getJSONArray("meross").getJSONObject(0).getJSONObject("annotation").getInt("value"));
        assertEquals(42,merged.getJSONArray("meross").getJSONObject(1).getJSONObject("annotation").getInt("value"));
        assertEquals("old",old.getJSONArray("meross").getJSONObject(0).getString("name"));
    }
}
