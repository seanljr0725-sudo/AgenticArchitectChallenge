# Agentic Architect Challenge

This Developer Intern assessment submission has three parts: (1) a Customer Support Email Agent architecture, (2) a Java Website Summarizer, and (3) a Java document-based conversational Agent. The design emphasizes grounded AI responses, deterministic guardrails, tool use, graceful failure handling, modularity, observability, and human escalation where appropriate.

## Repository Structure

```text
.
├── part1-system-design/
│   └── system-design.md
├── part2-web-summarizer/
│   ├── pom.xml
│   ├── README.md
│   ├── src/main/java/com/example/summarizer/
│   └── src/test/java/com/example/summarizer/
├── part3-document-agent/
│   ├── pom.xml
│   ├── README.md
│   ├── .env.example
│   ├── documents/sample-document.txt
│   ├── src/main/java/com/example/agent/
│   └── src/test/java/com/example/agent/
└── docs/
    └── architecture-report.pdf
```

## Part 1 — Customer Support Email System

The [system design](part1-system-design/system-design.md) describes this proposed flow:

```text
Incoming Email → Classification Agent → Escalation Guardrail
→ RAG Knowledge Retrieval → Response Generation
→ Grounding/Policy Validation → Response or Human Review
```

Escalation happens **before normal drafting** for possible data loss, a service outage, a security breach, or more than three support contacts within seven days. An LLM can detect the first three conditions semantically; support-history counts are checked with deterministic code. For other cases, RAG retrieves approved internal FAQs, policies, and PDFs. The validator checks that material claims are supported by retrieved evidence. Unsupported policy claims, especially refund-policy claims, must not be invented and go to human review.

## Part 2 — Website Summarizer

The Java CLI follows a static-first path for speed and lower resource use:

```text
URL → Jsoup static fetch → content cleaning/extraction → content sufficiency check
→ Playwright/Chromium fallback when necessary → chunking
→ per-chunk summarization → reduction → final synthesis
→ summary-length validation
```

The browser starts only when cleaned static HTML lacks sufficient content. Input is bounded by a 2 MiB HTML limit, 6,000-character chunks, and a 200-chunk limit. Long pages use map-reduce summarization. A deterministic guardrail enforces a 150-word maximum, requesting compression at most twice before failing. Invalid URLs, HTTP errors, timeouts, unsupported content, browser failures, and model failures produce safe errors rather than partial summaries. See the [Part 2 details](part2-web-summarizer/README.md).

### Prerequisites

Java 17+, Maven 3.9+, and an OpenAI API key for live summaries. Install Playwright's Chromium once for dynamic-page fallback; static pages can run without it. Maven tests use local fixtures and fake models, so they need no API key.

### Build & Test

From the repository root:

```bash
cd part2-web-summarizer
mvn clean verify
```

The normal suite reports **51 tests: 47 passed and 4 skipped**. Browser integration tests are opt-in; after installing Chromium, run `mvn -Dplaywright.tests=true clean verify` to include them.

### Run

From `part2-web-summarizer/`, after building:

```bash
java -cp target/web-summarizer.jar com.microsoft.playwright.CLI install chromium
export OPENAI_API_KEY='your-api-key'
java -jar target/web-summarizer.jar '<http-or-https-article-url>'
```

Replace the URL placeholder with a real article that has enough content. `OPENAI_MODEL` is optional and defaults to `gpt-4.1-mini`.

## Part 3 — Document Agent

This Java CLI lets the LLM choose tools through structured tool calling. `document_search` retrieves passages from the supplied sample document with source IDs such as `S2`; `calculator` evaluates arithmetic deterministically. The agent can retrieve a document value and then calculate with it in sequential calls. It retains bounded conversation history and evidence, checks document answers against cited passages, validates calculation provenance, allows one bounded recovery when calculator evidence is missing or inconsistent, and fails safely when evidence is insufficient.

Lexical retrieval was intentionally chosen for one sample document: it is simple and explainable without vector-database infrastructure. It can be weaker when a question paraphrases the document's wording. See the [Part 3 details](part3-document-agent/README.md).

### Prerequisites

Java 17+, Maven 3.9+, and an OpenAI API key for live conversations. The model must support Chat Completions, strict function calling, and structured JSON responses. Offline tests need no API key.

### Build & Test

From the repository root:

```bash
cd part3-document-agent
mvn clean verify
```

The existing test reports record **88 passing tests**.

### Run

From `part3-document-agent/`, after building:

```bash
export OPENAI_API_KEY='your-api-key'
export OPENAI_MODEL='gpt-4.1-mini'
java -jar target/document-agent.jar
```

`gpt-4.1-mini` is the code's default model; the explicit setting above can be omitted. The default fictional handbook is [documents/sample-document.txt](part3-document-agent/documents/sample-document.txt). Run from the module directory so the default relative path resolves, or pass a document path as the JAR's argument. Type `/exit` to end the session.

## Architecture Report

A concise one-page report covering the architecture, engineering trade-offs, and key failure points across all three parts is available here:

[View the Architecture Report](docs/architecture-report.pdf)

## Reliability and Operational Thinking

The design uses bounded retries and explicit timeouts for external calls, deterministic validation for objective limits and arithmetic, grounded generation, and safe failures. Part 1 routes critical or unsupported cases to humans. Responsibilities are separated across classification, retrieval, tools, generation, and validation; Part 1 specifies operational event logging, while Part 3 emits structured event and reason-code logs. Credentials are never hardcoded. In addition to offline tests, live model evaluation in Part 3 exposed tool-selection and calculation-provenance issues that were subsequently addressed.

## Known Limitations

- Heuristic webpage extraction cannot identify the main content perfectly on every site; short valid pages may fail the content check.
- Browser-rendered sites may still fail behind authentication, anti-bot controls, or interactions the CLI does not perform.
- Lexical document retrieval may miss semantic paraphrases, and model-based evidence checking cannot prove semantic entailment.
- Part 3 conversation memory is in memory and resets when the CLI exits.
- Live AI operations depend on external API availability and model access.

## Security

Supply API credentials through environment variables. Never commit API keys or other secrets to Git. `.env` files are not loaded automatically by either CLI.

## Architecture Decisions

Use LLMs for semantic reasoning and flexible decisions, deterministic code for objective rules and validation, tools for external evidence and calculation, and human escalation or safe failure when a trustworthy answer cannot be established.
