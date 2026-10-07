package com.example.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static com.example.agent.LanguageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiLanguageModelTest {
    private static final Gson JSON = new Gson();
    private static String success() {
        return JSON.toJson(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content",
                JSON.toJson(Map.of("answer", "400", "basis", "CALCULATION", "evidenceIds", List.of())))))));
    }
    private static String completion(String content) {
        return JSON.toJson(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
    }
    private OpenAiLanguageModel client(TestServer server) throws Exception {
        return new OpenAiLanguageModel(HttpClient.newHttpClient(), server.uri(), "test-key", "test-model", Duration.ofSeconds(2), 0);
    }
    @Test void sendsStrictToolsAndCorrectCallResultProtocol() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        try (TestServer server = new TestServer(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            TestServer.respond(exchange, 200, success());
        })) {
            var call = new ToolCall("c1", "calculator", "{\"expression\":\"25*16\"}");
            Reply reply = client(server).respond(Agent.INSTRUCTIONS,
                    List.of(Message.user("25*16"), Message.request(call), Message.result("c1", "{\"result\":\"400\"}")), Agent.TOOLS);
            assertEquals("400", ((Answer) reply).text());
            assertEquals("Bearer test-key", auth.get());
            assertFalse(request.get().get("store").getAsBoolean());
            assertFalse(request.get().get("parallel_tool_calls").getAsBoolean());
            assertEquals("auto", request.get().get("tool_choice").getAsString());
            assertTrue(request.get().getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").get("strict").getAsBoolean());
            var messages = request.get().getAsJsonArray("messages");
            assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
            assertEquals("c1", messages.get(3).getAsJsonObject().get("tool_call_id").getAsString());
            assertEquals("c1", messages.get(2).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
            assertEquals("json_schema", request.get().getAsJsonObject("response_format").get("type").getAsString());
        }
    }
    @Test void omitsToolFieldsForBoundedDocumentVerificationCall() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        try (TestServer server = new TestServer(exchange -> {
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            TestServer.respond(exchange, 200, completion("{\"verdict\":\"SUPPORTED\"}"));
        })) {
            assertEquals("SUPPORTED", ((Verification) client(server).respond("Verify this answer",
                    List.of(Message.user("data")), List.of())).verdict());
            assertFalse(request.get().has("tools"));
            assertFalse(request.get().has("tool_choice"));
            assertFalse(request.get().has("parallel_tool_calls"));
            var format = request.get().getAsJsonObject("response_format").getAsJsonObject("json_schema");
            assertEquals("semantic_verdict", format.get("name").getAsString());
            assertEquals("verdict", format.getAsJsonObject("schema").getAsJsonArray("required").get(0).getAsString());
        }
    }
    @Test void verifiesProfessionalTrainingAllowanceWithRetrievedSource() throws Exception {
        String question = "According to the document, what is the professional training allowance?";
        String draft = "Employees receive RM1,200 per year for approved professional training.";
        List<JsonObject> requests = new CopyOnWriteArrayList<>();
        String searchCall = JSON.toJson(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message",
                Map.of("tool_calls", List.of(Map.of("id", "search1", "type", "function", "function",
                        Map.of("name", "document_search", "arguments", "{\"query\":\"professional training allowance\"}"))))))));
        String candidate = completion(JSON.toJson(Map.of("answer", draft, "basis", "DOCUMENT", "evidenceIds", List.of("S5"))));
        String verifier = completion("{\"verdict\":\"SUPPORTED\"}");
        try (TestServer server = new TestServer(exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            TestServer.respond(exchange, 200, switch (requests.size()) {
                case 1 -> searchCall;
                case 2 -> candidate;
                default -> verifier;
            });
        })) {
            Agent agent = new Agent(client(server), new ConversationMemory(),
                    new DocumentRetriever(Path.of("documents/sample-document.txt")), new CalculatorTool());
            assertEquals(draft + " [S5]", agent.chat(question));
        }
        assertEquals(3, requests.size());
        var toolResult = requests.get(1).getAsJsonArray("messages");
        var evidence = JsonParser.parseString(toolResult.get(toolResult.size() - 1).getAsJsonObject()
                .get("content").getAsString()).getAsJsonObject().getAsJsonArray("evidence");
        assertEquals("S5", evidence.get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(evidence.get(0).getAsJsonObject().get("text").getAsString().contains("RM1,200 per year"));
        JsonObject verificationRequest = requests.get(2);
        assertFalse(verificationRequest.has("tools"));
        assertEquals("semantic_verdict", verificationRequest.getAsJsonObject("response_format")
                .getAsJsonObject("json_schema").get("name").getAsString());
        var verificationMessages = verificationRequest.getAsJsonArray("messages");
        var verificationData = JsonParser.parseString(verificationMessages.get(1).getAsJsonObject()
                .get("content").getAsString()).getAsJsonObject();
        assertEquals(question, verificationData.get("currentUserMessage").getAsString());
        assertEquals(draft, verificationData.get("draftAnswer").getAsString());
        assertEquals("DOCUMENT", verificationData.get("draftBasis").getAsString());
        assertEquals("S5", verificationData.getAsJsonArray("citedEvidence").get(0)
                .getAsJsonObject().get("id").getAsString());
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "{\"verdict\":\"MAYBE\"}",
            "{\"verdict\":\"SUPPORTED\",\"extra\":true}",
            "{\"answer\":\"SUPPORTED\",\"basis\":\"DOCUMENT\",\"evidenceIds\":[\"S5\"]}"})
    void rejectsMalformedVerificationResponses(String content) throws Exception {
        try (TestServer server = new TestServer(exchange -> TestServer.respond(exchange, 200, completion(content)))) {
            assertThrows(AgentException.class, () -> client(server).respond("Verify", List.of(Message.user("data")), List.of()));
        }
    }
    @Test void parsesStructuredFunctionCall() throws Exception {
        String response = JSON.toJson(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                "tool_calls", List.of(Map.of("id", "s1", "type", "function", "function",
                        Map.of("name", "document_search", "arguments", "{\"query\":\"annual leave\"}"))))))));
        try (TestServer server = new TestServer(exchange -> TestServer.respond(exchange, 200, response))) {
            var reply = (ToolCall) client(server).respond("instructions", List.of(), Agent.TOOLS);
            assertEquals("document_search", reply.name());
            assertEquals("s1", reply.id());
        }
    }
    @ParameterizedTest @ValueSource(ints={429,500,503})
    void retriesTransientFailuresAndRecovers(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            int count = requests.incrementAndGet();
            TestServer.respond(exchange, count < 3 ? status : 200, count < 3 ? "{}" : success());
        })) {
            assertInstanceOf(Answer.class, client(server).respond("instructions", List.of(), Agent.TOOLS));
            assertEquals(3, requests.get());
        }
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,503})
    void boundsRetriesAndHidesProviderErrorBodies(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            TestServer.respond(exchange, status, "secret-key-private-source");
        })) {
            AgentException failure = assertThrows(AgentException.class, () -> client(server).respond("instructions", List.of(), Agent.TOOLS));
            assertEquals(status == 503 ? 3 : 1, requests.get());
            assertFalse(failure.getMessage().contains("secret"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"not JSON", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"no\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not structured\"}}]}"})
    void rejectsMalformedIncompleteAndRefusedResponsesWithoutRetry(String response) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet(); TestServer.respond(exchange, 200, response);
        })) {
            assertThrows(AgentException.class, () -> client(server).respond("instructions", List.of(), Agent.TOOLS));
            assertEquals(1, requests.get());
        }
    }
    @Test void timesOutAndStopsAfterBoundedAttempts() throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            var model = new OpenAiLanguageModel(HttpClient.newHttpClient(), server.uri(), "test-key", "test-model", Duration.ofMillis(50), 0);
            assertTimeout(Duration.ofSeconds(3), () -> assertThrows(AgentException.class,
                    () -> model.respond("instructions", List.of(), Agent.TOOLS)));
        }
    }
    @Test void preservesInterruptionWithoutRetry() throws Exception {
        try (TestServer server = new TestServer(exchange -> TestServer.respond(exchange, 200, success()))) {
            var model = client(server);
            Thread.currentThread().interrupt();
            try {
                assertThrows(AgentException.class, () -> model.respond("instructions", List.of(), Agent.TOOLS));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }
}
