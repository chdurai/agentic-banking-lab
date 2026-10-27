package io.github.chdurai.agenticbanking.investigation;

import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append identities before dispatch; no takeover or implicit replay, even for read-only tools. */
public final class InvestigationStore {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public record Run(String caseId, String runId, String owner) {}
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public InvestigationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        SchemaMigrator.migrate(jdbc);
    }

    public void captureContext(io.github.chdurai.agenticbanking.model.LoanApplication application, Object policy) {
        String caseId = io.github.chdurai.agenticbanking.cases.CaseExecution.current();
        if (caseId == null) throw new IllegalStateException("Evidence capture requires a durable loan case");
        var facts = Map.of("applicantId", application.applicantId(), "annualIncome", application.annualIncome(),
                "requestedAmount", application.requestedAmount(), "termMonths", application.termMonths(),
                "existingMonthlyDebt", application.existingMonthlyDebt(), "documents",
                application.documents() == null ? List.of() : application.documents(), "employmentYears", application.employmentYears());
        Map<String, Object> artifacts = new java.util.TreeMap<>();
        for (String file : List.of("policies/credit-policy.md", "policies/aml-kyc-policy.md", "policies/fair-lending-policy.md")) {
            try (var input = java.util.Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(file))) {
                String text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                artifacts.put(file, Map.of("content", text, "sha256", CaseService.sha256(text)));
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot freeze policy artifact " + file, e); }
        }
        Map<String, String> agentArtifacts = new java.util.TreeMap<>();
        for (Class<?> type : List.of(io.github.chdurai.agenticbanking.agents.IntakeAgent.class, io.github.chdurai.agenticbanking.agents.KycAgent.class,
                io.github.chdurai.agenticbanking.agents.CreditRiskAgent.class, io.github.chdurai.agenticbanking.agents.ComplianceAgent.class,
                LoanInvestigator.class, io.github.chdurai.agenticbanking.policy.LoanPolicyEngine.class)) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (var input = java.util.Objects.requireNonNull(type.getResourceAsStream(resource))) {
                agentArtifacts.put(type.getName(), java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("Cannot fingerprint executing agent artifact", e);
            }
        }
        String snapshot = encode(Map.of("facts", facts, "policy", policy, "artifacts", artifacts,
                "agentBytecodeSha256", agentArtifacts,
                "source", "SYNTHETIC_ONLY", "schemaVersion", 1));
        jdbc.update("INSERT INTO loan_evidence_context (tenant_id,case_id,snapshot,snapshot_hash) VALUES (?,?,?,?)",
                tenant(), caseId, snapshot, CaseService.sha256(snapshot));
    }

    public String sourceIntent(String kind, String agent, String source, String version, Object args) {
        String caseId = io.github.chdurai.agenticbanking.cases.CaseExecution.current();
        if (caseId == null) return null;
        return tx.execute(status -> {
            var contexts = jdbc.queryForList("SELECT sources_digest FROM loan_evidence_context WHERE tenant_id=? AND case_id=? FOR UPDATE", tenant(), caseId);
            if (contexts.isEmpty()) return null; // Other flows are not captured by this loan-only ledger.
            if (contexts.getFirst().get("sources_digest") != null)
                throw new IllegalStateException("Mandatory source manifest is sealed; new dispatch refused");
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO loan_source_observation (tenant_id,case_id,observation_id,agent_id,kind,source_name,source_version,args_hash,state,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    tenant(), caseId, id, agent, kind, source, version, CaseService.sha256(encode(args)), "RUNNING", Instant.now().toString());
            return id;
        });
    }

    public void sourceResult(String id, Object result, String state) {
        if (id == null) return;
        String payload = encode(result);
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw new IllegalStateException("Source observation exceeds 32 KiB");
        tx.executeWithoutResult(status -> {
            String caseId = jdbc.queryForObject("SELECT case_id FROM loan_source_observation WHERE tenant_id=? AND observation_id=?",
                    String.class, tenant(), id);
            lockOpenSources(caseId);
            int changed = jdbc.update("UPDATE loan_source_observation SET state=?,payload=?,payload_hash=? WHERE tenant_id=? AND observation_id=? AND state='RUNNING'",
                    state, payload, CaseService.sha256(payload), tenant(), id);
            if (changed != 1) throw new IllegalStateException("Source observation no longer writable");
        });
    }

    public Map<String, Object> lineage(String caseId) {
        var contexts = jdbc.query("SELECT snapshot,snapshot_hash FROM loan_evidence_context WHERE tenant_id=? AND case_id=?",
                (row, number) -> Map.of("snapshot", row.getString(1), "sha256", row.getString(2)), tenant(), caseId);
        if (contexts.isEmpty()) return Map.of("coverage", "GAP", "reason", "Historical case has no frozen mandatory source context");
        var context = contexts.getFirst();
        if (!CaseService.sha256(context.get("snapshot")).equals(context.get("sha256")))
            throw new IllegalStateException("Case context integrity mismatch");
        var sources = sourceRows(caseId);
        var manifest = jdbc.queryForMap("SELECT source_count,sources_digest FROM loan_evidence_context WHERE tenant_id=? AND case_id=?", tenant(), caseId);
        if (manifest.get("source_count") == null || ((Number) manifest.get("source_count")).intValue() != sources.size()
                || !CaseService.sha256(encode(sources)).equals(manifest.get("sources_digest")))
            throw new IllegalStateException("Mandatory source manifest is unsealed, altered or missing evidence");
        for (var source : sources) {
            if (!"SUCCEEDED".equals(source.get("state")) || source.get("payload") == null
                    || !CaseService.sha256(source.get("payload").toString()).equals(source.get("payload_hash")))
                throw new IllegalStateException("Incomplete or altered mandatory source observation");
        }
        try {
            return Map.of("coverage", "CAPTURED", "context", JSON.readTree(context.get("snapshot")),
                    "contextSha256", context.get("sha256"), "sources", sources);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Unreadable case context", e); }
    }

    public void sealSources(String caseId) {
        tx.executeWithoutResult(status -> {
            lockOpenSources(caseId);
            var sources = sourceRows(caseId);
            int changed = jdbc.update("UPDATE loan_evidence_context SET source_count=?,sources_digest=? WHERE tenant_id=? AND case_id=? AND sources_digest IS NULL",
                    sources.size(), CaseService.sha256(encode(sources)), tenant(), caseId);
            if (changed != 1) throw new IllegalStateException("Source context is unavailable or already sealed");
        });
    }

    private void lockOpenSources(String caseId) {
        var rows = jdbc.queryForList("SELECT sources_digest FROM loan_evidence_context WHERE tenant_id=? AND case_id=? FOR UPDATE",
                tenant(), caseId);
        if (rows.size() != 1 || rows.getFirst().get("sources_digest") != null)
            throw new IllegalStateException("Source context is unavailable or already sealed");
    }

    private List<Map<String, Object>> sourceRows(String caseId) {
        return jdbc.query("SELECT observation_id,agent_id,kind,source_name,source_version,args_hash,state,payload,payload_hash,recorded_at FROM loan_source_observation WHERE tenant_id=? AND case_id=? ORDER BY observation_id",
                this::mapRow, tenant(), caseId);
    }

    public Run begin(String caseId, String gap) {
        Run run = new Run(caseId, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        jdbc.update("INSERT INTO loan_investigation (tenant_id,case_id,run_id,owner_id,state,gap,created_at) VALUES (?,?,?,?,?,?,?)",
                tenant(), caseId, run.runId(), run.owner(), "RUNNING", gap, Instant.now().toString());
        return run;
    }

    public String intent(Run run, int ordinal, String kind, String tool) {
        requireOwned(run);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO loan_investigation_attempt (tenant_id,run_id,attempt_id,ordinal_no,kind,tool_name,args_hash,state,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                tenant(), run.runId(), id, ordinal, kind, tool, CaseService.sha256("{}"), "RUNNING", Instant.now().toString());
        return id;
    }

    public void observation(Run run, String id, String state, String payload) {
        requireOwned(run);
        int changed = jdbc.update("UPDATE loan_investigation_attempt SET state=?,payload=?,payload_hash=? WHERE tenant_id=? AND run_id=? AND attempt_id=? AND state='RUNNING'",
                state, payload, CaseService.sha256(payload), tenant(), run.runId(), id);
        if (changed != 1) throw new IllegalStateException("Attempt is no longer writable");
    }

    public void finish(Run run, String state) {
        var attempts = (List<?>) view(run.caseId()).get("attempts");
        int changed = jdbc.update("UPDATE loan_investigation SET state=?,attempt_count=?,attempts_digest=? WHERE tenant_id=? AND run_id=? AND owner_id=? AND state='RUNNING'",
                state, attempts.size(), CaseService.sha256(encode(attempts)), tenant(), run.runId(), run.owner());
        if (changed != 1) throw new IllegalStateException("Investigation owner/state changed");
    }

    public boolean cancelled(Run run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT cancel_requested FROM loan_investigation WHERE tenant_id=? AND run_id=? AND owner_id=?",
                Boolean.class, tenant(), run.runId(), run.owner()));
    }

    public void cancel(String caseId) {
        int changed = jdbc.update("UPDATE loan_investigation SET cancel_requested=TRUE WHERE tenant_id=? AND case_id=? AND state='RUNNING'", tenant(), caseId);
        if (changed != 1) throw new IllegalStateException("No running investigation to cancel");
    }

    public Map<String, Object> view(String caseId) {
        var rows = jdbc.query("SELECT case_id,run_id,state,gap,cancel_requested,created_at,attempt_count,attempts_digest FROM loan_investigation WHERE tenant_id=? AND case_id=?",
                this::mapRow, tenant(), caseId);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Investigation not found");
        Map<String, Object> row = rows.getFirst();
        String runId = row.get("run_id").toString();
        var attempts = jdbc.query("SELECT attempt_id,ordinal_no,kind,tool_name,args_hash,state,payload,payload_hash,created_at FROM loan_investigation_attempt WHERE tenant_id=? AND run_id=? ORDER BY ordinal_no",
                this::mapRow, tenant(), runId);
        return Map.of("run", row, "attempts", attempts);
    }

    public List<Map<String, Object>> verifiedObservations(String caseId) {
        Map<String, Object> view = view(caseId);
        @SuppressWarnings("unchecked")
        var run = (Map<String, Object>) view.get("run");
        if (!List.of("COMPLETE", "REVIEW_REQUIRED", "NO_ALLOWED_EVIDENCE").contains(run.get("state"))) {
            throw new IllegalStateException("Investigation not safely finalized; replay refused");
        }
        @SuppressWarnings("unchecked")
        var attempts = (List<Map<String, Object>>) view.get("attempts");
        if (run.get("attempt_count") == null || ((Number) run.get("attempt_count")).intValue() != attempts.size()
                || !CaseService.sha256(encode(attempts)).equals(run.get("attempts_digest")))
            throw new IllegalStateException("Finalized attempt manifest changed or is incomplete; replay refused");
        for (var attempt : attempts) {
            if (!"SUCCEEDED".equals(attempt.get("state")) || attempt.get("payload") == null
                    || !CaseService.sha256(attempt.get("payload").toString()).equals(attempt.get("payload_hash"))) {
                throw new IllegalStateException("Incomplete or inconsistent evidence; replay refused");
            }
        }
        return attempts;
    }

    public Map<String, Object> freeze(String caseId, String resultJson) {
        var observations = verifiedObservations(caseId);
        if (resultJson == null) throw new IllegalStateException("Case result is not yet available");
        var sources = lineage(caseId);
        if (!"CAPTURED".equals(sources.get("coverage"))) throw new IllegalStateException("Mandatory evidence context is missing; passport refused");
        var content = new java.util.LinkedHashMap<String, Object>(Map.of("schemaVersion", 2, "caseId", caseId, "tenant", tenant(),
                "source", "SYNTHETIC_ONLY", "investigation", view(caseId), "attemptsDigest", CaseService.sha256(encode(observations)),
                "decisionJson", resultJson, "decisionDigest", CaseService.sha256(resultJson),
                "lineage", sources,
                "lineageLimits", List.of("Source hashes prove content consistency, not that synthetic adapters verified a customer.",
                        "Hash consistency is not source authenticity or a digital signature. No real customer evidence.")));
        content.put("findingLinks", findingLinks(resultJson, sources, observations));
        String snapshot = encode(content);
        try {
            jdbc.update("INSERT INTO loan_evidence_passport (tenant_id,case_id,snapshot,snapshot_hash) VALUES (?,?,?,?)",
                    tenant(), caseId, snapshot, CaseService.sha256(snapshot));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // A frozen passport is immutable, including on repeated freeze requests.
            return passport(caseId);
        }
        return passport(caseId);
    }

    private Object findingLinks(String decisionJson, Map<String, Object> lineage, List<Map<String, Object>> attempts) {
        try {
            var decision = JSON.readTree(decisionJson);
            var sources = JSON.valueToTree(lineage.get("sources"));
            if (!decision.path("agentReports").isArray() || decision.path("agentReports").isEmpty())
                throw new IllegalStateException("A passport requires the actual decision's specialist findings");
            java.util.Set<String> reported = new java.util.HashSet<>();
            for (var report : decision.path("agentReports")) {
                if (!reported.add(report.path("agentName").asText()) || !report.path("findings").isArray())
                    throw new IllegalStateException("Duplicate or malformed specialist findings");
            }
            if (!reported.containsAll(java.util.Set.of("intake", "kyc", "credit-risk", "compliance", "loan-investigator")))
                throw new IllegalStateException("Mandatory specialist findings are missing");
            java.util.Set<String> tools = new java.util.HashSet<>();
            java.util.Set<String> models = new java.util.HashSet<>();
            boolean retrieval = false;
            for (var source : sources) {
                if ("TOOL".equals(source.path("kind").asText())) tools.add(source.path("source_name").asText());
                if ("MODEL".equals(source.path("kind").asText())) models.add(source.path("agent_id").asText());
                if ("RETRIEVAL".equals(source.path("kind").asText())) retrieval = true;
            }
            if (!tools.containsAll(java.util.Set.of("sanctions.screen", "sanctions.pep", "bureau.credit-report",
                    "bureau.public-records", "income.verify-employment"))
                    || !models.containsAll(java.util.Set.of("intake", "kyc", "credit-risk", "compliance")) || !retrieval)
                throw new IllegalStateException("Mandatory tool, specialist or retrieved policy evidence is missing");
            List<Map<String, Object>> links = new java.util.ArrayList<>();
            for (var finding : decision.path("agentReports")) {
                String agent = finding.path("agentName").asText();
                List<String> refs = new java.util.ArrayList<>();
                for (var source : sources) if (agent.equals(source.path("agent_id").asText())) refs.add(source.path("observation_id").asText());
                if (agent.equals("loan-investigator")) {
                    attempts.forEach(attempt -> refs.add(attempt.get("attempt_id").toString()));
                    if (attempts.isEmpty()) refs.add("contextSha256:" + lineage.get("contextSha256"));
                }
                links.add(Map.of("agent", agent, "findings", finding.path("findings"), "observationIds", refs,
                        "coverage", refs.isEmpty() ? "GAP: no frozen source observation" : "RECORDED_SYNTHETIC_SOURCE",
                        "meaning", "Agent-level evidence association, not a claim that scripted findings are independently verified"));
            }
            return links;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Unreadable decision for evidence linking", e); }
    }

    public Map<String, Object> passport(String caseId) {
        var rows = jdbc.query("SELECT snapshot,snapshot_hash FROM loan_evidence_passport WHERE tenant_id=? AND case_id=?",
                (row, index) -> Map.of("snapshot", row.getString(1), "sha256", row.getString(2)), tenant(), caseId);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Freeze an evidence passport before playback");
        var row = rows.getFirst();
        if (!CaseService.sha256(row.get("snapshot")).equals(row.get("sha256")))
            throw new IllegalStateException("Passport digest mismatch");
        try {
            var snapshot = JSON.readTree(row.get("snapshot"));
            String current = CaseService.sha256(encode(verifiedObservations(caseId)));
            if (!current.equals(snapshot.path("attemptsDigest").asText()))
                throw new IllegalStateException("Recorded observations changed or are missing");
            if (!tenant().equals(snapshot.path("tenant").asText()) || !caseId.equals(snapshot.path("caseId").asText()))
                throw new IllegalStateException("Passport scope mismatch");
            if (!JSON.valueToTree(lineage(caseId)).equals(snapshot.path("lineage")))
                throw new IllegalStateException("Mandatory source lineage changed or is missing");
            if (!CaseService.sha256(snapshot.path("decisionJson").asText()).equals(snapshot.path("decisionDigest").asText()))
                throw new IllegalStateException("Frozen decision digest mismatch");
            return Map.of("snapshot", snapshot, "sha256", row.get("sha256"), "integrity", "CONTENT_CONSISTENCY_ONLY");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unreadable passport", e);
        }
    }

    private String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot serialize passport", e); }
    }

    private void requireOwned(Run run) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM loan_investigation WHERE tenant_id=? AND run_id=? AND owner_id=? AND state='RUNNING'",
                Integer.class, tenant(), run.runId(), run.owner());
        if (count == null || count != 1) throw new IllegalStateException("Investigation not owned");
    }

    private String tenant() { return CallerContext.current().tenantId(); }

    private Map<String, Object> mapRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        var metadata = row.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String name = metadata.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT);
            result.put(name, "payload".equals(name) ? row.getString(i) : row.getObject(i));
        }
        return result;
    }
}
