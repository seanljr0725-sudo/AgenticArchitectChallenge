package com.example.agent;

import java.util.List;

/** Provider-independent conversation and structured tool-call protocol. */
@FunctionalInterface
public interface LanguageModel {
    Reply respond(String instructions, List<Message> messages, List<Tool> tools) throws AgentException;

    record Tool(String name, String description, String argument) {}
    sealed interface Reply permits ToolCall, Answer, Verification {}
    record ToolCall(String id, String name, String arguments) implements Reply {}
    record Verification(String verdict) implements Reply {}
    enum Basis { DOCUMENT, CONVERSATION, CALCULATION, NOT_FOUND }
    record Answer(String text, Basis basis, List<String> evidenceIds) implements Reply {
        public Answer { evidenceIds = List.copyOf(evidenceIds); }
    }
    record Message(String role, String content, ToolCall call, String callId) {
        public static Message user(String text) { return new Message("user", text, null, null); }
        public static Message assistant(String text) { return new Message("assistant", text, null, null); }
        public static Message request(ToolCall call) { return new Message("assistant", null, call, null); }
        public static Message result(String id, String text) { return new Message("tool", text, null, id); }
        int size() {
            return (content == null ? 0 : content.length()) + (callId == null ? 0 : callId.length())
                    + (call == null ? 0 : call.id().length() + call.name().length() + call.arguments().length());
        }
    }
}
