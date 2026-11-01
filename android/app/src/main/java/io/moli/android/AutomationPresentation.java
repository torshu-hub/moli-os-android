package io.moli.android;
import org.json.JSONObject;
/** Presentation from the official API, independent of enabled alone. */
final class AutomationPresentation {
    static int state(JSONObject view) {
        return view.optBoolean("live")?R.string.auto_active:view.optBoolean("approved")?R.string.auto_inactive:R.string.auto_draft_state;
    }
    private AutomationPresentation(){}
}
