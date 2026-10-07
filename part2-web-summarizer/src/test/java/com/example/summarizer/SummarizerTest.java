package com.example.summarizer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SummarizerTest {
    @Test
    void summarizesEachChunkIndependentlyThenCombinesAllNotes() throws Exception {
        TextChunker chunker = new TextChunker(100);
        String source = "Page information about architecture. ".repeat(10);
        List<String> chunks = chunker.chunk(source);
        List<String> inputs = new ArrayList<>();
        LanguageModel model = (instructions, input) -> {
            inputs.add(input);
            return inputs.size() <= chunks.size() ? "note" + inputs.size() : "Final summary.";
        };
        assertEquals("Final summary.", new Summarizer(model, chunker).summarize(source));
        assertEquals(chunks, inputs.subList(0, chunks.size()));
        for (int index = 1; index <= chunks.size(); index++) {
            assertTrue(inputs.get(inputs.size() - 1).contains("note" + index));
        }
        assertEquals(chunks.size() + 1, inputs.size());
    }

    @Test
    void rechunksIntermediateNotesSoEveryModelInputStaysBounded() throws Exception {
        AtomicInteger mapCalls = new AtomicInteger();
        String source = "source ".repeat(80);
        TextChunker chunker = new TextChunker(100);
        LanguageModel model = (instructions, input) -> {
            assertTrue(input.length() <= 100, "Every map/reduce input must fit a chunk");
            if (instructions.contains("Combine these notes")) {
                return "Finished summary.";
            }
            mapCalls.incrementAndGet();
            return input.contains("source") ? "detail ".repeat(8) : "Combined detail.";
        };
        assertEquals("Finished summary.", new Summarizer(model, chunker).summarize(source));
        assertTrue(mapCalls.get() > chunker.chunk(source).size());
    }

    @Test
    void appliesFinalGuardrailAfterCombining() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, input) -> switch (calls.incrementAndGet()) {
            case 1 -> "Intermediate notes.";
            case 2 -> "word ".repeat(151);
            default -> "Compressed final summary.";
        };
        assertEquals("Compressed final summary.", new Summarizer(model, new TextChunker(6000)).summarize("Some source text."));
        assertEquals(3, calls.get());
    }

    @Test
    void stopsIfReductionDoesNotShrink() {
        AtomicInteger calls = new AtomicInteger();
        var summarizer = new Summarizer((instructions, input) -> {
            calls.incrementAndGet();
            return "unhelpful ".repeat(10);
        }, new TextChunker(50));
        assertTrue(assertThrows(SummarizerException.class,
                () -> summarizer.summarize("source ".repeat(20))).getMessage().contains("did not shrink"));
        assertTrue(calls.get() < 20);
    }

    @Test
    void rejectsEmptyOrExcessiveInputBeforeCallingTheModel() {
        var summarizer = new Summarizer((instructions, input) -> {
            fail("Invalid input must not call model");
            return "";
        }, new TextChunker(50));
        assertThrows(SummarizerException.class, () -> summarizer.summarize(" "));
        assertThrows(SummarizerException.class, () -> summarizer.summarize("source ".repeat(2000)));
    }

    @Test
    void abortsOnFailedOrEmptyChunkInsteadOfReturningPartialSummary() {
        AtomicInteger calls = new AtomicInteger();
        var summarizer = new Summarizer((instructions, input) -> {
            if (calls.incrementAndGet() == 2) {
                throw new SummarizerException("Model failed");
            }
            return "Notes";
        }, new TextChunker(50));
        assertThrows(SummarizerException.class, () -> summarizer.summarize("source ".repeat(30)));
        assertEquals(2, calls.get());
        assertThrows(SummarizerException.class,
                () -> new Summarizer((i, s) -> " ", new TextChunker(50)).summarize("source"));
    }
}
