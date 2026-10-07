package com.example.summarizer;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {
    private final int maxCharacters;

    public TextChunker(int maxCharacters) {
        if (maxCharacters < 2) {
            throw new IllegalArgumentException("Chunk size must be at least two characters.");
        }
        this.maxCharacters = maxCharacters;
    }

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replaceAll("(?U)\\s+", " ").strip();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + maxCharacters, normalized.length());
            if (end < normalized.length()) {
                int space = normalized.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                } else if (Character.isHighSurrogate(normalized.charAt(end - 1))
                        && Character.isLowSurrogate(normalized.charAt(end))) {
                    end--; // Do not split a Unicode surrogate pair in a long token.
                }
            }
            chunks.add(normalized.substring(start, end));
            start = end;
            if (start < normalized.length() && normalized.charAt(start) == ' ') {
                start++;
            }
        }
        return List.copyOf(chunks);
    }
}
