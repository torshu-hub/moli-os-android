package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class NetatmoPanelTest {
    @Test public void temporaryFailuresDoNotAskForAccountAuthorization(){
        assertEquals(R.string.netatmo_network,NetatmoPanel.errorLabel("oauth_network"));
        assertEquals(R.string.netatmo_provider,NetatmoPanel.errorLabel("oauth_provider"));
        assertEquals(R.string.netatmo_quota,NetatmoPanel.errorLabel("rate_limit"));
        assertEquals(R.string.netatmo_storage,NetatmoPanel.errorLabel("storage"));
    }
    @Test public void invalidGrantApplicationAndAmbiguousRotationRemainDistinct(){
        assertEquals(R.string.netatmo_reauthorize,NetatmoPanel.errorLabel("oauth_denied"));
        assertEquals(R.string.netatmo_application_rejected,NetatmoPanel.errorLabel("oauth_client_rejected"));
        assertEquals(R.string.netatmo_ambiguous,NetatmoPanel.errorLabel("oauth_ambiguous"));
    }
}
