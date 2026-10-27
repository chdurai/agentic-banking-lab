package io.github.chdurai.agenticbanking.cases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.chdurai.agenticbanking.approval.Roles;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CaseLinkService {
    public record Page(List<CaseLink> items, String nextCursor) {}
    public record Evidence(CaseLink link, String sourceFlow, String sourceStatus, long sourceVersion,
                           boolean available, boolean verified, JsonNode facts, List<String> limitations) {}
    private final CaseStore store;
    private final AsyncCaseService cases;
    private final ObjectMapper json;
    private final PlatformEventPublisher events;

    public CaseLinkService(CaseStore store, AsyncCaseService cases, ObjectMapper json, PlatformEventPublisher events) {
        this.store = store;
        this.cases = cases;
        this.json = json;
        this.events = events;
    }

    public CaseLink.Creation create(String fromCase, String toCase, CaseLink.Type type) {
        var source = cases.get(fromCase);
        var target = cases.get(toCase);
        if (type == null || !type.sourceFlow().equals(source.flow()) || !type.targetFlow().equals(target.flow())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link type does not match its endpoint flows");
        }
        return store.link(fromCase, toCase, type);
    }

    public Page list(String caseId, String cursor, int limit) {
        cases.get(caseId);
        if (limit < 1 || limit > 100 || cursor != null && !cursor.matches("[a-fA-F0-9-]{36}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid link cursor or limit");
        }
        var rows = store.links(caseId, cursor, limit + 1);
        var selected = rows.stream().limit(limit).filter(this::visible).toList();
        return new Page(selected, rows.size() > limit ? rows.get(limit - 1).id() : null);
    }

    private boolean visible(CaseLink link) {
        var roles = CallerContext.current().roles();
        return roles.contains(Roles.PLATFORM_ADMIN) || roles.contains(Roles.forFlow(link.type().sourceFlow()))
                && roles.contains(Roles.forFlow(link.type().targetFlow()));
    }

    public Evidence evidence(String id) {
        var link = store.findLink(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        var source = cases.get(link.fromCase());
        cases.get(link.toCase());
        ObjectNode facts = json.createObjectNode();
        boolean available = source.resultJson() != null
                && List.of(CaseStatus.COMPLETED, CaseStatus.APPROVED, CaseStatus.REJECTED).contains(source.status());
        if (available) {
            JsonNode result;
            try { result = json.readTree(source.resultJson()); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Unreadable linked evidence", e); }
            switch (link.type()) {
                case LOAN_GRIEVANCE -> {
                    if (result.path("decision").asText().equals("COMPLIANCE_HOLD")) available = false;
                    else {
                        copy(result, facts, "decision", "policyVersion", "customerReasonCodes");
                    }
                }
                case DEPENDENCY_DEGRADED -> {
                    facts.put("signal", "DEPENDENCY_DEGRADED");
                    copy(result, facts, "status");
                    copy(result.path("triage"), facts, "severity");
                }
                case DOCUMENT_EVIDENCE -> {
                    JsonNode analysis = result.path("analysis");
                    if (!analysis.isObject() || analysis.path("containsPii").asBoolean(true)
                            || result.path("workflowQueue").asText().equals("restricted-review")) {
                        available = false;
                    } else {
                        copy(analysis, facts, "documentType", "confidencePct");
                        var numeric = facts.putObject("extractedNumericFields");
                        analysis.path("keyFields").properties().forEach(entry -> {
                            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
                            if (List.of("annualincome", "monthlyincome", "grosspay", "netpay", "total").contains(name)) {
                                String value = entry.getValue().asText().trim();
                                if (value.matches("\\$?\\d{1,3}(,\\d{3})*(\\.\\d{1,2})?|\\$?\\d{1,10}(\\.\\d{1,2})?")) {
                                    BigDecimal amount = new BigDecimal(value.replace("$", "").replace(",", ""));
                                    if (amount.compareTo(BigDecimal.valueOf(1_000_000_000)) <= 0) numeric.put(name, amount);
                                }
                            }
                        });
                    }
                }
            }
        }
        if (!available) facts.removeAll();
        events.publish(PlatformEvent.of("case.link.read", "case", link.toCase(),
                "linkId=" + link.id() + ";sourceVersion=" + source.version()));
        return new Evidence(link, source.flow(), source.status().name(), source.version(), available, false, facts,
                List.of("Read-only projection; not verified evidence or an authorization to act.",
                        "No source narrative, raw request, internal findings or pending-review recommendations are disclosed.",
                        "Linking does not invoke another flow or change a loan decision."));
    }

    private static void copy(JsonNode source, ObjectNode target, String... names) {
        for (String name : names) if (source.hasNonNull(name)) target.set(name, source.get(name).deepCopy());
    }
}
