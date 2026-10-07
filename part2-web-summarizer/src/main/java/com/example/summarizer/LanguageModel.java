package com.example.summarizer;

/** Allows summarization tests to use a fake model without network calls or API costs. */
@FunctionalInterface
public interface LanguageModel {
    String generate(String instructions, String source) throws SummarizerException;
}
