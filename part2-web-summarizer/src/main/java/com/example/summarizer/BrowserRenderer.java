package com.example.summarizer;

import java.net.URI;

/**
 * Optional fallback. Return the DOM's HTML after JavaScript has rendered it.
 * Implementations must impose a timeout and close their browser resources.
 */
@FunctionalInterface
public interface BrowserRenderer {
    String render(URI url) throws SummarizerException;

    static BrowserRenderer disabled() {
        return url -> {
            throw new SummarizerException(
                    "Too little meaningful HTML content. The page may require JavaScript, "
                    + "authentication, or may simply be short. No browser renderer is configured.");
        };
    }
}
