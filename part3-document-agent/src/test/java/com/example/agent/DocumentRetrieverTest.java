package com.example.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DocumentRetrieverTest {
    @TempDir Path temp;
    @Test void retrievesDistinctPoliciesAndNeverInventsEvidence() throws Exception {
        var retriever = new DocumentRetriever(Path.of("documents/sample-document.txt"));
        assertTrue(retriever.search("annual leave").get(0).text().contains("18 days"));
        assertTrue(retriever.search("medical leave").get(0).text().contains("14 days"));
        assertTrue(retriever.search("professional training allowance").get(0).text().contains("RM1,200"));
        assertTrue(retriever.search("remote work").get(0).text().contains("two days"));
        assertTrue(retriever.search("stock options").isEmpty());
        assertTrue(retriever.search("what does the document provide").isEmpty());
    }
    @Test void boundsChunksAndKeepsSourceText() throws Exception {
        Path file = temp.resolve("long.txt");
        String source = "training allowance details ".repeat(200);
        Files.writeString(file, source);
        var results = new DocumentRetriever(file).search("training allowance");
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(hit -> hit.text().length() <= 1000 && source.contains(hit.text())));
    }
    @Test void handlesMissingEmptyOversizedDocumentsAndInvalidQueries() throws Exception {
        assertThrows(AgentException.class, () -> new DocumentRetriever(temp.resolve("missing.txt")));
        Path file = temp.resolve("sample.txt");
        Files.writeString(file, " ");
        assertThrows(AgentException.class, () -> new DocumentRetriever(file));
        Files.writeString(file, "x".repeat(100001));
        assertThrows(AgentException.class, () -> new DocumentRetriever(file));
        var retriever = new DocumentRetriever(Path.of("documents/sample-document.txt"));
        assertThrows(AgentException.class, () -> retriever.search(""));
        assertThrows(AgentException.class, () -> retriever.search("x".repeat(301)));
    }
}
