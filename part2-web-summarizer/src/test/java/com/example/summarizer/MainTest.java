package com.example.summarizer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @Test
    void reportsInvalidPortSafelyBeforeRequiringCredentials() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(1, Main.run(new String[]{"http://localhost:99999/"}, Map.of(),
                new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertTrue(errors.toString().startsWith("Error:"));
        assertTrue(errors.toString().contains("port"));
        assertFalse(errors.toString().contains("Exception"));
    }
    @Test
    void helpWorksWithoutAnApiKey() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"--help"}, Map.of(), new PrintStream(output), new PrintStream(errors)));
        assertTrue(output.toString().contains("Usage:"));
        assertEquals("", errors.toString());
    }

    @Test
    void reportsMissingConfigurationWithoutPrintingASummary() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"https://example.com"}, Map.of(), new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("OPENAI_API_KEY"));
    }

    @Test
    void rejectsInvalidUrlWithoutPrintingAStackTrace() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(1, Main.run(new String[]{"file:///tmp/page.html"}, Map.of(), new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertTrue(errors.toString().startsWith("Error:"));
        assertFalse(errors.toString().contains("Exception"));
    }
}
