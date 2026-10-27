package io.github.chdurai.agenticbanking.approval;

import java.util.Map;

/** Serialisable description of what runs on approval; resolved by an {@link ApprovalActionHandler} (no closures). */
public record ActionSpec(String type, Map<String, String> params) {
    public ActionSpec {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
