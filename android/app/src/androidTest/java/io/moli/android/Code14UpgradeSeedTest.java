package io.moli.android;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
@RunWith(AndroidJUnit4.class)
public final class Code14UpgradeSeedTest {
    @Test public void seedRealCode13()throws Exception {
        Code12UpgradeSeedTest.seed(InstrumentationRegistry.getInstrumentation().getTargetContext(),13);
    }
}
