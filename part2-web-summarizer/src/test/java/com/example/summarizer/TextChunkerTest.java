package com.example.summarizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {
    @Test
    void preservesEveryWordInOrderAndBoundsEachChunk() {
        String source = "alpha beta gamma delta epsilon ".repeat(500).strip();
        var chunks = new TextChunker(50).chunk(source);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.isBlank() && chunk.length() <= 50));
        assertEquals(source, String.join(" ", chunks));
    }

    @Test
    void handlesEmptyInputAndUnicodeWhitespace() {
        TextChunker chunker = new TextChunker(20);
        assertTrue(chunker.chunk(null).isEmpty());
        assertTrue(chunker.chunk(" \n\t\u00a0 ").isEmpty());
        assertEquals(java.util.List.of("one two three"), chunker.chunk(" one\n two\u00a0three "));
    }

    @Test
    void handlesExactBoundary() {
        assertEquals(java.util.List.of("hello", "world"), new TextChunker(5).chunk("hello world"));
        assertEquals(java.util.List.of("hello"), new TextChunker(5).chunk("hello"));
    }

    @Test
    void splitsHugeTokensWithoutLosingCharactersOrBreakingUnicode() {
        String source = "😀".repeat(20);
        var chunks = new TextChunker(5).chunk(source);
        assertEquals(source, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 5 && chunk.length() % 2 == 0));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(1));
    }
}
