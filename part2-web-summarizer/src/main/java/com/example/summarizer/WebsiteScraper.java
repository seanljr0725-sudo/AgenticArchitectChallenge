package com.example.summarizer;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class WebsiteScraper {
    static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private final ContentCleaner cleaner;
    private final BrowserRenderer renderer;

    public WebsiteScraper(ContentCleaner cleaner, BrowserRenderer renderer) {
        this.cleaner = cleaner;
        this.renderer = renderer;
    }

    public String scrape(String url) throws SummarizerException {
        URI uri = validateUrl(url);
        String text = cleaner.clean(fetchHtml(uri));
        if (cleaner.hasMeaningfulContent(text)) {
            return text;
        }

        // Render at most once and run the same cleaner/quality check again.
        String renderedHtml = renderer.render(uri);
        if (renderedHtml == null || renderedHtml.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new SummarizerException("Browser renderer returned empty or oversized HTML.");
        }
        text = cleaner.clean(renderedHtml);
        if (!cleaner.hasMeaningfulContent(text)) {
            throw new SummarizerException("Too little meaningful content, even after browser rendering.");
        }
        return text;
    }

    public static URI validateUrl(String value) throws SummarizerException {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new SummarizerException("Provide a valid HTTP or HTTPS URL without embedded credentials; "
                    + "an explicit port must be between 1 and 65535.");
        }
    }

    private String fetchHtml(URI uri) throws SummarizerException {
        try {
            Connection.Response response = Jsoup.connect(uri.toString())
                    .userAgent("WebSummarizer/1.0")
                    .timeout(15_000)
                    .maxBodySize(MAX_HTML_BYTES + 1)
                    .followRedirects(true)
                    .execute();
            // Request one extra byte so oversized pages are rejected, not silently truncated.
            if (response.bodyAsBytes().length > MAX_HTML_BYTES) {
                throw new SummarizerException("Webpage exceeds the 2 MiB HTML limit.");
            }
            String contentType = response.contentType();
            if (contentType != null && !contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) {
                throw new SummarizerException("The URL did not return an HTML webpage.");
            }
            return response.body();
        } catch (HttpStatusException e) {
            throw new SummarizerException("Webpage request failed with HTTP " + e.getStatusCode() + ".", e);
        } catch (UnsupportedMimeTypeException e) {
            throw new SummarizerException("The URL did not return an HTML webpage.", e);
        } catch (IOException e) {
            throw new SummarizerException("Could not fetch the webpage. Check the URL and network connection.", e);
        } catch (IllegalArgumentException e) {
            throw new SummarizerException("The webpage URL or a redirect URL is invalid.", e);
        }
    }
}
