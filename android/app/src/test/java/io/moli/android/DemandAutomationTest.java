package io.moli.android;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DemandAutomationTest {
    @Test public void standardGraphHasOnlyExplicitTransitionsAndNoStartup()throws Exception {
        JSONObject graph=DemandAutomation.graph("energy-test:room/declared_demand","plug-test:outlet/on");
        assertArrayEquals(new String[]{"energy-test:room/declared_demand","plug-test:outlet/on"},DemandAutomation.wiring(graph));
        JSONArray nodes=graph.getJSONArray("nodes");
        assertEquals("on",nodes.getJSONObject(0).getString("to"));assertTrue(nodes.getJSONObject(1).getBoolean("value"));
        assertEquals("off",nodes.getJSONObject(2).getString("to"));assertFalse(nodes.getJSONObject(3).getBoolean("value"));
        nodes.getJSONObject(0).put("from","unknown");assertNull(DemandAutomation.wiring(graph));
    }
    @Test public void layoutChangesRemainEditableButWebLogicIsPreserved()throws Exception {
        JSONObject graph=DemandAutomation.graph("energy-test:room/declared_demand","plug-test:outlet/on");
        graph.getJSONArray("nodes").getJSONObject(0).put("x",600);assertNotNull(DemandAutomation.wiring(graph));
        graph.getJSONArray("nodes").getJSONObject(0).put("for_s",60);assertNull(DemandAutomation.wiring(graph));
    }
    @Test public void webStringCommandMustNotBeRewrittenAsBoolean()throws Exception {
        JSONObject graph=DemandAutomation.graph("energy-test:room/declared_demand","plug-test:outlet/on");
        graph.getJSONArray("nodes").getJSONObject(1).put("value","true");
        assertNull(DemandAutomation.wiring(graph));
    }
    @Test public void editingDevicesKeepsWebLayoutAndNullLabelUsesNativeName()throws Exception {
        JSONObject device=new JSONObject().put("native_name","Thermostat test").put("label",new JSONObject().put("name",JSONObject.NULL));
        assertEquals("Thermostat test",DemandAutomation.name(device));
        JSONObject graph=DemandAutomation.graph("energy-test:room/declared_demand","plug-test:outlet/on");
        graph.getJSONArray("nodes").getJSONObject(0).put("x",600);
        JSONObject changed=DemandAutomation.retarget(graph,"energy-test:other/declared_demand","plug-test:other/on");
        assertEquals(600,changed.getJSONArray("nodes").getJSONObject(0).getInt("x"));
        assertEquals("energy-test:room/declared_demand",DemandAutomation.wiring(graph)[0]);
        assertEquals("plug-test:other/on",DemandAutomation.wiring(changed)[1]);
    }
}
