package io.moli.android;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public final class ProfileInputTest {
    @Test public void copiesExactUtf8AndRejectsOversizeAndMalformedInput() throws Exception {
        String text="name = \"42.5 °C\"\n";
        assertEquals(text,ProfileStore.bounded(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),100));
        try { ProfileStore.bounded(new ByteArrayInputStream(new byte[101]),100); fail("oversize"); } catch (ProfileStore.Rejected expected) { }
        try { ProfileStore.bounded(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),100); fail("bad UTF-8"); } catch (java.nio.charset.CharacterCodingException expected) { }
    }
}
