package com.example.summarizer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WebsiteScraperTest {
    @Test
    void usesNormalHtmlWithoutRendering() throws Exception {
        try (TestServer server = htmlServer("<article>" + ContentCleanerTest.ARTICLE + "</article>")) {
            WebsiteScraper scraper = new WebsiteScraper(new ContentCleaner(), uri -> {
                fail("Substantive HTML should not launch a browser");
                return "";
            });
            assertEquals(ContentCleanerTest.ARTICLE, scraper.scrape(server.uri().toString()));
        }
    }

    @Test
    void rendersSparseHtmlOnceThenCleansTheResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = htmlServer("<div id='app'>Loading...</div>")) {
            WebsiteScraper scraper = new WebsiteScraper(new ContentCleaner(), uri -> {
                assertEquals(server.uri(), uri);
                calls.incrementAndGet();
                return "<nav>menu</nav><main>" + ContentCleanerTest.ARTICLE + "</main>";
            });
            assertEquals(ContentCleanerTest.ARTICLE, scraper.scrape(server.uri().toString()));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void failsWhenRenderingIsDisabledOrStillInsufficient() throws Exception {
        try (TestServer server = htmlServer("<p>Loading...</p>")) {
            String url = server.uri().toString();
            var disabled = new WebsiteScraper(new ContentCleaner(), BrowserRenderer.disabled());
            assertTrue(assertThrows(SummarizerException.class, () -> disabled.scrape(url))
                    .getMessage().contains("No browser renderer"));
            AtomicInteger calls = new AtomicInteger();
            var sparse = new WebsiteScraper(new ContentCleaner(), uri -> {
                calls.incrementAndGet();
                return "<p>Still loading</p>";
            });
            assertThrows(SummarizerException.class, () -> sparse.scrape(url));
            assertEquals(1, calls.get());
            var broken = new WebsiteScraper(new ContentCleaner(), uri -> {
                throw new SummarizerException("Browser timed out");
            });
            assertEquals("Browser timed out", assertThrows(SummarizerException.class,
                    () -> broken.scrape(url)).getMessage());
        }
    }

    @Test
    void handlesHttpAndContentTypeErrorsWithoutRendering() throws Exception {
        WebsiteScraper scraper = new WebsiteScraper(new ContentCleaner(), uri -> {
            fail("HTTP and content-type failures must not invoke rendering");
            return "";
        });
        try (TestServer server = new TestServer(exchange ->
                TestServer.respond(exchange, 404, "text/html", "Not found"))) {
            assertTrue(assertThrows(SummarizerException.class,
                    () -> scraper.scrape(server.uri().toString())).getMessage().contains("404"));
        }
        try (TestServer server = new TestServer(exchange ->
                TestServer.respond(exchange, 200, "application/json", "{}"))) {
            assertThrows(SummarizerException.class, () -> scraper.scrape(server.uri().toString()));
        }
    }

    @Test
    void rejectsOversizedHtmlInsteadOfSummarizingTruncatedContent() throws Exception {
        try (TestServer server = htmlServer("<p>" + "x".repeat(2 * 1024 * 1024) + "</p>")) {
            WebsiteScraper scraper = new WebsiteScraper(new ContentCleaner(), BrowserRenderer.disabled());
            assertTrue(assertThrows(SummarizerException.class,
                    () -> scraper.scrape(server.uri().toString())).getMessage().contains("2 MiB"));
        }
    }

    @Test
    void rejectsInvalidAndNonHttpUrls() {
        for (String url : new String[]{"file:///etc/passwd", "ftp://example.com", "not a url", "https://user:pass@example.com"}) {
            assertThrows(SummarizerException.class, () -> WebsiteScraper.validateUrl(url));
        }
    }

    @Test
    void rejectsInvalidPortsBeforeFetchingOrRendering() {
        WebsiteScraper scraper = new WebsiteScraper(new ContentCleaner(), uri -> {
            fail("Invalid ports must not invoke the renderer");
            return "";
        });
        for (String port : new String[]{"99999", "65536", "0", "-1", "abc", "", "999999999999"}) {
            assertThrows(SummarizerException.class, () -> scraper.scrape("http://localhost:" + port + "/"));
        }
        assertDoesNotThrow(() -> WebsiteScraper.validateUrl("http://localhost:1/"));
        assertDoesNotThrow(() -> WebsiteScraper.validateUrl("http://localhost:65535/"));
        assertDoesNotThrow(() -> WebsiteScraper.validateUrl("http://[::1]:8080/"));
        assertDoesNotThrow(() -> WebsiteScraper.validateUrl("https://example.com/"));
    }

    private TestServer htmlServer(String html) throws Exception {
        return new TestServer(exchange -> TestServer.respond(exchange, 200, "text/html", html));
    }
}
