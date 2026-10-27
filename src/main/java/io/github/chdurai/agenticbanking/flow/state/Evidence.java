package io.github.chdurai.agenticbanking.flow.state;

/** Reference to something a finding relied on. */
public record Evidence(Type type, String ref, String hash, boolean verified) {
    public enum Type { TOOL, RAG, INPUT, FINDING }
}
