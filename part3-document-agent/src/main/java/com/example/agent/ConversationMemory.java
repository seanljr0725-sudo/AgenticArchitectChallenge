package com.example.agent;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evicts whole completed turns, never leaving orphaned tool calls/results. */
public final class ConversationMemory {
    private record Turn(List<LanguageModel.Message> messages, Map<String, DocumentRetriever.Evidence> evidence, int size) {}
    private final ArrayDeque<Turn> turns = new ArrayDeque<>();
    private final int maxTurns;
    private final int maxCharacters;
    private int characters;

    public ConversationMemory() { this(8, 60_000); }
    public ConversationMemory(int maxTurns, int maxCharacters) {
        if (maxTurns < 1 || maxCharacters < 1) throw new IllegalArgumentException("Memory limits must be positive.");
        this.maxTurns = maxTurns;
        this.maxCharacters = maxCharacters;
    }
    public List<LanguageModel.Message> messages() {
        return turns.stream().flatMap(turn -> turn.messages().stream()).toList();
    }
    public Map<String, DocumentRetriever.Evidence> evidence() {
        Map<String, DocumentRetriever.Evidence> result = new LinkedHashMap<>();
        turns.forEach(turn -> result.putAll(turn.evidence()));
        return Map.copyOf(result);
    }
    public void remember(List<LanguageModel.Message> messages, Map<String, DocumentRetriever.Evidence> evidence)
            throws AgentException {
        int size = messages.stream().mapToInt(LanguageModel.Message::size).sum()
                + evidence.values().stream().mapToInt(item -> item.id().length() + item.text().length()).sum();
        if (size > maxCharacters) throw new AgentException("This turn exceeds the conversation memory limit.");
        turns.addLast(new Turn(List.copyOf(messages), Map.copyOf(evidence), size));
        characters += size;
        while (turns.size() > maxTurns || characters > maxCharacters) characters -= turns.removeFirst().size();
    }
}
