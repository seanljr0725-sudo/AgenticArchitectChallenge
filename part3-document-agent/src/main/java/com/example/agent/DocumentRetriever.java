package com.example.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Paragraph chunks with deterministic lexical ranking; no embeddings or remote calls. */
public final class DocumentRetriever {
    public record Evidence(String id, String text) {}
    private record Section(Evidence evidence, Set<String> terms) {}
    private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "be", "of", "to", "in", "on",
            "for", "and", "or", "per", "do", "does", "how", "many", "much", "what", "which", "i", "my", "we",
            "you", "me", "can", "would", "receive", "employees", "employee", "document", "handbook", "policy",
            "provide", "provides", "about", "please", "tell", "get", "with", "from");
    private final List<Section> sections;

    public DocumentRetriever(Path path) throws AgentException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(100_001);
            if (bytes.length > 100_000) throw new AgentException("Document exceeds the 100 KB limit.");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isBlank()) throw new AgentException("The provided document is empty.");
            List<Section> indexed = new ArrayList<>();
            for (String paragraph : text.split("\\R\\s*\\R")) {
                String remaining = paragraph.strip();
                while (!remaining.isEmpty()) {
                    int end = Math.min(1_000, remaining.length());
                    if (end < remaining.length()) {
                        int space = remaining.lastIndexOf(' ', end);
                        if (space > 0) end = space;
                        else if (Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
                    }
                    String chunk = remaining.substring(0, end);
                    Evidence evidence = new Evidence("S" + (indexed.size() + 1), chunk);
                    indexed.add(new Section(evidence, terms(chunk)));
                    remaining = remaining.substring(end).strip();
                }
            }
            sections = List.copyOf(indexed);
        } catch (IOException | SecurityException e) {
            throw new AgentException("Cannot read the sample document. Check its path and permissions.", e);
        }
    }
    public List<Evidence> search(String query) throws AgentException {
        if (query == null || query.isBlank() || query.length() > 300)
            throw new AgentException("Document search requires a query of 1 to 300 characters.");
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return List.of();
        // At least half the meaningful query terms must occur in a section. This is relevance, not proof.
        int minimum = Math.max(1, (queryTerms.size() + 1) / 2);
        return sections.stream().filter(section -> score(section, queryTerms) >= minimum)
                .sorted(Comparator.<Section>comparingInt(section -> score(section, queryTerms)).reversed()
                        .thenComparing(section -> section.evidence().id()))
                .limit(3).map(Section::evidence).toList();
    }
    private static int score(Section section, Set<String> query) {
        return (int) query.stream().filter(section.terms()::contains).count();
    }
    private static Set<String> terms(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> !term.isEmpty() && !STOP.contains(term)).collect(Collectors.toSet());
    }
}
