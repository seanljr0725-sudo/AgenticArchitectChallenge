package com.example.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static com.example.agent.LanguageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {
    @Test void storesPreviousTurnsAndReturnsImmutableSnapshots() throws Exception {
        var memory = new ConversationMemory();
        memory.remember(List.of(Message.user("My name is Sean."), Message.assistant("Hello Sean.")), Map.of());
        assertEquals("My name is Sean.", memory.messages().get(0).content());
        assertEquals(2, memory.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> memory.messages().clear());
    }
    @Test void evictsWholeTurnsIncludingToolEvidence() throws Exception {
        var memory = new ConversationMemory(1, 1000);
        var call = new ToolCall("c1", "document_search", "{}");
        memory.remember(List.of(Message.user("leave?"), Message.request(call), Message.result("c1", "18 days"),
                Message.assistant("18 days")), Map.of("S2", new DocumentRetriever.Evidence("S2", "18 days")));
        memory.remember(List.of(Message.user("Hi"), Message.assistant("Hello")), Map.of());
        assertEquals(2, memory.messages().size());
        assertTrue(memory.evidence().isEmpty());
        assertTrue(memory.messages().stream().noneMatch(m -> m.call() != null || m.callId() != null));
    }
    @Test void boundsTotalCharactersAndRejectsOversizedTurnAtomically() throws Exception {
        var memory = new ConversationMemory(10, 12);
        memory.remember(List.of(Message.user("12345"), Message.assistant("12345")), Map.of());
        memory.remember(List.of(Message.user("abc"), Message.assistant("def")), Map.of());
        assertEquals(2, memory.messages().size());
        assertEquals("abc", memory.messages().get(0).content());
        assertThrows(AgentException.class, () -> memory.remember(List.of(Message.user("x".repeat(13))), Map.of()));
        assertEquals("abc", memory.messages().get(0).content());
    }
}
