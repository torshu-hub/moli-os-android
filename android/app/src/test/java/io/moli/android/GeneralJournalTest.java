package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class GeneralJournalTest {
    @Test public void optionalFieldDiagnosticsStayAnonymous() {
        String line="{\"ts\":1,\"duration_ms\":0,\"origin\":\"system\",\"action\":{\"kind\":\"diagnostic\",\"event\":\"netatmo_time_quality\",\"cause\":\"timestamp_missing\",\"retry_seconds\":0},\"outcome\":{\"result\":\"ok\"}}";
        assertTrue(GeneralJournal.redactedEngineEntry(line).contains("netatmo_time_quality | timestamp_missing"));
        for(String cause:new String[]{"timestamp_future_le_1s","timestamp_future_1_5s","timestamp_future_5_30s","timestamp_future_30_300s","timestamp_future_gt_300s"})assertTrue(GeneralJournal.redactedEngineEntry(line.replace("timestamp_missing",cause)).contains(cause));
        assertTrue(GeneralJournal.redactedEngineEntry(line.replace("netatmo_time_quality","netatmo_reachability").replace("timestamp_missing","reachable_missing")).contains("reachable_missing"));
        assertEquals("",GeneralJournal.redactedEngineEntry(line.replace("timestamp_missing","private-value")));
    }
    @Test public void timestampedNameUsesTabletLocalTime() {
        assertEquals("Moli-OS-Beta2-journal-2026-11-01_12-30-00.txt",GeneralJournal.exportName(java.time.ZonedDateTime.parse("2026-11-01T12:30:00+01:00[Europe/Paris]")));
    }
    @Test public void diagnosticExportRetainsOnlyAllowedCodes() {
        String line="{\"ts\":1,\"duration_ms\":0,\"origin\":\"system\",\"action\":{\"kind\":\"diagnostic\",\"event\":\"netatmo_read_error\",\"cause\":\"oauth\",\"retry_seconds\":30},\"outcome\":{\"result\":\"ok\"}}";
        assertTrue(GeneralJournal.redactedEngineEntry(line).contains("netatmo_read_error | oauth | retry_s=30"));
        assertEquals("",GeneralJournal.redactedEngineEntry(line.replace("oauth","private-token")));
    }

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
