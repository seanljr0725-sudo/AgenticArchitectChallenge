# Part 3: Fictional handbook conversational agent

A small Java 17+ CLI agent with one local document, bounded conversation memory,
and a deterministic calculator. Part 3 is a separate Maven project; it does not
use or change Part 1 or Part 2.

## Build, test, and run

Requirements: Java 17+, Maven 3.9+, and an OpenAI API key for live conversations.
The only runtime dependency is Gson; tests use JUnit 5 and local HTTP fixtures.
There is no agent framework, server, browser, database, or vector service.

```sh
cd part3-document-agent
mvn test
mvn clean verify
export OPENAI_API_KEY='your-api-key'
export OPENAI_MODEL='gpt-4.1-mini'  # optional; this is the default
java -jar target/document-agent.jar
```

The model must support Chat Completions, strict function calling, and structured
JSON responses. Use a model available to your account. `.env.example` documents
the variables; `.env` is ignored but is **not loaded automatically**.
Never commit your API key. Live conversations send retained conversation context
and retrieved document passages to OpenAI and may incur charges.

Run from the module directory, or supply an explicit document path:

```sh
java -jar target/document-agent.jar documents/sample-document.txt
java -jar target/document-agent.jar --help
```

Type `/exit` or send EOF to quit. Expected per-turn failures print a safe error
and let you try again. Startup errors exit nonzero; missing arguments/configuration
may exit with code 2. Help and normal session exit return 0.

On the assessment machine, if Maven is not on PATH, use:

```sh
/private/tmp/apache-maven-3.9.11/bin/mvn -Dmaven.repo.local=.cache/m2 clean verify
```

## Architecture and flow

| File | Responsibility |
| --- | --- |
| `Main` | Load environment and document, wire components, run CLI, show safe errors. |
| `Agent` | Supply capability descriptions, coordinate model/tool loop, validate arguments, enforce budgets and answer basis. |
| `LanguageModel` | Provider-independent records for messages, tool calls, and answers; replaceable with a test fake. |
| `OpenAiLanguageModel` | Serialize API requests, parse structured responses, apply HTTP timeouts and bounded retries. |
| `AnswerValidator` and `ModelAnswerValidator` | Check a draft's relevance, document source, and calculation provenance with one tool-free model call. |
| `ConversationMemory` | Retain completed turns and their evidence; evict entire old turns when limits are reached. |
| `DocumentRetriever` | Load one text file, split paragraphs into sections, rank matching evidence. |
| `CalculatorTool` | Parse and evaluate bounded arithmetic without executing code. |
| `AgentException` | Expected failures with user-safe messages. |

A turn follows this loop:

1. Combine retained conversation history with the new user message.
2. Send instructions and tool descriptions to the LLM with `tool_choice: auto`.
3. If the model requests `document_search(query)` or `calculator(expression)`,
   validate the tool name and JSON argument, execute it, and append the assistant
   tool call and corresponding tool result with the same call ID.
4. Ask the LLM again. It can select another tool, repair an invalid argument,
   refine a search, or finish. Calls are sequential, not parallel.
5. Validate the final answer's basis and evidence metadata. For DOCUMENT,
   CONVERSATION, and CALCULATION drafts, make one tool-free model verification call. Display a
   verified answer and commit
   the completed turn to memory. Abort safely if four tool calls are exhausted.

