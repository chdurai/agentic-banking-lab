package io.github.chdurai.agenticbanking.tools.port;

/** Marker interface for typed ports. */
public interface ToolPort {

    /** Stable catalog id used in capability allow-lists and audits. */
    String id();

    /** Declared side-effect class for governance. */
    SideEffect sideEffect();
}
