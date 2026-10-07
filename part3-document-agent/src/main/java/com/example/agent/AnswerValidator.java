package com.example.agent;

import java.util.List;

/** Checks whether a draft's claimed basis fits the current request and available evidence. */
@FunctionalInterface
interface AnswerValidator {
    record Calculation(String expression, String result) {}
    enum Verdict { SUPPORTED, UNSUPPORTED_EVIDENCE, INCONSISTENT_CALCULATION, SEMANTIC_FAILURE }

    Verdict supports(String currentMessage, LanguageModel.Answer draft,
                     List<DocumentRetriever.Evidence> citations,
                     List<LanguageModel.Message> conversation, List<Calculation> calculations) throws AgentException;
}
