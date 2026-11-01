package io.moli.android;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class AutomationPresentationTest {
    @Test public void approvalAndLiveDetermineTheThreeStates()throws Exception {
        assertEquals(R.string.auto_draft_state,AutomationPresentation.state(new JSONObject().put("approved",false).put("live",false).put("automation",new JSONObject().put("enabled",true))));
        assertEquals(R.string.auto_inactive,AutomationPresentation.state(new JSONObject().put("approved",true).put("live",false)));
        assertEquals(R.string.auto_active,AutomationPresentation.state(new JSONObject().put("approved",true).put("live",true)));
    }
}
