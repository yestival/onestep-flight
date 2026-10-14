package com.selene.onestepflight;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechChunksTest {
    @Test public void longMultilingualTextKeepsEveryCharacterWithinEngineLimit() {
        String text = "请前往登机口。 Go to your gate. Ve a la puerta. ✈️ 😀 ".repeat(160);
        var chunks = SpeechChunks.split(text, 4000);
        assertTrue(chunks.size() > 1);
        assertEquals(text, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 4000);
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
        }
    }
    @Test public void surrogatePairAtBoundaryIsNotSplit() {
        assertEquals(java.util.List.of("ab", "😀c"), SpeechChunks.split("ab😀c", 3));
        assertEquals(java.util.List.of("四号登机口"), SpeechChunks.split("四号登机口", 4000));
    }
}
