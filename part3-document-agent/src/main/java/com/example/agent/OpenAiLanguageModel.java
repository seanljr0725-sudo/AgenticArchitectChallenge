package com.example.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Official Chat Completions function calls, with a strict schema for final answer metadata. */
public final class OpenAiLanguageModel implements LanguageModel {
    private static final Gson JSON = new Gson();
    private static final Logger LOG = Logger.getLogger(OpenAiLanguageModel.class.getName());
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final Duration timeout;
    private final long retryDelayMs;

    public OpenAiLanguageModel(String key, String model) throws AgentException {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create("https://api.openai.com/v1/chat/completions"), key, model, Duration.ofSeconds(45), 500);
    }
    // Test-only injection: the CLI never accepts endpoint overrides.
    OpenAiLanguageModel(HttpClient client, URI endpoint, String key, String model, Duration timeout, long retryDelayMs)
            throws AgentException {
        if (key == null || key.isBlank() || key.chars().anyMatch(c -> c < 33 || c > 126))
            throw new AgentException("Set a valid OPENAI_API_KEY environment variable.");
        if (model == null || model.isBlank() || model.length() > 100)
            throw new AgentException("OPENAI_MODEL must be a nonempty model name.");
        this.client = client;
        this.endpoint = endpoint;
        this.key = key;
        this.model = model;
        this.timeout = timeout;
        this.retryDelayMs = retryDelayMs;
    }
    @Override
    public Reply respond(String instructions, List<Message> messages, List<Tool> tools) throws AgentException {
        boolean verification = tools.isEmpty();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("store", false);
        body.addProperty("max_completion_tokens", 1_500);
        JsonArray history = new JsonArray();
        history.add(JSON.toJsonTree(Map.of("role", "system", "content", instructions)));
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            if (message.content() != null) item.addProperty("content", message.content());
            if (message.callId() != null) item.addProperty("tool_call_id", message.callId());
            if (message.call() != null) {
                ToolCall call = message.call();
                item.add("tool_calls", JSON.toJsonTree(List.of(Map.of("id", call.id(), "type", "function",
                        "function", Map.of("name", call.name(), "arguments", call.arguments())))));
            }
            history.add(item);
        }
        body.add("messages", history);
        JsonArray definitions = new JsonArray();
        for (Tool tool : tools) {
            definitions.add(JSON.toJsonTree(Map.of("type", "function", "function", Map.of(
                    "name", tool.name(), "description", tool.description(), "strict", true,
                    "parameters", Map.of("type", "object", "properties", Map.of(tool.argument(), Map.of("type", "string")),
                            "required", List.of(tool.argument()), "additionalProperties", false)))));
        }
        if (!tools.isEmpty()) {
            body.add("tools", definitions);
            body.addProperty("tool_choice", "auto");
            body.addProperty("parallel_tool_calls", false);
        }
        body.add("response_format", JSON.toJsonTree(verification
                ? Map.of("type", "json_schema", "json_schema", Map.of("name", "semantic_verdict", "strict", true,
                        "schema", Map.of("type", "object", "properties", Map.of("verdict", Map.of("type", "string",
                                "enum", List.of("SUPPORTED", "UNSUPPORTED_EVIDENCE", "INCONSISTENT_CALCULATION",
                                        "SEMANTIC_FAILURE"))), "required", List.of("verdict"), "additionalProperties", false)))
                : Map.of("type", "json_schema", "json_schema", Map.of(
                        "name", "grounded_answer", "strict", true, "schema", Map.of("type", "object", "properties", Map.of(
                                "answer", Map.of("type", "string"),
                                "basis", Map.of("type", "string", "enum", List.of("DOCUMENT", "CONVERSATION", "CALCULATION", "NOT_FOUND")),
                                "evidenceIds", Map.of("type", "array", "items", Map.of("type", "string"))),
                                "required", List.of("answer", "basis", "evidenceIds"), "additionalProperties", false)))));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return parse(response.body(), verification);
                if (attempt == 3 || !(status == 429 || status >= 500 && status <= 599)) {
                    LOG.warning("api_failure status=" + status);
                    throw new AgentException("OpenAI request failed with HTTP " + status + ". Check model access, credentials, or quota.");
                }
            } catch (IOException e) {
                if (attempt == 3) {
                    LOG.warning("api_network_failure");
                    throw new AgentException("OpenAI network request failed after three attempts.", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException("OpenAI request was interrupted.", e);
            }
            LOG.info("api_retry attempt=" + (attempt + 1));
            try {
                Thread.sleep(retryDelayMs * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException("OpenAI retry was interrupted.", e);
            }
        }
        throw new AgentException("OpenAI retry limit reached.");
    }
    private Reply parse(String body, boolean verification) throws AgentException {
        try {
            if (body.length() > 100_000) throw new AgentException("OpenAI response exceeded the supported size.");
            JsonObject choice = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
            String finish = string(choice, "finish_reason");
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has("refusal") && !message.get("refusal").isJsonNull())
                throw new AgentException("The model declined this request.");
            if ("tool_calls".equals(finish)) {
                JsonArray calls = message.getAsJsonArray("tool_calls");
                if (calls.size() != 1) throw new AgentException("The model must request one tool at a time.");
                JsonObject call = calls.get(0).getAsJsonObject();
                if (!"function".equals(string(call, "type"))) throw new AgentException("Unsupported model tool type.");
                JsonObject function = call.getAsJsonObject("function");
                return new ToolCall(string(call, "id"), string(function, "name"), string(function, "arguments"));
            }
            if (!"stop".equals(finish)) throw new AgentException("The model response was incomplete or filtered.");
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull())
                throw new AgentException("The model returned inconsistent tool metadata.");
            JsonObject answer = JsonParser.parseString(string(message, "content")).getAsJsonObject();
            if (verification) {
                if (answer.size() != 1) throw new IllegalArgumentException();
                String verdict = string(answer, "verdict");
                if (!List.of("SUPPORTED", "UNSUPPORTED_EVIDENCE", "INCONSISTENT_CALCULATION", "SEMANTIC_FAILURE")
                        .contains(verdict)) throw new IllegalArgumentException();
                return new Verification(verdict);
            }
            if (answer.size() != 3) throw new IllegalArgumentException();
            List<String> ids = new ArrayList<>();
            for (JsonElement id : answer.getAsJsonArray("evidenceIds")) {
                if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !id.getAsString().matches("S[0-9]{1,4}"))
                    throw new IllegalArgumentException();
                ids.add(id.getAsString());
            }
            if (ids.size() > 12) throw new IllegalArgumentException();
            return new Answer(string(answer, "answer"), Basis.valueOf(string(answer, "basis")), ids);
        } catch (RuntimeException e) {
            throw new AgentException("OpenAI returned an invalid structured response.", e);
        }
    }
    private static String string(JsonObject object, String name) {
        if (!object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isString())
            throw new IllegalArgumentException();
        return object.get(name).getAsString();
    }
}
