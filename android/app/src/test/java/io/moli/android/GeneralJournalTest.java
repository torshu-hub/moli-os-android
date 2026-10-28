package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class GeneralJournalTest {
    @Test public void healthExportDropsDriverIdsCoordinatesAndFreeErrors()throws Exception{
        String safe=GeneralJournal.redactedHealth("{\"journal_failures\":0,\"drivers\":[{\"instance\":\"private-home\",\"status\":{\"state\":\"backoff\",\"error\":\"latitude=1.234567&token=secret\",\"retry_in_ms\":1000}}]}");
        assertTrue(safe.contains("driver#1 | backoff | retry_ms=1000"));
        assertFalse(safe.contains("private"));assertFalse(safe.contains("latitude"));assertFalse(safe.contains("secret"));
    }
    @Test public void exportWhitelistsEnvelopeAndDropsAllFreeText(){
        String raw="{\"ts\":1,\"duration_ms\":2,\"origin\":\"api\",\"actor\":\"user@example.com\",\"action\":{\"kind\":\"command\",\"point\":\"location:exact\",\"value\":\"secret\"},\"outcome\":{\"result\":\"err\",\"error\":\"token=private latitude=1.234567\"}}";
        String safe=GeneralJournal.redactedEngineEntry(raw);
        assertTrue(safe.contains("api | command | err | ms=2"));
        for(String forbidden:new String[]{"user@","location","secret","private","1.234567","token"})assertFalse(safe.contains(forbidden));
        assertEquals("",GeneralJournal.redactedEngineEntry(raw.replace("command","arbitrary-secret")));
    }
}