There is **no keyword routing** based on the user's message. The Java switch
only dispatches a structured tool name already selected by the model. See
[official OpenAI function calling documentation](https://developers.openai.com/api/docs/guides/function-calling)
for the call/result protocol. The implementation uses the
[Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
through Java's built-in HTTP client to keep provider code visible and small.

## Retrieval and grounding

The fictional handbook contains annual leave, medical leave, remote work,
training allowance, working hours, equipment, and reimbursement policies.
It is explicitly sample data, not a real company handbook or legal guidance.

The retriever reads at most 100 KB, splits on blank lines, and further splits long
paragraphs into chunks of at most 1,000 Java characters. Sections receive stable
IDs (`S1`, `S2`, etc.) for that loaded document. Search lowercases and tokenizes
text, removes common question words, counts matching terms, and returns at most
three ranked passages. At least half of the meaningful query terms must match.
There are no embeddings and no network calls in retrieval. Lexical matching here
is retrieval ranking, not agent tool selection.

Instructions require document-specific answers to use retrieved tool evidence,
not pretrained company knowledge or user assertions. Previous user names are
conversation facts; retrieved policy passages are document evidence. Document
content is untrusted data and must not override the agent's instructions.

Final responses use a strict JSON schema internally: `answer`, `basis`, and
`evidenceIds`. `basis` distinguishes DOCUMENT, CONVERSATION, CALCULATION, and
NOT_FOUND. The CLI displays plain text, plus application-appended section IDs
for document answers. A DOCUMENT answer needs cited IDs from retrieved or
retained evidence. A CALCULATION answer with citations and a current calculator
result is checked as a document-derived calculation, even if the model used the
CALCULATION basis label. Valid citations must refer to available evidence; the
validator then checks the cited source value, calculator expression and result,
and final answer together. Pure arithmetic uses CALCULATION without citations.
Search history alone never makes a CONVERSATION answer require document citations,
and an unnecessary no-hit search does not itself force NOT_FOUND.

For DOCUMENT, CONVERSATION, or CALCULATION drafts, a separate, tool-free model
call checks whether the draft addresses the current user message and fits its
claimed basis. This call uses a dedicated, constrained verdict schema. Document
claims must be supported by the cited passages; derived
arithmetic must match the supplied calculator expression and deterministic result.
The calculator result, rather than the model's own arithmetic, is authoritative.
Conversation answers may use the
current message and recent conversational context but cannot assert unsupported
handbook facts. A citation-free CALCULATION draft must be standalone arithmetic;
the verifier rejects a policy-derived calculation whose handbook citation is
missing. An unsupported document or calculation draft is withheld with a safe error;
an unsupported conversational draft uses the fixed NOT_FOUND response. Invalid
or failed verification also fails safely. Verification adds at most one model
request after the ordinary tool loop. Its judgment is probabilistic and is not
a proof of semantic entailment.

A search with no evidence results in a fixed response:

> The requested information was not found in the provided document.

The model can try a better query within the tool-call limit. A final NOT_FOUND
answer also always uses that fixed response. Candidate passages can share terms
without supporting a claim; the model is instructed to check actual support.
Reference validation is deterministic, but semantic entailment and correct
classification remain model responsibilities. This is not a mathematical
hallucination guarantee or a full prompt-injection defense.

## Memory

Memory is **in-memory only and resets when the process exits**. It retains at
most eight completed turns and 60,000 characters across messages and evidence.
The history includes tool calls/results so follow-ups can use the source amount
and distinguish it from a user assertion. Whole turns are evicted together,
preventing orphaned tool messages. Older personal facts, including a name, can
be forgotten after eviction. Lost document evidence should be retrieved again.
Failed turns are not committed, preventing incomplete tool sequences from
polluting the next request. No database, disk persistence, or LLM summary of
memory is used.

## Calculator safety

The calculator is a small recursive-descent parser using `BigDecimal` and
DECIMAL128 (34 significant digits). It supports `+`, `-`, `*`, `/`, decimals,
unary signs, parentheses, and the display symbols `×`, `÷`, and `−`.
Multiplication/division bind more tightly than addition/subtraction.

It does not use `eval`, JavaScript, reflection, shell execution, variables,
functions, exponent notation, or currency-formatted numbers. Inputs are limited
to 200 characters, numeric literals to 40 characters, and nesting to 20 levels.
Division by zero and malformed expressions return safe tool errors; final
magnitudes above 10^100 are rejected. Recurring decimal results are rounded;
this is demonstration arithmetic, not an accounting engine.

Calculations belong in code because numeric correctness should not depend on
probabilistic text generation. The model chooses the operation and explains the
result; Java computes it. Correct selection of operands and faithful wording
of the final answer still depend on the model.

## Reliability and observability

| Limit or failure | Behavior |
| --- | --- |
| User message | 1–2,000 characters. |
| Tool calls | Four per turn, one at a time; at most five agent-loop model requests. |
| Answer verification | At most one additional tool-free model request for a DOCUMENT, CONVERSATION, or CALCULATION draft. |
| Tool arguments | Only the named string field; reject unknown tools, malformed JSON, extra fields, and oversized values. |
| Model request | 10-second connection timeout and 45-second request timeout; 1,500 output-token cap. |
| API retries | Three total attempts for I/O failures, HTTP 429 or 5xx; delays of 0.5 and 1 second. |
| API refusals/incomplete/malformed responses | Fail safely; do not retry as valid answers. |
| Tool failure | Send a safe error result to the model for correction within the same budget. |
| Unresolved final tool failure | Reject fabricated success and report a safe error. |
| Missing/empty/oversized document or missing key | Fail before conversation begins. |
| Interruptions | Restore interrupt status and stop the CLI. |

`java.util.logging` emits turn start/completion, allowlisted tool names,
tool success/failure, retrieval success/no-evidence, API retry/failure, and
maximum-step termination to stderr. Final validation logs only a reason code,
such as `invalid_evidence_id`, `unsupported_evidence`,
`inconsistent_calculation_provenance`, `semantic_failure`, or
`malformed_final_basis`. It does not log keys, prompts, expressions,
full documents, or conversation content. Raw provider error bodies are not shown.

Retries are bounded per request, so a full turn can make at most 18 HTTP attempts
(five agent-loop requests and one verification request, each with three tries).
There is no global wall-clock or cost budget; transient retries can duplicate
provider work. No `Retry-After` scheduling is implemented in this small demo.

## Tests

Normal `mvn test` and `mvn clean verify` use fake models and loopback HTTP servers.
They require **no API key, external websites, or live LLM calls**. Maven may need
internet access to obtain dependencies. Tests cover memory bounds and follow-ups,
retrieval/no-evidence, arithmetic/errors, tool selection protocols, sequential
retrieval/calculation, agent limits, safe argument validation, CLI configuration,
HTTP retries/timeouts/interruption, structured response parsing, unnecessary
search recovery, rejection of an unrelated cited answer, and calculator-only
follow-ups that reuse retained handbook evidence without another search.

Scripted model tests prove orchestration and verifier handling for specified model
outputs; they do not establish real-model tool-selection or verification accuracy.
Live OpenAI calls are intentionally excluded from automated verification.

## Example conversation

These are illustrative expected responses; wording depends on the configured model.

```text
You: My name is Sean.
Agent: Hello Sean.
You: How many days of annual leave do employees receive?
Agent: Employees receive 18 days of paid annual leave per year. [S2]
You: What is 25 × 16?
Agent: 400.
You: What is my name?
Agent: Sean.
You: How much annual training allowance does the document provide?
Agent: RM1,200 per year for approved professional training. [S5]
You: How much would that be over 4 years?
Agent: RM4,800, assuming the annual allowance remains unchanged. Unused allowance does not carry forward. [S5]
You: How much training allowance would I receive over 3 years?
Agent: RM3,600 over three years, assuming the annual allowance remains unchanged. [S5]
You: How many stock options do employees receive?
Agent: The requested information was not found in the provided document.
You: /exit
```

For the three-year question the model should call document_search followed by
calculator(`1200*3`). For the four-year follow-up, retained tool evidence can
supply the RM1,200 amount without a new search; calculator(`1200*4`) supplies 4,800.

## Intentional limitations

Single text document and one CLI conversation; English-oriented lexical search
without synonym expansion, stemming, or semantic embeddings; finite context;
no persistent memory; live-model behavior is not covered by automated tests. Ambiguous queries can
retrieve loosely related passages or miss useful wording. Future-year totals
are hypothetical arithmetic, not guarantees of future policy. No UI, web server,
authentication, multi-agent system, or production infrastructure is included.
