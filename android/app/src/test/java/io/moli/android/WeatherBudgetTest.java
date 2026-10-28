package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class WeatherBudgetTest {
    @Test public void attemptsRemainLimitedAcrossRestartsAndClockRollback() {
        WeatherBudget b=new WeatherBudget(0,0,0); assertTrue(b.reserve(1000));
        assertFalse(b.reserve(2000)); assertFalse(b.reserve(999));
        b=new WeatherBudget(b.last,b.window,b.attempts);
        for(int i=1;i<10;i++) assertTrue(b.reserve(1000+60000L*i));
        assertFalse(b.reserve(1000+600000)); assertTrue(b.reserve(1000+3600000));
    }
}
