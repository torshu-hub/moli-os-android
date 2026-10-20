package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class TrialJournalTest {
    @Test public void boundsJournalAndDoesNotExportUnrecognizedPayloads() throws Exception {
        TrialJournal j=TrialJournal.parse("{\"active\":true,\"events\":[{\"event\":\"token=private\",\"raw\":\"PIN=4826 secret=file.toml\"}]}");
        assertEquals(0,j.events.length());
        for(int i=0;i<300;i++) j.add(1000+i,TrialJournal.Event.ENGINE_READ_OK,i,0);
        assertEquals(256,j.events.length()); assertEquals(44,j.dropped);
        TrialJournal restored=TrialJournal.parse(j.json());
        assertTrue(restored.active); assertEquals(j.text(),restored.text());
        assertFalse(restored.text().contains("private")); assertFalse(restored.text().contains("4826"));
        assertFalse(restored.text().contains("file.toml"));
    }
}
