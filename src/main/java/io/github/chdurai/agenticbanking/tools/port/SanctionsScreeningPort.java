package io.github.chdurai.agenticbanking.tools.port;

import java.util.List;

public interface SanctionsScreeningPort extends ToolPort {

    enum MatchStatus {
        CLEAR, POTENTIAL_MATCH, CONFIRMED_MATCH
    }

    record ScreeningRequest(String fullName, String dateOfBirth, String countryCode) {
    }

    record ScreeningResult(
            String name,
            MatchStatus status,
            double score,
            List<String> matchedList,
            String evidenceId) {

        public boolean sanctionsMatch() {
            return status == MatchStatus.CONFIRMED_MATCH;
        }
    }

    ScreeningResult screen(ScreeningRequest request);

    @Override
    default String id() {
        return "sanctions.screen";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.NONE;
    }
}
