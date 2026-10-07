package com.example.summarizer;

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

/** Small Responses API adapter; transport retries are separate from summary compression. */
public final class OpenAiLanguageModel implements LanguageModel {
    private static final int MAX_ATTEMPTS = 3;
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration retryDelay;

    public OpenAiLanguageModel(String apiKey, String model) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create("https://api.openai.com/v1/responses"), apiKey, model, Duration.ofMillis(500));
    }

    // Package visibility permits a local HTTP server in tests without exposing CLI endpoint overrides.
    OpenAiLanguageModel(HttpClient client, URI endpoint, String apiKey, String model, Duration retryDelay) {
        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("An API key and model name are required.");
        }
        this.client = client;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.retryDelay = retryDelay;
    }

    @Override
    public String generate(String instructions, String source) throws SummarizerException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", instructions);
        body.addProperty("input", source);
        body.addProperty("max_output_tokens", 1024);
        body.addProperty("store", false);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return parseText(response.body());
                }
                boolean transientFailure = status == 429 || status >= 500;
                if (!transientFailure || attempt == MAX_ATTEMPTS) {
                    // Do not include raw provider responses: they may contain source text or secrets.
                    throw new SummarizerException("Language model request failed with HTTP " + status
                            + " after " + attempt + " attempt(s). Check API credentials, model access, or quota.");
                }
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new SummarizerException("Language model network request failed after three attempts.", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SummarizerException("Language model request was interrupted.", e);
            }
            pauseBeforeRetry(attempt);
        }
        throw new SummarizerException("Language model retry limit reached.");
    }

    private void pauseBeforeRetry(int attempt) throws SummarizerException {
        try {
            Thread.sleep(retryDelay.toMillis() * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SummarizerException("Language model retry was interrupted.", e);
        }
    }

    private String parseText(String response) throws SummarizerException {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            if (!"completed".equals(root.get("status").getAsString())) {
                throw new SummarizerException("Language model response was incomplete or failed.");
            }
            StringBuilder text = new StringBuilder();
            JsonArray output = root.getAsJsonArray("output");
            for (JsonElement item : output) {
                JsonObject message = item.getAsJsonObject();
                if (!"message".equals(message.get("type").getAsString())) {
                    continue;
                }
                for (JsonElement element : message.getAsJsonArray("content")) {
                    JsonObject content = element.getAsJsonObject();
                    String type = content.get("type").getAsString();
                    if ("refusal".equals(type)) {
                        throw new SummarizerException("The language model declined to summarize this content.");
                    }
                    if ("output_text".equals(type)) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(content.get("text").getAsString());
                    }
                }
            }
            if (SummaryGuardrail.countWords(text.toString()) == 0) {
                throw new SummarizerException("Language model response contained no summary text.");
            }
            return text.toString().strip();
        } catch (RuntimeException e) {
            throw new SummarizerException("Language model returned an invalid response.", e);
        }
    }
}
