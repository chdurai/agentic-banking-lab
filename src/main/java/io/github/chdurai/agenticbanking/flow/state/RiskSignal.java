package io.github.chdurai.agenticbanking.flow.state;

/** A coded contribution to risk. The scalar score is derived from signals, not self-reported (WI-01). */
public record RiskSignal(String code, String description, int weight, String source) {}
