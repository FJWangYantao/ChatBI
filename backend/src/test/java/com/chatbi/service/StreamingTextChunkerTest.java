package com.chatbi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTextChunkerTest {

    @Test
    void shortTextKeepsFineGrainedChunks() {
        StreamingTextChunker.Plan plan = StreamingTextChunker.plan("简短报告内容");

        assertEquals(12, plan.chunkSize());
        assertEquals(20, plan.delayMs());
    }

    @Test
    void longTextUsesLargerChunksAndLowerDelay() {
        String text = "数据洞察".repeat(800);

        StreamingTextChunker.Plan plan = StreamingTextChunker.plan(text);

        assertTrue(plan.chunkSize() >= 48, "long reports should use much larger chunks");
        assertTrue(plan.delayMs() <= 8, "long reports should use a lower inter-chunk delay");
    }
}
