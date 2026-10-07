package com.example.agent;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.agent.LanguageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private int run(String[] args, Map<String,String> env) {
        return Main.run(args, env, new BufferedReader(new StringReader("/exit\n")), new PrintStream(output), new PrintStream(errors));
    }
    @Test void handlesMissingKeyWithoutNetworkOrSecrets() {
        assertEquals(2, run(new String[0], Map.of()));
        assertTrue(errors.toString().contains("OPENAI_API_KEY"));
        assertEquals("", output.toString());
    }
    @Test void handlesHelpMissingDocumentAndBadConfiguration() {
        assertEquals(0, run(new String[]{"--help"}, Map.of()));
        assertEquals(1, run(new String[]{"missing-document.txt"}, Map.of("OPENAI_API_KEY", "test-key")));
        assertEquals(1, run(new String[0], Map.of("OPENAI_API_KEY", "test-key", "OPENAI_MODEL", " ")));
        assertEquals(1, run(new String[0], Map.of("OPENAI_API_KEY", "bad\nkey")));
        assertFalse(errors.toString().contains("test-key"));
        assertFalse(errors.toString().contains("Exception"));
    }
    @Test void conversationContinuesAfterExpectedModelFailureAndExitsCleanly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (i,m,t) -> {
            if (calls.incrementAndGet() == 1) throw new AgentException("Provider unavailable.");
            return new Answer("Hello Sean.", Basis.CONVERSATION, List.of());
        };
        Agent agent = new Agent(model, new ConversationMemory(), new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), (current, draft, citations, history, calculations) -> AnswerValidator.Verdict.SUPPORTED);
        assertEquals(0, Main.converse(agent, new BufferedReader(new StringReader("Hello\nMy name is Sean.\n/exit\n")),
                new PrintStream(output), new PrintStream(errors)));
        assertEquals(2, calls.get());
        assertTrue(output.toString().contains("Hello Sean."));
        assertTrue(errors.toString().contains("Provider unavailable"));
        assertFalse(errors.toString().contains("Exception"));
    }
}
