package io.github.chdurai.agenticbanking.tools.port;

public interface PepScreeningPort extends ToolPort {

    record PepRequest(String fullName) {
    }

    record PepResult(String name, boolean isPep, String evidenceId) {
    }

    PepResult checkPep(PepRequest request);

    @Override
    default String id() {
        return "sanctions.pep";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.NONE;
    }
}
