package com.example.agent;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.agent.AnswerValidator.*;
import static com.example.agent.LanguageModel.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelAnswerValidatorTest {
    private static final DocumentRetriever.Evidence LEAVE =
            new DocumentRetriever.Evidence("S2", "Employees receive 18 days of paid annual leave per year.");

    @Test void acceptsOnlyAnExplicitSupportedVerdictWithMatchingCitations() throws Exception {
        LanguageModel model = (instructions, messages, tools) -> {
            assertTrue(tools.isEmpty());
            assertTrue(instructions.contains("CURRENT user message"));
            var data = JsonParser.parseString(messages.get(0).content()).getAsJsonObject();
            assertEquals("How much annual leave do employees receive?", data.get("currentUserMessage").getAsString());
            assertEquals("18 days per year.", data.get("draftAnswer").getAsString());
            assertEquals("S2", data.getAsJsonArray("citedEvidence").get(0).getAsJsonObject().get("id").getAsString());
            return new Verification("SUPPORTED");
        };
        assertEquals(Verdict.SUPPORTED, new ModelAnswerValidator(model).supports("How much annual leave do employees receive?",
                new Answer("18 days per year.", Basis.DOCUMENT, List.of("S2")), List.of(LEAVE), List.of(), List.of()));
    }

    @Test void rejectsUnrelatedDraftWhenVerifierSaysUnsupported() throws Exception {
        LanguageModel model = (instructions, messages, tools) -> new Verification("SEMANTIC_FAILURE");
        assertEquals(Verdict.SEMANTIC_FAILURE, new ModelAnswerValidator(model).supports("My name is Sean.",
                new Answer("Employees receive 18 days of paid annual leave per year.", Basis.DOCUMENT, List.of("S2")),
                List.of(LEAVE), List.of(), List.of()));
    }

    @Test void rejectsMismatchedSourceReferencesBeforeSemanticApproval() {
        var verifier = new ModelAnswerValidator((instructions, messages, tools) -> new Verification("SUPPORTED"));
        assertThrows(AgentException.class, () -> verifier.supports("How much annual leave?",
                new Answer("18 days.", Basis.DOCUMENT, List.of("S5")), List.of(LEAVE), List.of(), List.of()));
    }

    @Test void suppliesRecentConversationAndCalculatorResultForPolicyFollowUps() throws Exception {
        LanguageModel model = (instructions, messages, tools) -> {
            var data = JsonParser.parseString(messages.get(0).content()).getAsJsonObject();
            var calculation = data.getAsJsonArray("calculations").get(0).getAsJsonObject();
            assertEquals("1200*4", calculation.get("expression").getAsString());
            assertEquals("4800", calculation.get("result").getAsString());
            assertEquals("RM1,200 per year.", data.getAsJsonArray("recentConversation").get(1)
                    .getAsJsonObject().get("content").getAsString());
            return new Verification("SUPPORTED");
        };
        var allowance = new DocumentRetriever.Evidence("S5", "RM1,200 per year for approved training.");
        assertEquals(Verdict.SUPPORTED, new ModelAnswerValidator(model).supports("How much would that be over 4 years?",
                new Answer("RM4,800 over four years, assuming the allowance stays unchanged.", Basis.DOCUMENT, List.of("S5")), List.of(allowance),
                List.of(Message.user("What is the training allowance?"), Message.assistant("RM1,200 per year.")),
                List.of(new Calculation("1200*4", "4800"))));
    }

    @Test void checksConversationBasisWithoutDemandingDocumentEvidence() throws Exception {
        LanguageModel model = (instructions, messages, tools) -> {
            var data = JsonParser.parseString(messages.get(0).content()).getAsJsonObject();
            assertEquals("CONVERSATION", data.get("draftBasis").getAsString());
            assertTrue(data.getAsJsonArray("citedEvidence").isEmpty());
            return new Verification("SUPPORTED");
        };
        assertEquals(Verdict.SUPPORTED, new ModelAnswerValidator(model).supports("My name is Sean.",
                new Answer("Nice to meet you, Sean.", Basis.CONVERSATION, List.of()), List.of(), List.of(), List.of()));
    }

    @Test void suppliesDeterministicCalculationForStandaloneArithmetic() throws Exception {
        LanguageModel model = (instructions, messages, tools) -> {
            assertTrue(tools.isEmpty());
            var data = JsonParser.parseString(messages.get(0).content()).getAsJsonObject();
            assertEquals("CALCULATION", data.get("draftBasis").getAsString());
            assertTrue(data.getAsJsonArray("citedEvidence").isEmpty());
            var calculation = data.getAsJsonArray("calculations").get(0).getAsJsonObject();
            assertEquals("25*16", calculation.get("expression").getAsString());
            assertEquals("400", calculation.get("result").getAsString());
            return new Verification("SUPPORTED");
        };
        assertEquals(Verdict.SUPPORTED, new ModelAnswerValidator(model).supports("What is 25 times 16?",
                new Answer("25 times 16 is 400.", Basis.CALCULATION, List.of()), List.of(), List.of(),
                List.of(new Calculation("25*16", "400"))));
    }

    @Test void reportsInconsistentCalculationWithoutAcceptingTheDraft() throws Exception {
        LanguageModel model = (instructions, messages, tools) ->
                new Verification("INCONSISTENT_CALCULATION");
        assertEquals(Verdict.INCONSISTENT_CALCULATION, new ModelAnswerValidator(model).supports(
                "How much would that be over three years?",
                new Answer("RM9,999 over three years.", Basis.DOCUMENT, List.of("S5")),
                List.of(new DocumentRetriever.Evidence("S5", "RM1,200 per year for training.")), List.of(),
                List.of(new Calculation("1200*3", "3600"))));
    }

    @Test void identifiesMissingCalculatorForDocumentLabeledDerivedValue() throws Exception {
        LanguageModel model = (instructions, messages, tools) -> {
            assertTrue(tools.isEmpty());
            assertTrue(instructions.contains("even when the draft basis is DOCUMENT"));
            var data = JsonParser.parseString(messages.get(0).content()).getAsJsonObject();
            assertEquals("DOCUMENT", data.get("draftBasis").getAsString());
            assertTrue(data.getAsJsonArray("calculations").isEmpty());
            assertEquals("S5", data.getAsJsonArray("citedEvidence").get(0)
                    .getAsJsonObject().get("id").getAsString());
            return new Verification("INCONSISTENT_CALCULATION");
        };
        assertEquals(Verdict.INCONSISTENT_CALCULATION, new ModelAnswerValidator(model).supports(
                "How much allowance over 4 years?",
                new Answer("RM4,800 over 4 years.", Basis.DOCUMENT, List.of("S5")),
                List.of(new DocumentRetriever.Evidence("S5", "RM1,200 per year for approved training.")),
                List.of(), List.of()));
    }

    @Test void rejectsUnsupportedPolicyClaimMislabelledAsConversation() throws Exception {
        LanguageModel model = (instructions, messages, tools) -> new Verification("UNSUPPORTED_EVIDENCE");
        assertEquals(Verdict.UNSUPPORTED_EVIDENCE, new ModelAnswerValidator(model).supports("How many stock options does the handbook provide?",
                new Answer("You get 500 shares.", Basis.CONVERSATION, List.of()), List.of(), List.of(), List.of()));
    }

    @Test void failsSafelyForUnexpectedVerdictsOrToolCalls() {
        for (Reply reply : List.of(
                new Verification("maybe"),
                new Answer("SUPPORTED", Basis.DOCUMENT, List.of("S2")),
                new ToolCall("c1", "document_search", "{}"))) {
            var verifier = new ModelAnswerValidator((instructions, messages, tools) -> reply);
            assertThrows(AgentException.class,
                    () -> verifier.supports("Question?", new Answer("Draft", Basis.DOCUMENT, List.of("S2")),
                            List.of(LEAVE), List.of(), List.of()));
        }
    }

    @Test void propagatesVerifierFailureInsteadOfAcceptingDraft() {
        var verifier = new ModelAnswerValidator((instructions, messages, tools) -> {
            throw new AgentException("Provider unavailable");
        });
        assertThrows(AgentException.class,
                () -> verifier.supports("Question?", new Answer("Draft", Basis.DOCUMENT, List.of("S2")),
                        List.of(LEAVE), List.of(), List.of()));
    }
}
