package io.moli.android;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class TrialEvidenceTest {
    @Test public void profileNameAndApprovalAreNotEvidenceOfFreshData() throws Exception {
        JSONObject selected=new JSONObject("{\"import\":{\"name\":\"42.5 °C\",\"mode\":\"synthetic\",\"every\":5}}");
        JSONObject snap=new JSONObject("{\"drivers\":[{\"instance\":\"android-import\",\"status\":{\"state\":\"running\"}}],\"devices\":[{\"instance\":\"android-import\",\"online\":true,\"state\":{}}]}");
        assertFalse(TrialEvidence.observe(selected,snap,10000,false).synthetic);
        JSONObject state=snap.getJSONArray("devices").getJSONObject(0).getJSONObject("state");
        state.put("temperature",new JSONObject().put("value",42.5).put("ts",9000));
        state.put("humidity",new JSONObject().put("value",55).put("ts",9000));
        assertTrue(TrialEvidence.observe(selected,snap,10000,false).synthetic);
        assertFalse(TrialEvidence.observe(selected,snap,100000,false).synthetic);
    }
}
