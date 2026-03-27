package com.chatbi.service;

public final class StreamingTextChunker {

    private StreamingTextChunker() {
    }

    public static Plan plan(String text) {
        int length = text == null ? 0 : text.length();

        if (length >= 2400) {
            return new Plan(64, 6);
        }
        if (length >= 1200) {
            return new Plan(48, 8);
        }
        if (length >= 400) {
            return new Plan(24, 12);
        }
        return new Plan(12, 20);
    }

    public record Plan(int chunkSize, int delayMs) {
    }
}
