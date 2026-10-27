package io.github.chdurai.agenticbanking.tools;

import io.github.chdurai.agenticbanking.tools.port.SanctionsScreeningPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mock sanctions/PEP screening. In production, wire to OFAC SDN, EU/UN lists, or a vendor (e.g. Dow Jones, World-Check).
 */
@Component
public class SanctionsScreeningTools implements SanctionsScreeningPort {

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final ToolCallAuditor auditor;

    public SanctionsScreeningTools() {
        this(io.github.chdurai.agenticbanking.capability.CapabilityGuard.permissive(),
                new ToolCallAuditor(new io.github.chdurai.agenticbanking.ops.PlatformEventPublisher(event -> { })));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SanctionsScreeningTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities, ToolCallAuditor auditor) {
        this.capabilities = capabilities;
        this.auditor = auditor;
    }

    private record WatchlistHit(String name, String countryCode, String dateOfBirth, String source) {
    }

    private static final Map<String, WatchlistHit> WATCHLIST = Map.of(
            "OFAC-SDN-001", new WatchlistHit("Ivan Borislav", "BG", "1980-04-12", "OFAC-SDN"),
            "OFAC-SDN-002", new WatchlistHit("Test Sanctioned", "US", "1975-01-01", "OFAC-SDN"));

    @Tool(description = "Screen a person against OFAC SDN and consolidated sanctions watchlists by full name. "
            + "Returns typed status, score and evidence id.")
    public ScreeningResult screenSanctions(String fullName) {
        return screenSanctions(fullName, null, null);
    }

    @Tool(description = "Screen sanctions using name plus optional DOB/country to disambiguate fuzzy matches.")
    public ScreeningResult screenSanctions(String fullName, String dateOfBirth, String countryCode) {
        return screen(new ScreeningRequest(fullName, dateOfBirth, countryCode));
    }

    @Override
    public ScreeningResult screen(ScreeningRequest request) {
        capabilities.checkTool("sanctions.screen");
        return auditor.audited("sanctions.screen", request, () -> {
            String candidate = normalize(request.fullName());
            double best = 0.0;
            String evidenceId = "NONE";
            String matched = null;
            for (Map.Entry<String, WatchlistHit> entry : WATCHLIST.entrySet()) {
                WatchlistHit hit = entry.getValue();
                double score = tokenScore(candidate, normalize(hit.name()));
                if (request.dateOfBirth() != null && request.dateOfBirth().equals(hit.dateOfBirth())) {
                    score += 0.10;
                }
                if (request.countryCode() != null
                        && request.countryCode().equalsIgnoreCase(hit.countryCode())) {
                    score += 0.10;
                }
                if (score > best) {
                    best = score;
                    evidenceId = entry.getKey();
                    matched = hit.name() + " (" + hit.source() + ")";
                }
            }
            MatchStatus status = best >= 0.92 ? MatchStatus.CONFIRMED_MATCH
                    : best >= 0.70 ? MatchStatus.POTENTIAL_MATCH : MatchStatus.CLEAR;
            return new ScreeningResult(
                    request.fullName(),
                    status,
                    round(best),
                    matched == null ? List.of() : List.of(matched),
                    status == MatchStatus.CLEAR ? "NONE" : evidenceId + "-" + UUID.nameUUIDFromBytes(
                            (candidate + ":" + evidenceId).getBytes()).toString().substring(0, 8));
        });
    }

    private static double tokenScore(String candidate, String watchlistName) {
        Set<String> c = tokens(candidate);
        Set<String> w = tokens(watchlistName);
        if (c.isEmpty() || w.isEmpty()) {
            return 0.0;
        }
        long overlap = c.stream().filter(w::contains).count();
        if (overlap == 0) {
            return 0.0;
        }
        return overlap / (double) Math.max(c.size(), w.size());
    }

    private static Set<String> tokens(String value) {
        return Set.copyOf(java.util.Arrays.stream(value.split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet()));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT).trim();
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
