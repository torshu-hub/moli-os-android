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
}
