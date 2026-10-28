package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class TrialJournalTest {
    @Test public void pairedPseudonymsAndReceiptRoundTripWithoutRawIdentifiers()throws Exception {
        TrialJournal j=new TrialJournal();j.action(1792692000000L,TrialJournal.Event.CANDIDATE_RESULT,TrialJournal.Source.COMPTE,TrialJournal.Outcome.REFUSE,TrialJournal.Error.UUID_DIFFERENT,0,0,"0123456789abcdef","abcdef0123456789",0);
        j.action(1792692001000L,TrialJournal.Event.EQUIPMENT_READ,TrialJournal.Source.EQUIPEMENTS,TrialJournal.Outcome.FRAIS,TrialJournal.Error.AUCUNE,2,3,"0123456789abcdef","",1792692000999L);
        String text=TrialJournal.parse(j.json()).text();assertTrue(text.contains("candidate#abcdef0123456789"));assertTrue(text.contains("UUID_DIFFERENT"));assertTrue(text.contains("dernière_réception="));assertFalse(text.contains("192.168."));
    }
    @Test public void typedActionsHaveReadableTimestampsAndNeverAcceptArbitraryStrings() throws Exception {
        TrialJournal j=new TrialJournal();j.salt="c".repeat(64);j.active=true;
        j.action(1792692000000L,TrialJournal.Event.UUID_CHECK,TrialJournal.Source.COMPTE,TrialJournal.Outcome.REFUSE,TrialJournal.Error.IDENTITE,1,0,"raw-ip-192.168.1.20");
        j.action(1792692000001L,TrialJournal.Event.EQUIPMENT_READ,TrialJournal.Source.EQUIPEMENTS,TrialJournal.Outcome.ABSENT,TrialJournal.Error.RESEAU,3,0,"0123456789abcdef");
        String text=TrialJournal.parse(j.json()).text();
        assertTrue(text.contains("Identité UUID"));assertTrue(text.contains("REFUSE"));assertTrue(text.contains("1970")==false);assertTrue(text.contains("Z]"));
        assertFalse(text.contains(j.salt));assertFalse(text.contains("192.168.1.20"));assertTrue(text.contains("appareil#0123456789abcdef"));
        TrialJournal ignored=TrialJournal.parse("{\"events\":[{\"event\":\"UUID_CHECK\",\"source\":\"secret@example.com\",\"result\":\"REUSSI\",\"error\":\"AUCUNE\"}]}");assertEquals(0,ignored.events.length());
    }
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
