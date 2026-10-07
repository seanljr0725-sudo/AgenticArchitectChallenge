# Part 2: Webpage summarizer

A small Java 17+ command-line application. It fetches and cleans HTML, summarizes
bounded portions independently, combines the notes, and returns a summary of at
most **150 words**. Maven builds a runnable JAR.

## Run

Install Java 17+ and Maven 3.9+, then:

```sh
cd part2-web-summarizer
mvn clean verify
java -cp target/web-summarizer.jar com.microsoft.playwright.CLI install chromium
export OPENAI_API_KEY='your-api-key'
java -jar target/web-summarizer.jar 'https://example.com/an-article'
```

Use a substantive article URL; the short example.com landing page intentionally
fails the minimum-content check. Set `OPENAI_MODEL` to override `gpt-4.1-mini` with
a Responses-compatible text model available to your account. Credentials are
read from environment variables and are never printed or committed. `.env`
files are ignored by Git but are **not** loaded automatically.

```sh
export OPENAI_MODEL='gpt-4.1-mini'
java -jar target/web-summarizer.jar --help
mvn test
```

Successful runs write only the summary to stdout. Errors go to stderr. Exit codes
are `0` for success/help, `2` for missing arguments/configuration, and `1` for
invalid URLs (including invalid ports) or fetch, extraction, or summarization failures. Scraped text is sent
to OpenAI; live runs require an API key and may incur charges.

## How the code fits together

The classes are under `src/main/java/com/example/summarizer/`.

| Class | Responsibility |
| --- | --- |
| `Main` | Read arguments/environment, wire dependencies, print results/errors. |
| `WebsiteScraper` | Validate the URL, fetch HTML with jsoup, invoke the fallback once if needed. |
| `ContentCleaner` | Remove common boilerplate; prefer substantive `article`, then `main`, then body text. |
| `BrowserRenderer` | Small interface for JavaScript rendering. |
| `PlaywrightBrowserRenderer` | Lazily launch Chromium, wait for meaningful rendered content, close resources. |
| `TextChunker` | Split text into bounded chunks at whitespace where possible. |
| `LanguageModel` | Provider interface, also used by deterministic test fakes. |
| `OpenAiLanguageModel` | Call the Responses API, parse text, handle bounded transport retries. |
| `Summarizer` | Summarize each chunk, reduce combined notes, generate the final summary. |
| `SummaryGuardrail` | Count words, request compression, revalidate, reject persistent overflow. |
| `SummarizerException` | Carry expected failures to the CLI without a stack trace. |

