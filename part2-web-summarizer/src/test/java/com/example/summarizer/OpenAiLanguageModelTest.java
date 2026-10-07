package com.example.summarizer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiLanguageModelTest {
    private static final String SUCCESS = """
            {"status":"completed","output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","content":[{"type":"output_text","text":"First fact."}]},
              {"type":"message","content":[{"type":"output_text","text":"Second fact."}]}
            ]}
            """;

    @Test
    void sendsInstructionsSeparatelyAndReadsAllOutputMessages() throws Exception {
        AtomicReference<JsonObject> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (TestServer server = new TestServer(exchange -> {
            received.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            TestServer.respond(exchange, 200, "application/json", SUCCESS);
        })) {
            assertEquals("First fact.\nSecond fact.", client(server).generate("Summarize facts", "Untrusted source"));
            assertEquals("Summarize facts", received.get().get("instructions").getAsString());
            assertEquals("Untrusted source", received.get().get("input").getAsString());
            assertEquals("test-model", received.get().get("model").getAsString());
            assertFalse(received.get().get("store").getAsBoolean());
            assertEquals("Bearer test-key", authorization.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void retriesTransientFailuresAndRecovers(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            int attempt = requests.incrementAndGet();
            TestServer.respond(exchange, attempt < 3 ? status : 200, "application/json", attempt < 3 ? "{}" : SUCCESS);
        })) {
            assertEquals("First fact.\nSecond fact.", client(server).generate("Summarize", "source"));
            assertEquals(3, requests.get());
        }
    }

    @Test
    void stopsAfterThreeFailedRequestsAndDoesNotExposeResponseBody() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            TestServer.respond(exchange, 503, "application/json", "sensitive-provider-details");
        })) {
            SummarizerException failure = assertThrows(SummarizerException.class,
                    () -> client(server).generate("Summarize", "source"));
            assertEquals(3, requests.get());
            assertFalse(failure.getMessage().contains("sensitive-provider-details"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void doesNotRetryPermanentHttpFailures(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            TestServer.respond(exchange, status, "application/json", "{}");
        })) {
            assertThrows(SummarizerException.class, () -> client(server).generate("Summarize", "source"));
            assertEquals(1, requests.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not JSON",
            "{}",
            "{\"status\":\"incomplete\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\"}]}]}"
    })
    void rejectsInvalidEmptyIncompleteOrRefusedResponsesWithoutRetry(String body) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            TestServer.respond(exchange, 200, "application/json", body);
        })) {
            assertThrows(SummarizerException.class, () -> client(server).generate("Summarize", "source"));
            assertEquals(1, requests.get());
        }
    }

    @Test
    void preservesInterruptionSoCancellationIsNotRetried() throws Exception {
        try (TestServer server = new TestServer(exchange -> TestServer.respond(exchange, 200, "application/json", SUCCESS))) {
            OpenAiLanguageModel model = client(server);
            Thread.currentThread().interrupt();
            try {
                assertThrows(SummarizerException.class, () -> model.generate("Summarize", "source"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // Do not leave the JUnit runner interrupted.
            }
        }
    }

    private OpenAiLanguageModel client(TestServer server) {
        return new OpenAiLanguageModel(HttpClient.newHttpClient(), server.uri(), "test-key", "test-model", Duration.ZERO);
    }
}
