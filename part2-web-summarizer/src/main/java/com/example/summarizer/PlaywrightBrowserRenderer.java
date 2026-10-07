package com.example.summarizer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Lazy, one-shot Chromium fallback. Constructing this adapter does not start a browser. */
public final class PlaywrightBrowserRenderer implements BrowserRenderer {
    private final ContentCleaner cleaner;
    private final double navigationTimeoutMs;
    private final double readinessTimeoutMs;

    public PlaywrightBrowserRenderer(ContentCleaner cleaner) {
        this(cleaner, 15_000, 10_000);
    }

    // Shorter timeouts let local browser integration tests exercise failure paths quickly.
    PlaywrightBrowserRenderer(ContentCleaner cleaner, double navigationTimeoutMs, double readinessTimeoutMs) {
        if (!(navigationTimeoutMs > 0) || !Double.isFinite(navigationTimeoutMs)
                || !(readinessTimeoutMs > 0) || !Double.isFinite(readinessTimeoutMs)) {
            throw new IllegalArgumentException("Browser timeouts must be positive and finite.");
        }
        this.cleaner = cleaner;
        this.navigationTimeoutMs = navigationTimeoutMs;
        this.readinessTimeoutMs = readinessTimeoutMs;
    }

    @Override
    public String render(URI url) throws SummarizerException {
        // Install browsers explicitly, never download them during a summarization request.
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setTimeout(15_000));
             BrowserContext context = browser.newContext()) {
            // Closing the context closes its pages, including any popups, on every exit path.
            Page page = context.newPage();
            Response response = page.navigate(url.toString(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(navigationTimeoutMs));
            if (response == null || !response.ok()) {
                throw new SummarizerException("Browser webpage request failed"
                        + (response == null ? "." : " with HTTP " + response.status() + "."));
            }
            String[] html = {""};
            page.waitForCondition(() -> {
                html[0] = page.content();
                // Return oversized DOMs immediately so the caller can reject them safely.
                return html[0].getBytes(StandardCharsets.UTF_8).length > WebsiteScraper.MAX_HTML_BYTES
                        || cleaner.hasMeaningfulContent(cleaner.clean(html[0]));
            }, new Page.WaitForConditionOptions().setTimeout(readinessTimeoutMs));
            return html[0];
        } catch (TimeoutError e) {
            throw new SummarizerException("Browser rendering timed out while launching, navigating, "
                    + "or waiting for meaningful content.", e);
        } catch (RuntimeException e) {
            // Never print raw browser errors, which may contain URLs or page content.
            throw new SummarizerException("Browser rendering failed. Ensure Chromium is installed with "
                    + "'java -cp target/web-summarizer.jar com.microsoft.playwright.CLI install chromium' "
                    + "and the page is accessible.", e);
        }
    }
}
