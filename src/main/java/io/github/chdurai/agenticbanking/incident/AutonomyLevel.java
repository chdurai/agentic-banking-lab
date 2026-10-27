package io.github.chdurai.agenticbanking.incident;

/** Shared autonomy levels for flow gates. */
public enum AutonomyLevel {
    L0,
    L1,
    L2,
    L3;

    public boolean allowsAutomaticAction() {
        return this.ordinal() >= L2.ordinal();
    }
}
