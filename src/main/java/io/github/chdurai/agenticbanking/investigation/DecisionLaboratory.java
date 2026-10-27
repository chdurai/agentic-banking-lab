package io.github.chdurai.agenticbanking.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.policy.LoanPolicyEngine;
import io.github.chdurai.agenticbanking.policy.LoanPolicyProperties;
import io.github.chdurai.agenticbanking.policy.PolicyEnvelope;

import java.math.BigDecimal;
import java.util.*;

/** Pure policy counterfactuals over verified frozen inputs. No gateway, adapters or case writers. */
public final class DecisionLaboratory {
    private final InvestigationStore evidence;
    private final ObjectMapper json;

    public DecisionLaboratory(InvestigationStore evidence, ObjectMapper json) {
        this.evidence = evidence; this.json = json;
    }

    public Map<String, Object> compare(String caseId, Integer hypotheticalTerm) {
        var passport = evidence.passport(caseId);
        JsonNode snapshot = json.valueToTree(passport.get("snapshot"));
        JsonNode context = snapshot.path("lineage").path("context");
        if (!"CAPTURED".equals(snapshot.path("lineage").path("coverage").asText()))
            throw new IllegalStateException("Comparison requires a frozen case context");
        try {
            var policy = new LoanPolicyEngine(json.treeToValue(context.path("policy"), LoanPolicyProperties.class));
            var facts = context.path("facts");
            var application = new LoanApplication(facts.path("applicantId").asText(), "Synthetic frozen applicant", "0000",
                    facts.path("annualIncome").decimalValue(), facts.path("employmentYears").asInt(),
                    facts.path("requestedAmount").decimalValue(), "Frozen policy-only comparison",
                    facts.path("termMonths").asInt(), facts.path("existingMonthlyDebt").decimalValue(),
                    "Synthetic source", json.convertValue(facts.path("documents"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
            JsonNode decision = json.readTree(snapshot.path("decisionJson").asText());
            List<AgentReport> reports = json.convertValue(decision.path("agentReports"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<AgentReport>>() {});
            if (reports == null || reports.isEmpty()) throw new IllegalStateException("Frozen decision has no specialist reports");
            List<AgentReport> mandatory = reports.stream().filter(report -> !report.agentName().equals("loan-investigator")).toList();
            Set<String> names = new HashSet<>();
            mandatory.forEach(report -> names.add(report.agentName()));
            if (!names.containsAll(Set.of("intake", "kyc", "credit-risk", "compliance")))
                throw new IllegalStateException("Mandatory findings missing; comparison refused");
            List<PolicyEnvelope> envelopes = List.of(policy.evaluate(application, mandatory), policy.evaluate(application, reports));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "FROZEN_POLICY_COMPARISON_NOT_MODEL_REEXECUTION");
            result.put("passportSha256", passport.get("sha256"));
            result.put("baseline", Map.of("method", "Frozen mandatory specialists; optional investigator removed",
                    "envelope", envelopes.getFirst(), "newModelCalls", 0, "newToolCalls", 0));
            result.put("investigated", Map.of("method", "Same frozen specialists plus recorded optional finding",
                    "envelope", envelopes.getLast(), "recordedRecommendation", decision.path("decision").asText(),
                    "newModelCalls", 0, "newToolCalls", 0));
            result.put("findings", snapshot.path("findingLinks"));
            result.put("recordedUsage", recordedUsage(snapshot));
            result.put("sharedMandatoryUsage", mandatoryUsage(snapshot));
            if (hypotheticalTerm != null) {
                if (hypotheticalTerm < 6 || hypotheticalTerm > 360) throw new IllegalArgumentException("Hypothetical term must be 6-360 months");
                var changed = new LoanApplication(application.applicantId(), application.fullName(), application.ssnLast4(),
                        application.annualIncome(), application.employmentYears(), application.requestedAmount(),
                        application.loanPurpose(), hypotheticalTerm, application.existingMonthlyDebt(), application.employer(), application.documents());
                result.put("whatIf", Map.of("mode", "DETERMINISTIC_POLICY_ONLY", "termMonths", hypotheticalTerm,
                        "envelope", policy.evaluate(changed, reports),
                        "limitation", "Frozen specialist findings are not re-evaluated for changed facts. This is a policy sensitivity check, not a new underwriting decision."));
            }
            result.put("mutationCalls", 0);
            result.put("limits", List.of("All evidence is synthetic; scripted recommendations do not establish live LLM value.",
                    "Policy floors are constraints, not approval or executed effects.",
                    "A deterministic rule selector can match these scripted query choices with zero model calls.",
                    "Failed, incomplete or tampered evidence is refused, not replaced."));
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot compare invalid frozen inputs", e);
        }
    }

    private Map<String, Object> mandatoryUsage(JsonNode snapshot) {
        long calls = 0, tokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        try {
            for (var source : snapshot.path("lineage").path("sources")) {
                if (!"MODEL".equals(source.path("kind").asText()) || "loan-investigator".equals(source.path("agent_id").asText())) continue;
                JsonNode payload = json.readTree(source.path("payload").asText());
                if (!payload.path("usageReported").asBoolean()) throw new IllegalStateException("Mandatory model usage is incomplete");
                calls++;
                tokens += payload.path("promptTokens").asLong() + payload.path("completionTokens").asLong();
                cost = cost.add(payload.path("estimatedCostUsd").decimalValue());
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Unreadable mandatory usage", e); }
        return Map.of("physicalModelExchanges", calls, "tokens", tokens, "estimatedCostUsd", cost,
                "scope", "Shared frozen pipeline, including decision/critic; not additional calls by this laboratory. Scripted counts are synthetic.");
    }

    private Map<String, Object> recordedUsage(JsonNode snapshot) {
        long proposals = 0, physicalCalls = 0, tokens = 0, tools = 0;
        BigDecimal cost = BigDecimal.ZERO;
        try {
            for (JsonNode attempt : snapshot.path("investigation").path("attempts")) {
                if ("TOOL".equals(attempt.path("kind").asText())) tools++;
                else {
                    proposals++;
                    JsonNode payload = json.readTree(attempt.path("payload").asText());
                    physicalCalls += payload.path("providerCalls").asLong();
                    tokens += payload.path("promptTokens").asLong() + payload.path("completionTokens").asLong();
                    cost = cost.add(payload.path("estimatedCostUsd").decimalValue());
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Unreadable frozen usage", e); }
        return Map.of("optionalProposals", proposals, "optionalPhysicalExchanges", physicalCalls,
                "optionalTokens", tokens, "optionalEstimatedCostUsd", cost, "optionalToolDispatches", tools,
                "scope", "Optional investigation only. Scripted token counts are synthetic; estimated cost is not provider billing.");
    }
}