Runtime libraries are jsoup for HTML, Gson for JSON, and Playwright for browser rendering. There is no
application framework, dependency injection framework, or model SDK to learn.
The REST request and response parsing follow the
[official OpenAI text generation documentation](https://developers.openai.com/api/docs/guides/text).
The parser walks all message output items rather than assuming the first item
contains text. Source text is passed separately from the task instructions;
prompts tell the model to treat webpage instructions as untrusted data.

## Extraction and browser fallback

The cleaner removes navigation, site headers, footers, sidebars, scripts, styles,
forms, hidden elements, and common cookie/consent containers. Headers inside
`article`, `main`, or `[role=main]` retain their titles and metadata. Obvious inline
`display:none` and `visibility:hidden` declarations are removed, including common
case/spacing variations and `!important`. Cookie filtering
uses element IDs/classes, so an article discussing cookies is not removed just
because of its text. Stylesheet classes, CSS cascade/overrides, and unusual page
structures are not fully understood by this conservative static cleaner.

Text is considered sufficient when it contains at least 50 whitespace-separated
words and 200 letters. This is an explainable heuristic: short valid pages may
be rejected, and verbose login/challenge pages may pass. A tiny teaser `article`
does not prevent trying a fuller `main` or body.

If normal extraction is insufficient, `WebsiteScraper` invokes `BrowserRenderer`
once, cleans its returned DOM HTML, and checks content again. HTTP errors (such
as 403/404), timeouts, and non-HTML responses fail directly. Rendering is not used
to bypass access controls.

The CLI injects `PlaywrightBrowserRenderer`. It starts headless Chromium only
when the static path is insufficient; constructing the adapter starts nothing.
It allows 15 seconds for browser launch, 15 seconds for navigation to
`DOMContentLoaded`, then 10 seconds for the existing cleaner to find meaningful
content in the rendered DOM. It does not depend on network idleness. The returned
HTML passes through the scraper's size check and cleaning/validation again.
The context (including its pages), browser, and Playwright instance are closed
with try-with-resources on both success and failure. There are no browser retries.
Browser errors are converted to safe application errors without raw page details.

Install Chromium once with the command above, and again after a Playwright upgrade.
The application disables automatic browser downloads during requests. Static
pages still work without Chromium installed. On Linux, system dependencies may
also be needed; see the [official browser installation instructions](https://playwright.dev/java/docs/browsers).

Browser rendering adds installation size, RAM, and startup time. The readiness
heuristic cannot guarantee that every late update has arrived; it returns once
sufficient cleaned content appears. It does not handle login walls, bot challenges,
interaction-dependent content, or all sites' asynchronous behavior.

## Limits and failure behavior

| Limit | Behavior |
| --- | --- |
| HTML fetch | 15-second jsoup timeout; bounded redirects; reject HTML larger than 2 MiB. |
| Browser fallback | One invocation; 15-second launch, 15-second navigation, 10-second readiness limits; automatic resource cleanup. |
| Chunk size | 6,000 Java characters; prefer whitespace boundaries, split exceptionally long tokens safely. |
| Initial chunks | Maximum 200; refuse larger input before any model calls. |
| Intermediate notes | Request 100 words per chunk; reject empty notes or notes over 2,000 characters. |
| Reduction | Re-chunk combined notes; at most 8 rounds, and each must shrink the text. |
| Model request | 10-second connection timeout and 45-second request timeout. |
| Transport retries | Up to 3 total attempts for I/O errors, HTTP 429, or 5xx; 0.5s then 1s backoff. |
| Final summary | At most 150 words, with at most 2 additional compression calls. |

Chunk sizes are conservative character bounds, **not exact token counts**. They
avoid a tokenizer dependency; a production service using a specific model could
substitute token-based chunking. Splitting at whitespace can divide sentences,
and summarizing intermediate notes can lose detail. No content is deliberately
truncated to make the final summary fit.

Words are defined consistently as non-whitespace tokens, including Unicode
whitespace handling. A 150-word result passes; 151 words triggers compression.
Compression aims for 120 words to leave headroom. The result is counted again
after every attempt. If it remains too long, the app returns an error and prints
no invalid summary. This word-count definition is intended for space-delimited
text and does not perform linguistic segmentation for every language.

The app processes chunks sequentially for simple control flow and predictable
request pressure. A failed chunk aborts the whole run, so it never silently
reports a partial summary as complete. Transport retries can still incur extra
provider cost if an earlier response was lost. There is no infinite retry loop.

This is a local CLI that fetches the URL the user supplies. Before exposing it as
a public service, add network destination/redirect restrictions, request-level
budgets, and authentication. A service accepting arbitrary URLs has different
requirements from this assessment CLI.

## Tests

Tests use fakes and local HTTP fixtures; they need no API key and make no live
LLM calls. They cover chunk preservation and bounds, 150/151-word boundaries,
compression exhaustion, HTML cleaning, browser fallback/revalidation, combining
long intermediate notes, and API retry/error handling. Maven needs internet
access on the first build to download dependencies.

The deterministic tests establish control-flow and length guarantees. They do
not establish summary quality on a live model or browser behavior on real sites.

The default `mvn test` run needs no browser installation. Fakes verify that sparse
HTML invokes the fallback exactly once and sufficient static HTML never invokes
it. Regression tests also cover invalid ports, article/main headers, and inline
hidden content. To additionally run the real Chromium integration tests against
local HTTP fixtures (no external websites or LLM calls):

```sh
mvn clean package
java -cp target/web-summarizer.jar com.microsoft.playwright.CLI install chromium
mvn -Dplaywright.tests=true clean verify
```

The opt-in tests cover delayed JavaScript content, cleaned-content readiness,
navigation/readiness timeouts, and browser HTTP errors.
