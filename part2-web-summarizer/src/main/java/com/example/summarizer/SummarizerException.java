package com.example.summarizer;

/** An expected failure that the CLI can explain without a stack trace. */
public class SummarizerException extends Exception {
    public SummarizerException(String message) {
        super(message);
    }

    public SummarizerException(String message, Throwable cause) {
        super(message, cause);
    }
}
