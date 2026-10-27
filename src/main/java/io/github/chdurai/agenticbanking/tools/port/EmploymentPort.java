package io.github.chdurai.agenticbanking.tools.port;

public interface EmploymentPort extends ToolPort {

    record EmploymentRecord(String applicantId, String employer, boolean employed, int tenureMonths) {
    }

    EmploymentRecord employment(String applicantId);

    @Override
    default String id() {
        return "employment.lookup";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.NONE;
    }
}
