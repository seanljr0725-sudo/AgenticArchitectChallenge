package com.example.summarizer;

import java.io.PrintStream;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.println("Usage: java -jar target/web-summarizer.jar <http-or-https-url>");
            out.println("Required: OPENAI_API_KEY. Optional: OPENAI_MODEL (default: gpt-4.1-mini).");
            return 0;
        }
        if (args.length != 1) {
            err.println("Usage: java -jar target/web-summarizer.jar <http-or-https-url>");
            return 2;
        }
        try {
            WebsiteScraper.validateUrl(args[0]);
            String apiKey = environment.get("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                err.println("Set OPENAI_API_KEY before running the application.");
                return 2;
            }
            String modelName = environment.getOrDefault("OPENAI_MODEL", "gpt-4.1-mini");
            if (modelName.isBlank()) {
                err.println("OPENAI_MODEL must not be blank.");
                return 2;
            }
            ContentCleaner cleaner = new ContentCleaner();
            WebsiteScraper scraper = new WebsiteScraper(cleaner, new PlaywrightBrowserRenderer(cleaner));
            LanguageModel model = new OpenAiLanguageModel(apiKey, modelName);
            Summarizer summarizer = new Summarizer(model, new TextChunker(6_000));
            String content = scraper.scrape(args[0]);
            String summary = summarizer.summarize(content);
            out.println(summary);
            return 0;
        } catch (SummarizerException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
