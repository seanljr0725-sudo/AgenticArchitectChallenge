package com.example.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out, System.err));
    }
    static int run(String[] args, Map<String, String> env, BufferedReader input, PrintStream out, PrintStream err) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.println("Usage: java -jar target/document-agent.jar [document-path]");
            out.println("Required: OPENAI_API_KEY. Optional: OPENAI_MODEL (default gpt-4.1-mini). Type /exit to quit.");
            return 0;
        }
        if (args.length > 1) { err.println("Usage: java -jar target/document-agent.jar [document-path]"); return 2; }
        String key = env.get("OPENAI_API_KEY");
        if (key == null || key.isBlank()) { err.println("Set OPENAI_API_KEY before running the agent."); return 2; }
        try {
            var retriever = new DocumentRetriever(Path.of(args.length == 1 ? args[0] : "documents/sample-document.txt"));
            var model = new OpenAiLanguageModel(key, env.getOrDefault("OPENAI_MODEL", "gpt-4.1-mini"));
            return converse(new Agent(model, new ConversationMemory(), retriever, new CalculatorTool()), input, out, err);
        } catch (InvalidPathException e) {
            err.println("Error: Invalid document path.");
            return 1;
        } catch (AgentException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }
    static int converse(Agent agent, BufferedReader input, PrintStream out, PrintStream err) {
        out.println("Fictional handbook agent. Memory lasts for this session. Type /exit to quit.");
        try {
            while (true) {
                out.print("You: "); out.flush();
                String line = input.readLine();
                if (line == null || line.strip().equalsIgnoreCase("/exit")) return 0;
                if (line.isBlank()) continue;
                try { out.println("Agent: " + agent.chat(line)); }
                catch (AgentException e) {
                    err.println("Error: " + e.getMessage());
                    if (Thread.currentThread().isInterrupted()) return 1;
                }
            }
        } catch (IOException e) {
            err.println("Error: Could not read conversation input.");
            return 1;
        }
    }
}
