package com.example.agent;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.agent.AnswerValidator.*;
import static com.example.agent.LanguageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTest {
    private Agent agent(LanguageModel model, ConversationMemory memory) throws Exception {
        // Orchestration tests supply a verifier stub; verifier behavior is tested separately below.
        return new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), (current, draft, citations, history, calculations) -> Verdict.SUPPORTED);
    }
    private static ToolCall search(String id, String query) {
        return new ToolCall(id, "document_search", "{\"query\":\"" + query + "\"}");
    }
    private static ToolCall calculate(String id, String expression) {
        return new ToolCall(id, "calculator", "{\"expression\":\"" + expression + "\"}");
    }
    private static Answer answer(String text, Basis basis, String... ids) { return new Answer(text, basis, List.of(ids)); }
    private static LanguageModel script(Reply... replies) {
        AtomicInteger next = new AtomicInteger();
        return (instructions, messages, tools) -> replies[next.getAndIncrement()];
    }
    @Test void modelChoosesCalculatorWithoutDocumentSearch() throws Exception {
        var memory = new ConversationMemory();
        Agent agent = agent(script(calculate("c1", "25*16"), answer("400", Basis.CALCULATION)), memory);
        assertEquals("400", agent.chat("What is 25 × 16?"));
        assertEquals(List.of("calculator"), memory.messages().stream().filter(m -> m.call() != null).map(m -> m.call().name()).toList());
        assertTrue(memory.messages().stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("400")));
        assertTrue(memory.evidence().isEmpty());
    }
    @Test void standaloneCalculationPassesWithoutHandbookCitation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> calculate("c1", "25*16");
            case 1 -> answer("25 times 16 is 400.", Basis.CALCULATION);
            default -> {
                assertTrue(tools.isEmpty());
                yield new Verification("SUPPORTED");
            }
        };
        Agent agent = new Agent(model, new ConversationMemory(),
                new DocumentRetriever(Path.of("documents/sample-document.txt")), new CalculatorTool());
        assertEquals("25 times 16 is 400.", agent.chat("What is 25 times 16?"));
        assertEquals(3, calls.get());
    }
    @Test void modelChoosesDocumentSearchWithoutCalculator() throws Exception {
        var memory = new ConversationMemory();
        Agent agent = agent(script(search("s1", "annual leave"), answer("Employees receive 18 days per year.", Basis.DOCUMENT, "S2")), memory);
        assertEquals("Employees receive 18 days per year. [S2]", agent.chat("How many days of annual leave?"));
        assertEquals(List.of("document_search"), memory.messages().stream().filter(m -> m.call() != null).map(m -> m.call().name()).toList());
    }
    @Test void executesSearchThenCalculationAndFeedsResultsBack() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> {
            assertEquals(List.of("document_search", "calculator"), tools.stream().map(Tool::name).toList());
            return switch (calls.getAndIncrement()) {
                case 0 -> search("s1", "professional training allowance");
                case 1 -> {
                    assertTrue(messages.get(messages.size()-1).content().contains("RM1,200"));
                    assertEquals("s1", messages.get(messages.size()-1).callId());
                    yield calculate("c1", "1200*3");
                }
                default -> {
                    assertTrue(messages.get(messages.size()-1).content().contains("3600"));
                    yield answer("RM3,600 over three years, assuming the annual allowance stays unchanged.", Basis.DOCUMENT, "S5");
                }
            };
        };
        assertTrue(agent(model, new ConversationMemory()).chat("Training allowance over 3 years?").contains("RM3,600"));
        assertEquals(3, calls.get());
    }
    @Test void remembersNameAndUsesPreviousEvidenceForFollowUp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> answer("Hello Sean.", Basis.CONVERSATION);
            case 1 -> {
                assertTrue(messages.stream().anyMatch(m -> "My name is Sean.".equals(m.content())));
                yield answer("Sean.", Basis.CONVERSATION);
            }
            case 2 -> search("s1", "professional training allowance");
            case 3 -> answer("RM1,200 per year.", Basis.DOCUMENT, "S5");
            case 4 -> {
                assertTrue(messages.stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("RM1,200")));
                assertTrue(messages.stream().anyMatch(m -> "assistant".equals(m.role()) && m.content() != null && m.content().contains("RM1,200")));
                yield calculate("c1", "1200*4");
            }
            default -> answer("RM4,800, assuming the annual allowance remains unchanged.", Basis.DOCUMENT, "S5");
        };
        Agent agent = agent(model, new ConversationMemory());
        agent.chat("My name is Sean.");
        assertEquals("Sean.", agent.chat("What is my name?"));
        agent.chat("How much annual training allowance does the document provide?");
        assertTrue(agent.chat("How much would that be over 4 years?").contains("RM4,800"));
        assertEquals(6, calls.get());
    }
    @Test void retainsS5ForThreeYearCalculatorOnlyFollowUp() throws Exception {
        assertRetainedTrainingCalculation(3, "How much would that be over 3 years?", "3600", "RM3,600");
    }
    @Test void retainsS5ForExplicitFourYearDocumentCalculation() throws Exception {
        assertRetainedTrainingCalculation(4,
                "According to the document, how much professional training allowance would I receive over 4 years?",
                "4800", "RM4,800");
    }
    private void assertRetainedTrainingCalculation(int years, String question, String number, String amount) throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger validatorCalls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (modelCalls.getAndIncrement()) {
            case 0 -> search("s1", "professional training allowance");
            case 1 -> answer("Employees receive RM1,200 per year for approved professional training.", Basis.DOCUMENT, "S5");
            case 2 -> {
                assertTrue(messages.stream().anyMatch(message -> "tool".equals(message.role())
                        && message.content().contains("RM1,200")));
                yield calculate("c1", "1200*" + years);
            }
            default -> answer(amount + " over " + years + " years, assuming the allowance remains unchanged.",
                    Basis.CALCULATION, "S5");
        };
        AnswerValidator validator = (current, draft, citations, history, calculations) -> {
            validatorCalls.incrementAndGet();
            assertEquals(Basis.DOCUMENT, draft.basis(), "A cited calculation has document provenance");
            assertEquals(List.of("S5"), citations.stream().map(DocumentRetriever.Evidence::id).toList());
            assertTrue(citations.get(0).text().contains("RM1,200"));
            if (calculations.isEmpty()) return Verdict.SUPPORTED; // First turn: document fact only.
            assertEquals(question, current);
            assertEquals(List.of(new Calculation("1200*" + years, number)), calculations);
            assertTrue(history.stream().anyMatch(message -> message.content() != null
                    && message.content().contains("RM1,200 per year")));
            return Verdict.SUPPORTED;
        };
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), validator);
        assertTrue(agent.chat("How much professional training allowance do employees receive per year?").endsWith("[S5]"));
        assertTrue(memory.evidence().containsKey("S5"));
        assertEquals(amount + " over " + years + " years, assuming the allowance remains unchanged. [S5]",
                agent.chat(question));
        assertEquals(4, modelCalls.get(), "The second turn must use only calculator and retained evidence");
        assertEquals(2, validatorCalls.get());
        assertEquals(1, memory.messages().stream().filter(message -> message.call() != null
                && "document_search".equals(message.call().name())).count());
    }
    @Test void recoversPrematureFourYearCalculationUsingRetainedS5() throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        String question = "According to the document, how much professional training allowance would I receive over 4 years?";
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> search("s1", "professional training allowance");
            case 1 -> answer("Employees receive RM1,200 per year for approved training.", Basis.DOCUMENT, "S5");
            case 2 -> calculate("c3", "1200*3");
            case 3 -> answer("RM3,600 over 3 years.", Basis.CALCULATION, "S5");
            case 4 -> {
                assertFalse(instructions.contains("Your proposed final answer requires arithmetic"));
                assertTrue(messages.stream().anyMatch(m -> m.call() != null && "calculator".equals(m.call().name()) && "1200*3".equals(
                        JsonParser.parseString(m.call().arguments()).getAsJsonObject().get("expression").getAsString())));
                yield answer("RM4,800 over 4 years.", Basis.CALCULATION, "S5");
            }
            case 5 -> {
                assertTrue(instructions.contains("Your proposed final answer requires arithmetic"));
                assertEquals(List.of("document_search", "calculator"), tools.stream().map(Tool::name).toList());
                assertEquals(question, messages.get(messages.size() - 1).content());
                yield calculate("c4", "1200*4");
            }
            default -> {
                assertFalse(instructions.contains("Your proposed final answer requires arithmetic"));
                assertTrue(messages.get(messages.size() - 1).content().contains("4800"));
                yield answer("RM4,800 over 4 years.", Basis.CALCULATION, "S5");
            }
        };
        AnswerValidator validator = (current, draft, citations, history, calculations) -> {
            assertEquals(List.of("S5"), citations.stream().map(DocumentRetriever.Evidence::id).toList());
            if (current.equals(question)) {
                assertEquals(List.of(new Calculation("1200*4", "4800")), calculations,
                        "The previous 1200*3 result cannot authorize the new answer");
            }
            return Verdict.SUPPORTED;
        };
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), validator);
        agent.chat("How much professional training allowance do employees receive per year?");
        agent.chat("How much would that be over 3 years?");
        assertEquals("RM4,800 over 4 years. [S5]", agent.chat(question));
        assertEquals(7, calls.get());
        assertEquals(1, memory.messages().stream().filter(m -> m.call() != null
                && "document_search".equals(m.call().name())).count());
        assertEquals(2, memory.messages().stream().filter(m -> m.call() != null
                && "calculator".equals(m.call().name())).count());
    }
    @Test void repeatedPrematureCalculationFailsAfterOneRecovery() throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> search("s1", "professional training allowance");
            case 1 -> answer("RM1,200 per year.", Basis.DOCUMENT, "S5");
            case 2 -> answer("RM4,800 over 4 years.", Basis.CALCULATION, "S5");
            default -> {
                assertTrue(instructions.contains("Your proposed final answer requires arithmetic"));
                yield answer("RM4,800 over 4 years.", Basis.CALCULATION, "S5");
            }
        };
        Agent agent = agent(model, memory);
        agent.chat("How much training allowance is available?");
        AgentException failure = assertThrows(AgentException.class, () -> agent.chat("Training over 4 years?"));
        assertTrue(failure.getMessage().contains("Could not verify"));
        assertEquals(4, calls.get());
        assertEquals(1, memory.messages().stream().filter(m -> "assistant".equals(m.role()) && m.content() != null).count());
    }
    @Test void documentLabeledDerivedValueRecoversWhenVerifierFindsMissingCalculator() throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> search("s1", "professional training allowance");
            case 1 -> answer("RM1,200 per year.", Basis.DOCUMENT, "S5");
            case 2 -> answer("RM4,800 over 4 years.", Basis.DOCUMENT, "S5");
            case 3 -> {
                assertTrue(instructions.contains("Your proposed final answer requires arithmetic"));
                yield calculate("c4", "1200*4");
            }
            default -> answer("RM4,800 over 4 years.", Basis.DOCUMENT, "S5");
        };
        AnswerValidator validator = (current, draft, citations, history, calculations) ->
                current.contains("4 years") && calculations.isEmpty()
                        ? Verdict.INCONSISTENT_CALCULATION : Verdict.SUPPORTED;
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), validator);
        agent.chat("How much training allowance is available?");
        assertEquals("RM4,800 over 4 years. [S5]", agent.chat("Training over 4 years?"));
        assertEquals(5, calls.get());
    }
    @Test void recoversWhenCurrentTurnCalculatorDoesNotEstablishProposedResult() throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> search("s1", "professional training allowance");
            case 1 -> answer("RM1,200 per year.", Basis.DOCUMENT, "S5");
            case 2 -> calculate("c3", "1200*3");
            case 3 -> answer("RM4,800 over 4 years.", Basis.CALCULATION, "S5");
            case 4 -> {
                assertTrue(instructions.contains("lacks a matching deterministic"));
                yield calculate("c4", "1200*4");
            }
            default -> answer("RM4,800 over 4 years.", Basis.CALCULATION, "S5");
        };
        AnswerValidator validator = (current, draft, citations, history, calculations) -> {
            if (current.contains("4 years"))
                return calculations.contains(new Calculation("1200*4", "4800"))
                        ? Verdict.SUPPORTED : Verdict.INCONSISTENT_CALCULATION;
            return Verdict.SUPPORTED;
        };
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), validator);
        agent.chat("How much training allowance is available?");
        assertEquals("RM4,800 over 4 years. [S5]", agent.chat("Training over 4 years?"));
        assertEquals(6, calls.get());
    }
    @Test void verifierCanRequestCalculatorForMislabelledConversationalArithmetic() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> answer("25 times 16 is 400.", Basis.CONVERSATION);
            case 1 -> {
                assertTrue(instructions.contains("lacks a matching deterministic"));
                yield calculate("c1", "25*16");
            }
            default -> answer("25 times 16 is 400.", Basis.CALCULATION);
        };
        AnswerValidator validator = (current, draft, citations, history, calculations) ->
                calculations.isEmpty() ? Verdict.INCONSISTENT_CALCULATION : Verdict.SUPPORTED;
        Agent agent = new Agent(model, new ConversationMemory(),
                new DocumentRetriever(Path.of("documents/sample-document.txt")), new CalculatorTool(), validator);
        assertEquals("25 times 16 is 400.", agent.chat("What is 25 times 16?"));
        assertEquals(3, calls.get());
    }
    @Test void rejectsForgedCitationOnCitedCalculation() throws Exception {
        Agent agent = agent(script(calculate("c1", "1200*3"),
                answer("RM3,600 over three years.", Basis.CALCULATION, "S999")), new ConversationMemory());
        assertEquals(Agent.NOT_FOUND, agent.chat("What is the training allowance over three years?"));
    }
    @Test void rejectsCitationAfterItsEvidenceTurnIsEvicted() throws Exception {
        var memory = new ConversationMemory(1, 60_000);
        Agent agent = agent(script(search("s1", "professional training allowance"),
                answer("RM1,200 per year.", Basis.DOCUMENT, "S5"),
                answer("Hello.", Basis.CONVERSATION),
                calculate("c1", "1200*3"),
                answer("RM3,600 over three years.", Basis.CALCULATION, "S5")), memory);
        agent.chat("How much training allowance is available?");
        assertTrue(memory.evidence().containsKey("S5"));
        agent.chat("Hello");
        assertFalse(memory.evidence().containsKey("S5"));
        assertEquals(Agent.NOT_FOUND, agent.chat("How much would that be over three years?"));
    }
    @Test void rejectsPolicyDerivedCalculationWithoutCitation() throws Exception {
        var memory = new ConversationMemory();
        LanguageModel model = script(search("s1", "professional training allowance"),
                answer("RM1,200 per year.", Basis.DOCUMENT, "S5"),
                calculate("c1", "1200*3"), answer("RM3,600 over three years.", Basis.CALCULATION));
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), (current, draft, citations, history, calculations) ->
                draft.basis() == Basis.CALCULATION ? Verdict.UNSUPPORTED_EVIDENCE : Verdict.SUPPORTED);
        agent.chat("How much training allowance is available?");
        assertThrows(AgentException.class, () -> agent.chat("How much would that be over three years?"));
    }
    @Test void rejectsIncorrectFinalAmountDespiteValidS5AndCalculatorResult() throws Exception {
        var memory = new ConversationMemory();
        LanguageModel model = script(search("s1", "professional training allowance"),
                answer("RM1,200 per year.", Basis.DOCUMENT, "S5"),
                calculate("c1", "1200*3"), answer("RM9,999 over three years.", Basis.CALCULATION, "S5"),
                answer("RM9,999 over three years.", Basis.CALCULATION, "S5"));
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool(), (current, draft, citations, history, calculations) -> {
                    if (calculations.isEmpty()) return Verdict.SUPPORTED;
                    assertEquals(List.of(new Calculation("1200*3", "3600")), calculations);
                    return Verdict.INCONSISTENT_CALCULATION;
                });
        agent.chat("How much training allowance is available?");
        assertThrows(AgentException.class, () -> agent.chat("How much would that be over three years?"));
    }
    @Test void handlesNameIntroductionAndRecallWithoutToolsUsingProductionVerifier() throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> answer("Nice to meet you, Sean.", Basis.CONVERSATION);
            case 1 -> {
                assertTrue(tools.isEmpty());
                yield new Verification("SUPPORTED");
            }
            case 2 -> {
                assertTrue(messages.stream().anyMatch(message -> "My name is Sean.".equals(message.content())));
                yield answer("Your name is Sean.", Basis.CONVERSATION);
            }
            default -> {
                assertTrue(tools.isEmpty());
                yield new Verification("SUPPORTED");
            }
        };
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool());
        assertEquals("Nice to meet you, Sean.", agent.chat("My name is Sean."));
        assertEquals("Your name is Sean.", agent.chat("What is my name?"));
        assertEquals(4, calls.get());
        assertTrue(memory.messages().stream().noneMatch(message -> message.call() != null || "tool".equals(message.role())));
    }
    @Test void rejectsObservedUnrelatedDocumentAnswerAfterTwoSearches() throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        LanguageModel model = (instructions, messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> search("s1", "annual leave");
            case 1 -> search("s2", "paid annual leave");
            case 2 -> answer("Employees receive 18 days of paid annual leave per year.", Basis.DOCUMENT, "S2");
            default -> {
                assertTrue(tools.isEmpty(), "Verification must not offer tools");
                assertTrue(messages.get(0).content().contains("My name is Sean."));
                yield new Verification("SEMANTIC_FAILURE");
            }
        };
        Agent agent = new Agent(model, memory, new DocumentRetriever(Path.of("documents/sample-document.txt")),
                new CalculatorTool());
        AgentException failure = assertThrows(AgentException.class, () -> agent.chat("My name is Sean."));
        assertTrue(failure.getMessage().contains("Could not verify"));
        assertEquals(4, calls.get());
        assertTrue(memory.messages().isEmpty(), "A rejected answer must not enter conversation memory");
    }
    @Test void allowsConversationAnswerAfterUnnecessarySearchWithEvidence() throws Exception {
        var memory = new ConversationMemory();
        Agent agent = new Agent(script(search("s1", "annual leave"), answer("Nice to meet you, Sean.", Basis.CONVERSATION),
                new Verification("SUPPORTED")), memory,
                new DocumentRetriever(Path.of("documents/sample-document.txt")), new CalculatorTool());
        assertEquals("Nice to meet you, Sean.", agent.chat("My name is Sean."));
        assertEquals("My name is Sean.", memory.messages().get(0).content());
        assertEquals("Nice to meet you, Sean.", memory.messages().get(memory.messages().size() - 1).content());
    }
    @Test void allowsConversationAnswerAfterUnnecessaryNoHitSearch() throws Exception {
        var memory = new ConversationMemory();
        Agent agent = new Agent(script(search("s1", "stock options"), answer("Nice to meet you, Sean.", Basis.CONVERSATION),
                new Verification("SUPPORTED")), memory,
                new DocumentRetriever(Path.of("documents/sample-document.txt")), new CalculatorTool());
        assertEquals("Nice to meet you, Sean.", agent.chat("My name is Sean."));
        assertTrue(memory.evidence().isEmpty());
    }
    @Test void refusesFabricationAfterNoEvidenceEvenIfModelMislabelsAnswer() throws Exception {
        Agent agent = new Agent(script(search("s1", "stock options"), answer("You get 500 shares.", Basis.CONVERSATION),
                new Verification("UNSUPPORTED_EVIDENCE")), new ConversationMemory(),
                new DocumentRetriever(Path.of("documents/sample-document.txt")), new CalculatorTool());
        assertEquals(Agent.NOT_FOUND, agent.chat("How many stock options do I receive?"));
    }
    @Test void refusesUnretrievedOrForgedCitations() throws Exception {
        assertEquals(Agent.NOT_FOUND, agent(script(answer("18 days.", Basis.DOCUMENT, "S2")), new ConversationMemory()).chat("Annual leave?"));
        assertEquals(Agent.NOT_FOUND, agent(script(search("s1", "annual leave"), answer("99 days.", Basis.DOCUMENT, "S999")),
                new ConversationMemory()).chat("Annual leave?"));
        assertEquals(Agent.NOT_FOUND, agent(script(search("s1", "medical leave"), answer("Unsupported fact.", Basis.NOT_FOUND)),
                new ConversationMemory()).chat("Does medical leave cover my relative?"));
    }
    @Test void stopsAtFourToolCallsAndDoesNotCommitFailedTurn() throws Exception {
        var memory = new ConversationMemory();
        AtomicInteger calls = new AtomicInteger();
        Agent agent = agent((i, m, t) -> calculate("c" + calls.incrementAndGet(), "1+1"), memory);
        assertTrue(assertThrows(AgentException.class, () -> agent.chat("Keep calculating")).getMessage().contains("four"));
        assertEquals(5, calls.get());
        assertTrue(memory.messages().isEmpty());
    }
    @Test void safelyReturnsToolErrorsAndAllowsCorrection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Agent agent = agent((i, messages, t) -> switch (calls.getAndIncrement()) {
            case 0 -> calculate("c1", "1/0");
            case 1 -> {
                var output = JsonParser.parseString(messages.get(messages.size()-1).content()).getAsJsonObject();
                assertFalse(output.get("ok").getAsBoolean());
                assertTrue(output.get("error").getAsString().contains("zero"));
                yield calculate("c2", "1/2");
            }
            default -> answer("0.5", Basis.CALCULATION);
        }, new ConversationMemory());
        assertEquals("0.5", agent.chat("Calculate something"));
    }
    @Test void rejectsUnsafeArgumentsUnknownToolsAndDuplicateIds() throws Exception {
        for (ToolCall call : List.of(new ToolCall("c1", "calculator", "{\"expression\":5}"),
                new ToolCall("c1", "calculator", "{\"expression\":\"1+1\",\"extra\":true}"),
                new ToolCall("c1", "calculator", "not JSON"), new ToolCall("c1", "shell", "{}"))) {
            assertThrows(AgentException.class, () -> agent(script(call, answer("made up", Basis.CONVERSATION)),
                    new ConversationMemory()).chat("Test"));
        }
        assertThrows(AgentException.class, () -> agent(script(calculate("same", "1+1"), calculate("same", "1+1")),
                new ConversationMemory()).chat("Test"));
    }
    @Test void handlesModelFailureAndOversizedInputWithoutCorruptingMemory() throws Exception {
        var memory = new ConversationMemory();
        memory.remember(List.of(Message.user("Sean"), Message.assistant("Hi")), java.util.Map.of());
        Agent agent = agent((i,m,t) -> { throw new AgentException("Provider unavailable."); }, memory);
        assertThrows(AgentException.class, () -> agent.chat("Hi"));
        assertThrows(AgentException.class, () -> agent.chat("x".repeat(2001)));
        assertEquals(2, memory.messages().size());
    }
}
