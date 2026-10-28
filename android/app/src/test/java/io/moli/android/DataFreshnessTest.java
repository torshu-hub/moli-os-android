package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DataFreshnessTest {
    @Test public void failedOrOldDataCannotLookFresh() {
        assertTrue(DataFreshness.recent(true,true,true,1000,1100,200));
        assertFalse(DataFreshness.recent(false,true,true,1000,1100,200));
        assertFalse(DataFreshness.recent(true,false,true,1000,1100,200));
        assertFalse(DataFreshness.recent(true,true,false,1000,1100,200));
        assertFalse(DataFreshness.recent(true,true,true,1000,1300,200));
        assertFalse(DataFreshness.recent(true,true,true,2000,1100,200));
    }
}
