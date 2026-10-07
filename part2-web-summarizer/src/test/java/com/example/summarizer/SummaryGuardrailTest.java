package com.example.summarizer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SummaryGuardrailTest {
    @Test
    void acceptsExactly150WordsWithoutCallingModel() throws Exception {
        SummaryGuardrail guardrail = new SummaryGuardrail((instructions, source) -> {
            fail("No compression needed");
            return "";
        });
        assertEquals(150, SummaryGuardrail.countWords(guardrail.enforce("word ".repeat(150))));
    }

    @Test
    void compresses151WordsAndValidatesAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SummaryGuardrail guardrail = new SummaryGuardrail((instructions, source) -> {
            assertTrue(instructions.contains("150 words"));
            return calls.incrementAndGet() == 1 ? "word ".repeat(151) : "A concise summary.";
        });
        assertEquals("A concise summary.", guardrail.enforce("word ".repeat(151)));
        assertEquals(2, calls.get());
    }

    @Test
    void refusesOverlongResultAfterExactlyTwoCompressionAttempts() {
        AtomicInteger calls = new AtomicInteger();
        SummaryGuardrail guardrail = new SummaryGuardrail((instructions, source) -> {
            calls.incrementAndGet();
            return source;
        });
        assertThrows(SummarizerException.class, () -> guardrail.enforce("word ".repeat(151)));
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsBlankModelOutputAndPropagatesFailure() {
        assertThrows(SummarizerException.class,
                () -> new SummaryGuardrail((i, s) -> " ").enforce("word ".repeat(151)));
        assertThrows(SummarizerException.class,
                () -> new SummaryGuardrail((i, s) -> "unused").enforce(null));
        SummarizerException failure = new SummarizerException("Provider unavailable");
        assertSame(failure, assertThrows(SummarizerException.class,
                () -> new SummaryGuardrail((i, s) -> { throw failure; }).enforce("word ".repeat(151))));
    }

    @Test
    void countsWhitespaceSeparatedWordsConsistently() {
        assertEquals(0, SummaryGuardrail.countWords("\u00a0 \n"));
        assertEquals(4, SummaryGuardrail.countWords(" one\ttwo\nthree\u00a0four "));
    }
}
