package com.example.summarizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in local browser integration tests: mvn -Dplaywright.tests=true test. No LLM calls. */
@EnabledIfSystemProperty(named = "playwright.tests", matches = "true")
class PlaywrightBrowserRendererTest {
    private final ContentCleaner cleaner = new ContentCleaner();

    @Test
    void rendersDelayedJavaScriptContentThroughTheScraperAndCleaner() throws Exception {
        String html = "<nav>" + ContentCleanerTest.ARTICLE + "</nav><main id='app'>Loading...</main>"
                + "<script>setTimeout(() => { document.getElementById('app').innerHTML = "
                + "'<article><header>Energy findings</header><p>" + ContentCleanerTest.ARTICLE
                + "</p><p style=\"display:none\">hidden junk</p></article>'; }, 300);</script>";
        try (TestServer server = new TestServer(exchange -> TestServer.respond(exchange, 200, "text/html", html))) {
            var scraper = new WebsiteScraper(cleaner, new PlaywrightBrowserRenderer(cleaner));
            assertEquals("Energy findings " + ContentCleanerTest.ARTICLE, scraper.scrape(server.uri().toString()));
        }
    }

    @Test
    void reportsReadinessTimeoutSafely() throws Exception {
        try (TestServer server = new TestServer(exchange ->
                TestServer.respond(exchange, 200, "text/html", "<main>Loading forever...</main>"))) {
            var renderer = new PlaywrightBrowserRenderer(cleaner, 5_000, 300);
            SummarizerException failure = assertThrows(SummarizerException.class, () -> renderer.render(server.uri()));
            assertTrue(failure.getMessage().contains("timed out"));
            assertFalse(failure.getMessage().contains(server.uri().toString()));
        }
    }

    @Test
    void reportsNavigationTimeoutSafely() throws Exception {
        CountDownLatch releaseResponse = new CountDownLatch(1);
        try (TestServer server = new TestServer(exchange -> {
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        })) {
            var renderer = new PlaywrightBrowserRenderer(cleaner, 300, 300);
            try {
                SummarizerException failure = assertThrows(SummarizerException.class,
                        () -> renderer.render(server.uri()));
                assertTrue(failure.getMessage().contains("timed out"));
            } finally {
                releaseResponse.countDown();
            }
        }
    }

    @Test
    void reportsBrowserHttpFailureSafely() throws Exception {
        try (TestServer server = new TestServer(exchange ->
                TestServer.respond(exchange, 403, "text/html", "sensitive page details"))) {
            var renderer = new PlaywrightBrowserRenderer(cleaner);
            SummarizerException failure = assertThrows(SummarizerException.class, () -> renderer.render(server.uri()));
            assertTrue(failure.getMessage().contains("403"));
            assertFalse(failure.getMessage().contains("sensitive"));
        }
    }
}
