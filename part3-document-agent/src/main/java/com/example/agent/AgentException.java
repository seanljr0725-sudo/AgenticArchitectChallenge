package com.example.agent;

/** Expected application failures; only the safe message is shown to the user. */
public class AgentException extends Exception {
    public AgentException(String message) { super(message); }
    public AgentException(String message, Throwable cause) { super(message, cause); }
}
