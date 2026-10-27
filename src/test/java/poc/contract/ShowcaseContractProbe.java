package poc.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.AgenticBankingApplication;
import io.github.chdurai.agenticbanking.cases.CaseExecution;
import io.github.chdurai.agenticbanking.investigation.InvestigationStore;
import io.github.chdurai.agenticbanking.investigation.LoanInvestigator;
import io.github.chdurai.agenticbanking.investigation.LoanEvidenceTools;
import io.github.chdurai.agenticbanking.llm.CaseBudget;
import io.github.chdurai.agenticbanking.security.CallerContext;
import io.github.chdurai.agenticbanking.showcase.ShowcaseChatModel;
import io.github.chdurai.agenticbanking.showcase.ShowcaseController;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable without JUnit as well as through the normal Maven wrapper test. */
public final class ShowcaseContractProbe {
    @Configuration(proxyBeanMethods = false)
    public static class ControlledProvider {
        @Bean @Primary RecordingModel recordingModel() { return new RecordingModel(); }
        @Bean @Primary ControlledTools controlledTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard guard) { return new ControlledTools(guard); }
    }
    public static class ControlledTools extends LoanEvidenceTools {
        volatile boolean slow;
        volatile boolean oversized;
        ControlledTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard guard) { super(guard); }
        @Override
        public LoanInvestigator.Observation read(String id, String tool, io.github.chdurai.agenticbanking.model.LoanApplication application) {
            var observation = super.read(id, tool, application);
            if (slow) {
                try { Thread.sleep(6000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic tool cancelled", e); }
            }
            return oversized ? new LoanInvestigator.Observation(id, tool, observation.source(), observation.sourceVersion(),
                    observation.observedAt(), Map.of("synthetic", "x".repeat(17000))) : observation;
        }
    }
    public static class RecordingModel implements ChatModel {
        final ShowcaseChatModel delegate = new ShowcaseChatModel();
        final AtomicInteger exchanges = new AtomicInteger();
        volatile String forced;
        volatile boolean slow;
        volatile boolean twoTools;
        @Override public ChatResponse call(Prompt prompt) {
            exchanges.incrementAndGet();
            if (!prompt.getSystemMessage().getText().contains("bounded loan investigator")) return delegate.call(prompt);
            if (slow) {
                try { Thread.sleep(6000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Scripted cancellation", e); }
            }
            if (twoTools) {
                String tool = prompt.getUserMessage().getText().contains("loan.documents.v1") ? "loan.sources.v1" : "loan.documents.v1";
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"action\":\"QUERY\",\"tool\":\"" + tool + "\",\"arguments\":{},\"reason\":\"synthetic limits check\"}"))));
            }
            return forced == null ? delegate.call(prompt)
                    : new ChatResponse(List.of(new Generation(new AssistantMessage(forced))));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("crash-child")) {
            var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:file:" + args[1], "sa", ""));
            var store = new InvestigationStore(jdbc);
            var run = store.begin("crash-case", "MISSING_INCOME");
            store.intent(run, 1, "TOOL", "loan.documents.v1");
            System.out.println("INTENT_COMMITTED");
            System.out.flush();
            Thread.sleep(60000);
            return;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try (var context = new SpringApplicationBuilder(AgenticBankingApplication.class, ControlledProvider.class)
                .web(WebApplicationType.NONE).profiles("showcase")
                .run("--platform.datasource.url=jdbc:h2:mem:poc-contract;DB_CLOSE_DELAY=-1",
                        "--platform.cases.worker-enabled=true", "--spring.main.banner-mode=off")) {
            var investigator = context.getBean(LoanInvestigator.class);
            var store = context.getBean(InvestigationStore.class);
            var model = context.getBean(RecordingModel.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var toolAdapter = context.getBean(ControlledTools.class);
            List<String[]> corpus = Files.readAllLines(Path.of("src/test/resources/eval/showcase-investigations-v2.tsv"))
                    .stream().skip(1).filter(line -> !line.isBlank()).map(line -> line.split("\t", -1)).toList();
            check(corpus.size() == 24 && corpus.stream().map(row -> row[0]).distinct().count() == 24, "Frozen fixture identity count");
            int baselineDispatches = 0;
            int investigatedDispatches = 0;
            List<Map<String, Object>> comparisons = new ArrayList<>();
            for (String scenario : List.of("complete", "missing", "conflicting")) {
                for (int variant = 1; variant <= 6; variant++) {
                    String id = "fixture-v2-" + scenario + "-" + variant;
                    String[] label = corpus.stream().filter(row -> row[0].equals(id)).findFirst().orElseThrow();
                    var application = labelledFixture(label);
                    check(LoanInvestigator.gap(application).equals(label[6]), "Fixture gap label mismatch");
                    long start = System.nanoTime();
                    try (var scope = CaseExecution.open(id)) {
                        store.captureContext(application,
                                context.getBean(io.github.chdurai.agenticbanking.policy.LoanPolicyEngine.class).properties());
                        var finding = investigator.investigate(application);
                        check((scenario.equals("complete") ? "PASS" : "UNAVAILABLE").equals(finding.status().name()), id + " review routing");
                        store.sealSources(id);
                    }
                    long investigationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    var view = store.view(id);
                    var attempts = attempts(view);
                    long tools = attempts.stream().filter(a -> "TOOL".equals(a.get("kind"))).count();
                    check(tools == (scenario.equals("complete") ? 0 : 1), id + " next-best tool");
                    if (scenario.equals("complete")) check(attempts.isEmpty(), "Complete case made an unnecessary optional model/tool call");
                    if (!label[7].equals("NONE")) check(attempts.stream().anyMatch(a -> "TOOL".equals(a.get("kind"))
                            && label[7].equals(a.get("tool_name"))), "Required labelled evidence missing");
                    check(attempts.stream().filter(a -> "MODEL".equals(a.get("kind"))).count() <= 3, "Proposal cap");
                    int calls = model.exchanges.get();
                    store.verifiedObservations(id);
                    store.verifiedObservations(id);
                    check(model.exchanges.get() == calls, "Recorded playback made a model call");
                    boolean duplicate = false;
                    try (var scope = CaseExecution.open(id)) { investigator.investigate(ShowcaseController.fixture(scenario)); }
                    catch (org.springframework.dao.DuplicateKeyException expected) { duplicate = true; }
                    check(duplicate, "Duplicate investigation reran");
                    if (!scenario.equals("complete")) {
                        List<LoanInvestigator.Observation> baseline = new ArrayList<>();
                        long baselineStarted = System.nanoTime();
                        try (var scope = context.getBean(io.github.chdurai.agenticbanking.capability.CapabilityGuard.class).enter("loan-investigator", "baseline-" + id)) {
                            for (String tool : List.of("loan.documents.v1", "loan.sources.v1")) {
                                baseline.add(toolAdapter.read(UUID.randomUUID().toString(), tool, application));
                            }
                        }
                        baselineDispatches += baseline.size();
                        investigatedDispatches += (int) tools;
                        comparisons.add(Map.of("id", id, "fixedChecklistObservations", baseline,
                                "fixedChecklistMs", (System.nanoTime() - baselineStarted) / 1_000_000.0,
                                "fixedChecklistModelCalls", 0, "investigatorModelProposals",
                                attempts.stream().filter(a -> "MODEL".equals(a.get("kind"))).count(),
                                "ruleSelectorDispatches", 1, "ruleSelectorModelCalls", 0,
                                "requiredTool", scenario.equals("missing") ? "loan.documents.v1" : "loan.sources.v1"));
                    }
                    results.add(Map.of("id", id, "partition", label[1], "scenario", scenario, "optionalDispatches", tools,
                            "investigationElapsedMs", investigationMs, "passed", true));
                }
            }
            List<String> invalid = List.of(
                    "{\"action\":\"QUERY\",\"tool\":\"payments.send\",\"arguments\":{},\"reason\":\"bad\"}",
                    "{\"action\":\"QUERY\",\"tool\":\"loan.documents.v1\",\"arguments\":{\"caseId\":\"foreign-case\"},\"reason\":\"bad\"}",
                    "{\"action\":\"QUERY\",\"tool\":\"loan.sources.v1\",\"arguments\":{\"url\":\"https://invalid.example\"},\"reason\":\"bad\"}",
                    "{\"action\":\"EXECUTE\",\"tool\":\"loan.documents.v1\",\"arguments\":{},\"reason\":\"bad\"}",
                    "{\"action\":\"STOP\",\"tool\":\"loan.sources.v1\",\"arguments\":{},\"reason\":\"COMPLETE\"}",
                    "{\"action\":\"QUERY\",\"tool\":\"loan.documents.v1\",\"arguments\":{},\"reason\":\"bad\",\"tenant\":\"foreign\"}");
            for (int n = 0; n < invalid.size(); n++) {
                model.forced = invalid.get(n);
                String id = "fixture-v2-adversarial-" + (n + 1);
                String[] label = corpus.stream().filter(row -> row[0].equals(id)).findFirst().orElseThrow();
                long start = System.nanoTime();
                try (var scope = CaseExecution.open(id)) {
                    check(investigator.investigate(labelledFixture(label)).status().name().equals("UNAVAILABLE"), "Unsafe proposal approved");
                }
                check(attempts(store.view(id)).stream().noneMatch(a -> "TOOL".equals(a.get("kind"))), "Forbidden tool dispatched");
                results.add(Map.of("id", id, "partition", label[1], "scenario", "adversarial", "optionalDispatches", 0,
                        "investigationElapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), "passed", true));
            }
            model.forced = "{\"action\":\"QUERY\",\"tool\":\"loan.documents.v1\",\"arguments\":{},\"reason\":\"completeness\"}";
            try (var scope = CaseExecution.open("duplicate-tool")) { investigator.investigate(ShowcaseController.fixture("missing")); }
            check(attempts(store.view("duplicate-tool")).stream().filter(a -> "TOOL".equals(a.get("kind"))).count() == 1, "Repeated tool executed twice");
            model.forced = null;
            int calls = model.exchanges.get();
            try (var caller = CallerContext.DEV.withBudget(new CaseBudget(1, 0, 30000)).open();
                 var scope = CaseExecution.open("token-limit")) {
                investigator.investigate(ShowcaseController.fixture("missing"));
            }
            check(model.exchanges.get() == calls, "Budget overrun contacted provider");
            try (var caller = CallerContext.DEV.withBudget(new CaseBudget(12000, 0.00000001, 30000)).open();
                 var scope = CaseExecution.open("cost-limit")) {
                investigator.investigate(ShowcaseController.fixture("missing"));
            }
            check(model.exchanges.get() == calls, "Unpriced cost-limited call contacted provider");
            model.twoTools = true;
            try (var scope = CaseExecution.open("rounds-limit")) { investigator.investigate(ShowcaseController.fixture("missing")); }
            var bounded = attempts(store.view("rounds-limit"));
            check(bounded.stream().filter(a -> "TOOL".equals(a.get("kind"))).count() == 2, "Optional dispatch limit");
            check(bounded.stream().filter(a -> "MODEL".equals(a.get("kind"))).count() == 3, "Proposal round limit");
            model.twoTools = false;
            toolAdapter.oversized = true;
            try (var scope = CaseExecution.open("observation-size")) { investigator.investigate(ShowcaseController.fixture("missing")); }
            check(attempts(store.view("observation-size")).stream().anyMatch(a -> "FAILED".equals(a.get("state"))), "Oversized observation accepted");
            toolAdapter.oversized = false;
            toolAdapter.slow = true;
            long started = System.nanoTime();
            try (var scope = CaseExecution.open("tool-deadline")) { investigator.investigate(ShowcaseController.fixture("missing")); }
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5800, "Tool timeout was not bounded");
            check(attempts(store.view("tool-deadline")).stream().anyMatch(a -> "UNCERTAIN".equals(a.get("state"))), "Timed-out tool was not uncertain");
            toolAdapter.slow = false;

            model.slow = true;
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> pending = executor.submit(() -> {
                    try (var scope = CaseExecution.open("cancel-case")) { investigator.investigate(ShowcaseController.fixture("missing")); }
                });
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (jdbc.queryForObject("SELECT COUNT(*) FROM loan_investigation_attempt WHERE run_id IN (SELECT run_id FROM loan_investigation WHERE case_id='cancel-case')", Integer.class) == 0
                        && System.nanoTime() < until) Thread.sleep(10);
                store.cancel("cancel-case");
                pending.get(5, TimeUnit.SECONDS);
                check(attempts(store.view("cancel-case")).stream().anyMatch(a -> "UNCERTAIN".equals(a.get("state"))), "Cancellation lost uncertain identity");
                refuse(() -> store.verifiedObservations("cancel-case"), "Uncertain replay accepted");
            } finally { executor.shutdownNow(); model.slow = false; }

            var laboratories = verifyCaseJourneys(context);
            verifyFaultJourneys(context);
            verifySourceFinalization(context);
            String unfinishedPassport = "deleted-before-freeze";
            try (var scope = CaseExecution.open(unfinishedPassport)) {
                store.captureContext(ShowcaseController.fixture("missing"),
                        context.getBean(io.github.chdurai.agenticbanking.policy.LoanPolicyEngine.class).properties());
                investigator.investigate(ShowcaseController.fixture("missing"));
                store.sealSources(unfinishedPassport);
                refuse(() -> store.sourceIntent("TOOL", "credit-risk", "bureau.credit-report", "synthetic-adapters-v1", Map.of()),
                        "Sealed source manifest allowed a new dispatch");
            }
            jdbc.update("DELETE FROM loan_investigation_attempt WHERE run_id IN (SELECT run_id FROM loan_investigation WHERE case_id=?)",
                    unfinishedPassport);
            refuse(() -> store.freeze(unfinishedPassport, "{\"decision\":\"MANUAL_REVIEW\"}"), "Missing attempts before first freeze accepted");
            jdbc.update("UPDATE loan_investigation_attempt SET payload='tampered' WHERE run_id IN (SELECT run_id FROM loan_investigation WHERE case_id='fixture-v2-missing-1')");
            refuse(() -> store.verifiedObservations("fixture-v2-missing-1"), "Tampered observation accepted");
            jdbc.update("DELETE FROM loan_investigation_attempt WHERE run_id IN (SELECT run_id FROM loan_investigation WHERE case_id='fixture-v2-missing-2')");
            refuse(() -> store.verifiedObservations("fixture-v2-missing-2"), "Missing observation accepted");
            jdbc.update("UPDATE loan_evidence_context SET snapshot='{}' WHERE case_id='fixture-v2-complete-2'");
            refuse(() -> store.lineage("fixture-v2-complete-2"), "Changed frozen case/policy context accepted");
            try (var caller = new CallerContext("foreign", "tester", Set.of("PLATFORM_ADMIN")).open()) {
                boolean missing = false;
                try { store.view("fixture-v2-missing-2"); }
                catch (org.springframework.web.server.ResponseStatusException expected) { missing = expected.getStatusCode().value() == 404; }
                check(missing, "Foreign tenant evidence exposed");
            }
            String noScope = "scope-not-bound";
            refuse(() -> investigator.investigate(ShowcaseController.fixture("missing")), noScope);
            crashCheck();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("mode", "SCRIPTED_SYNTHETIC_CONTRACTS_NOT_LIVE_MODEL_EVALUATION");
            report.put("createdAt", java.time.Instant.now().toString());
            report.put("environment", System.getProperty("os.name") + " / Java " + System.getProperty("java.version"));
            report.put("sourceRevision", "UNCOMMITTED_WORKTREE");
            Map<String, String> fingerprints = new TreeMap<>();
            for (String source : List.of("src/main/java/io/github/chdurai/agenticbanking/investigation/InvestigationStore.java",
                    "src/main/java/io/github/chdurai/agenticbanking/investigation/DecisionLaboratory.java",
                    "src/main/java/io/github/chdurai/agenticbanking/investigation/ReviewOutcomeReader.java",
                    "src/main/java/io/github/chdurai/agenticbanking/controller/InvestigationController.java",
                    "src/main/java/io/github/chdurai/agenticbanking/llm/LlmGateway.java",
                    "src/main/java/io/github/chdurai/agenticbanking/orchestrator/LoanOrchestrator.java",
                    "src/main/resources/static/investigation.js")) {
                fingerprints.put(source, io.github.chdurai.agenticbanking.cases.CaseService.sha256(Files.readString(Path.of(source))));
            }
            report.put("sourceFilesSha256", fingerprints);
            report.put("investigatorSourceSha256", io.github.chdurai.agenticbanking.cases.CaseService.sha256(Files.readString(Path.of("src/main/java/io/github/chdurai/agenticbanking/investigation/LoanInvestigator.java"))));
            report.put("fixtureSourceSha256", io.github.chdurai.agenticbanking.cases.CaseService.sha256(Files.readString(Path.of("src/main/java/io/github/chdurai/agenticbanking/showcase/ShowcaseController.java"))
                    + Files.readString(Path.of("src/test/java/poc/contract/ShowcaseContractProbe.java"))));
            report.put("labelSha256", io.github.chdurai.agenticbanking.cases.CaseService.sha256(Files.readString(Path.of("src/test/resources/eval/showcase-investigations-v2.tsv"))));
            report.put("dataset", "showcase-investigations-v2: 24 frozen labelled synthetic cases with varied income, amount and term; 8 held aside, no prompt tuning in runner. Same-author scripted contracts, not independent held-out model generalization.");
            report.put("cases", results);
            List<Long> latencies = results.stream().map(result -> ((Number) result.get("investigationElapsedMs")).longValue()).sorted().toList();
            report.put("investigationLatencyMs", Map.of("p50", latencies.get((int) Math.ceil(latencies.size() * .50) - 1),
                    "p95", latencies.get((int) Math.ceil(latencies.size() * .95) - 1),
                    "scope", "24 local scripted fixtures; includes initial frozen context capture; not provider or production latency"));
            report.put("decisionLaboratory", laboratories);
            report.put("killedJvmReplayRefused", true);
            report.put("springJourneys", List.of("complete loan", "missing loan", "conflicting loan", "incident", "grievance", "document",
                    "human-authorized simulated receipt", "duplicate-safe reconciliation", "playback preserves original case version"));
            report.put("pairedMissingConflicting", Map.of("fixedChecklistDispatches", baselineDispatches,
                    "investigatorDispatches", investigatedDispatches, "coverage", "Required synthetic gap tool dispatched in all twelve cases",
                    "comparisons", comparisons,
                    "limitation", "Executed fixed checklist always reads both tools. A simple rule selector also needs only one, with zero model calls. Scripted selection is not evidence of incremental LLM value."));
            report.put("additionalChecks", List.of("duplicate run", "duplicate tool", "shared token and estimated cost budget",
                    "3-round/2-tool caps", "16 KiB observation cap", "5 second tool timeout",
                    "cancellation uncertainty", "tampering", "deleted evidence", "tenant isolation", "durable case required",
                    "mandatory source lineage and policy artifacts", "zero-call frozen policy comparison",
                    "isolated policy-only what-if", "mandatory source deletion refusal",
                    "reviewer/receipt digest linkage and failed/rejected simulation states",
                    "concurrent source intent versus manifest sealing",
                    "per-case hostile, repeated, timeout and cancellation fault fixtures"));
            Path output = Path.of("target", "showcase-contract-report.json");
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
        System.out.println("PASS: 24 scripted investigation fixtures, negative boundaries, passport integrity, cancellation and killed-JVM replay refusal");
    }

    private static io.github.chdurai.agenticbanking.model.LoanApplication labelledFixture(String[] row) {
        var base = ShowcaseController.fixture(row[2].equals("adversarial") ? "missing" : row[2]);
        return new io.github.chdurai.agenticbanking.model.LoanApplication(row[0], base.fullName(), base.ssnLast4(),
                new java.math.BigDecimal(row[3]), base.employmentYears(), new java.math.BigDecimal(row[4]),
                base.loanPurpose(), Integer.parseInt(row[5]), base.existingMonthlyDebt(), base.employer(), base.documents());
    }

    private static void verifySourceFinalization(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
        var store = context.getBean(InvestigationStore.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 12; round++) {
                String id = "seal-race-" + round;
                try (var scope = CaseExecution.open(id)) {
                    store.captureContext(ShowcaseController.fixture("complete"),
                            context.getBean(io.github.chdurai.agenticbanking.policy.LoanPolicyEngine.class).properties());
                }
                var start = new CountDownLatch(1);
                var append = pool.submit(CallerContext.wrapCall(() -> {
                    start.await();
                    try (var scope = CaseExecution.open(id)) {
                        try { return store.sourceIntent("TOOL", "credit-risk", "bureau.credit-report", "synthetic-adapters-v1", Map.of()); }
                        catch (IllegalStateException sealed) {
                            check(sealed.getMessage().contains("sealed"), "Unexpected source append refusal");
                            return null;
                        }
                    }
                }));
                var seal = pool.submit(CallerContext.wrapCall(() -> { start.await(); store.sealSources(id); return true; }));
                start.countDown();
                String observation = append.get(5, TimeUnit.SECONDS);
                check(seal.get(5, TimeUnit.SECONDS), "Concurrent source seal failed");
                int count = jdbc.queryForObject("SELECT COUNT(*) FROM loan_source_observation WHERE case_id=?", Integer.class, id);
                check(jdbc.queryForObject("SELECT source_count FROM loan_evidence_context WHERE case_id=?", Integer.class, id) == count,
                        "Source intent escaped finalized manifest");
                if (observation != null) refuse(() -> store.sourceResult(observation, Map.of("late", true), "SUCCEEDED"),
                        "Late completion rewrote sealed evidence");
            }
        }
    }

    private static List<Map<String, Object>> verifyCaseJourneys(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
        List<Map<String, Object>> laboratories = new ArrayList<>();
        var showcase = context.getBean(ShowcaseController.class);
        var cases = context.getBean(io.github.chdurai.agenticbanking.cases.AsyncCaseService.class);
        var caseStore = context.getBean(io.github.chdurai.agenticbanking.cases.CaseStore.class);
        var approvals = context.getBean(io.github.chdurai.agenticbanking.approval.ApprovalStore.class);
        var controller = context.getBean(io.github.chdurai.agenticbanking.controller.InvestigationController.class);
        for (String scenario : List.of("complete", "missing", "conflicting")) {
            String key = UUID.randomUUID().toString();
            String id = showcase.launch(scenario, key).caseId();
            check(showcase.launch(scenario, key).caseId().equals(id), "Case submission lost idempotency");
            cases.drain();
            var row = awaitCase(cases, id);
            check(row.status() == io.github.chdurai.agenticbanking.cases.CaseStatus.PENDING_APPROVAL, "Synthetic loan did not reach review: " + row.status());
            var result = new ObjectMapper().readTree(row.resultJson());
            check(result.path("decision").asText().equals("MANUAL_REVIEW"), "Unsupported automatic approval");
            Set<String> found = new HashSet<>();
            result.path("agentReports").forEach(report -> found.add(report.path("agentName").asText()));
            check(found.containsAll(Set.of("intake", "kyc", "credit-risk", "compliance", "loan-investigator")), "Mandatory specialist omitted");
            var passport = controller.freeze(id);
            // A caller cannot create a success-shaped passport from a decision missing its specialist evidence.
            refuse(() -> context.getBean(InvestigationStore.class).freeze(id, "{\"decision\":\"MANUAL_REVIEW\"}"),
                    "Incomplete decision accepted as a passport");
            int modelCalls = context.getBean(RecordingModel.class).exchanges.get();
            var jdbc = context.getBean(JdbcTemplate.class);
            int sourceCount = jdbc.queryForObject("SELECT COUNT(*) FROM loan_source_observation WHERE case_id=?", Integer.class, id);
            var comparison = controller.compare(id, 120);
            var frozen = new ObjectMapper().valueToTree(passport).path("snapshot");
            Set<String> sourceKinds = new HashSet<>();
            frozen.path("lineage").path("sources").forEach(source -> sourceKinds.add(source.path("kind").asText()));
            check(sourceKinds.containsAll(Set.of("MODEL", "TOOL", "RETRIEVAL")), "Mandatory tool/model/retrieval lineage missing");
            check(frozen.path("findingLinks").size() >= 5 && frozen.path("lineage").path("context").path("artifacts").size() == 3,
                    "Finding links or frozen policy artifacts missing");
            check(new ObjectMapper().valueToTree(comparison).path("whatIf").path("envelope").path("floor").asText().equals("DECLINED"),
                    "Out-of-policy what-if did not enforce frozen term cap");
            check(context.getBean(RecordingModel.class).exchanges.get() == modelCalls, "Comparison made a model call");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM loan_source_observation WHERE case_id=?", Integer.class, id) == sourceCount,
                    "Comparison dispatched a source call");
            long version = caseStore.find(id).orElseThrow().version();
            controller.replay(id);
            check(version == caseStore.find(id).orElseThrow().version(), "Playback changed original case");
            controller.compare(id, null);
            check(version == caseStore.find(id).orElseThrow().version(), "Comparison changed original case");
            check(passport.equals(controller.passport(id)), "Frozen passport changed");
            var beforeReview = new ObjectMapper().valueToTree(controller.reviewOutcome(id));
            check(beforeReview.path("reviews").get(0).path("effectState").asText().equals("AWAITING_HUMAN_REVIEW"),
                    "Pending reviewer checkpoint claimed an effect");
            check(beforeReview.path("reviews").get(0).path("receipt").isNull(), "Receipt existed before authorization");
            try (var caller = new CallerContext("dev", "wrong-role", Set.of("DOCUMENT_REVIEWER")).open()) {
                boolean denied = false;
                try { controller.get(id); }
                catch (org.springframework.web.server.ResponseStatusException expected) { denied = expected.getStatusCode().value() == 403; }
                check(denied, "Wrong flow role read evidence");
                boolean comparisonDenied = false;
                try { controller.compare(id, null); }
                catch (org.springframework.web.server.ResponseStatusException expected) { comparisonDenied = expected.getStatusCode().value() == 403; }
                check(comparisonDenied, "Wrong flow role read comparison");
                boolean outcomeDenied = false;
                try { controller.reviewOutcome(id); }
                catch (org.springframework.web.server.ResponseStatusException expected) { outcomeDenied = expected.getStatusCode().value() == 403; }
                check(outcomeDenied, "Wrong flow role read reviewer outcome");
            }
            if (scenario.equals("complete")) {
                var approval = approvals.forCase(id).getFirst();
                var decisions = context.getBean(io.github.chdurai.agenticbanking.controller.ApprovalDecisionController.class);
                var approved = decisions.decide(approval.id(), "\"" + approval.version() + "\"",
                        new io.github.chdurai.agenticbanking.controller.ApprovalDecisionController.Decision("APPROVE", "SYNTHETIC_REVIEW", "Explicit synthetic reviewer authorization")).getBody();
                check(approved != null && approved.executionLog().stream().anyMatch(entry -> entry.contains("receipt=sim-")), "Authorized simulation produced no receipt");
                var reconciled = context.getBean(io.github.chdurai.agenticbanking.approval.ApprovalQueue.class).reconcile(approval.id(),
                        new io.github.chdurai.agenticbanking.approval.ReviewerIdentity("dev", Set.of("PLATFORM_ADMIN")));
                check(approved.executionLog().equals(reconciled.executionLog()), "Reconciliation changed simulated receipt");
                long before = caseStore.find(id).orElseThrow().version();
                controller.replay(id);
                check(before == caseStore.find(id).orElseThrow().version(), "Playback refreshed original approval lifecycle");
                int afterApprovalCalls = context.getBean(RecordingModel.class).exchanges.get();
                var outcome = new ObjectMapper().valueToTree(controller.reviewOutcome(id));
                var reviewed = outcome.path("reviews").get(0);
                check(reviewed.path("effectState").asText().equals("SIMULATED_RECEIPT_CONFIRMED")
                        && reviewed.path("receipt").path("receiptId").asText().startsWith("sim-"), "Verified receipt linkage missing");
                check(reviewed.path("approvalEvidenceSha256").equals(reviewed.path("receipt").path("approvalEvidenceSha256")),
                        "Receipt is not bound to approval evidence");
                check(passport.equals(controller.passport(id)) && before == caseStore.find(id).orElseThrow().version(),
                        "Outcome refresh changed passport or case");
                check(approvals.find(approval.id()).version() == approved.version()
                        && afterApprovalCalls == context.getBean(RecordingModel.class).exchanges.get(), "Outcome reader executed or reconciled an action");
                jdbc.update("UPDATE simulated_loan_receipt SET evidence_sha256=? WHERE approval_id=?", "0".repeat(64), approval.id());
                refuse(() -> controller.reviewOutcome(id), "Tampered receipt digest was accepted");
                jdbc.update("UPDATE simulated_loan_receipt SET evidence_sha256=? WHERE approval_id=?",
                        reviewed.path("approvalEvidenceSha256").asText(), approval.id());
                var transaction = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
                transaction.executeWithoutResult(status -> {
                    jdbc.update("DELETE FROM simulated_loan_receipt WHERE approval_id=?", approval.id());
                    refuse(() -> controller.reviewOutcome(id), "Missing previously confirmed receipt was accepted");
                    status.setRollbackOnly();
                });
                check(new ObjectMapper().valueToTree(controller.reviewOutcome(id)).path("reviews").get(0)
                        .path("effectState").asText().equals("SIMULATED_RECEIPT_CONFIRMED"), "Receipt restoration failed");
            }
            if (!scenario.equals("complete")) {
                var approval = approvals.forCase(id).getFirst();
                var decisions = context.getBean(io.github.chdurai.agenticbanking.controller.ApprovalDecisionController.class);
                var decided = decisions.decide(approval.id(), "\"" + approval.version() + "\"",
                        new io.github.chdurai.agenticbanking.controller.ApprovalDecisionController.Decision(scenario.equals("missing") ? "APPROVE" : "REJECT",
                                "SYNTHETIC_REVIEW", "Exercise missing-evidence refusal or explicit rejection")).getBody();
                check(decided != null && decided.status() == (scenario.equals("missing")
                        ? io.github.chdurai.agenticbanking.approval.ApprovalRequest.Status.EXECUTION_FAILED : io.github.chdurai.agenticbanking.approval.ApprovalRequest.Status.REJECTED),
                        "Missing/conflicting review did not retain safe effect state");
                var observed = new ObjectMapper().valueToTree(controller.reviewOutcome(id)).path("reviews").get(0);
                check(observed.path("effectState").asText().equals(scenario.equals("missing")
                        ? "EXECUTION_FAILED_NO_RECEIPT" : "NO_SIMULATED_EFFECT") && observed.path("receipt").isNull(),
                        "Failed/rejected simulation claimed a completed effect");
            }
            laboratories.add(Map.of("scenario", scenario, "comparison", comparison, "passport", passport,
                    "reviewOutcome", controller.reviewOutcome(id)));
            if (scenario.equals("conflicting")) {
                jdbc.update("DELETE FROM loan_source_observation WHERE case_id=? AND source_name='income.verify-employment'", id);
                refuse(() -> controller.passport(id), "Deleted mandatory source was accepted");
                refuse(() -> controller.compare(id, null), "Comparison accepted deleted mandatory source");
            }
        }
        ObjectMapper json = context.getBean(ObjectMapper.class);
        Map<String, Object> otherFlows = Map.of(
                "incident", context.getBean(io.github.chdurai.agenticbanking.controller.IncidentController.class).sample(),
                "grievance", context.getBean(io.github.chdurai.agenticbanking.controller.GrievanceController.class).sample(),
                "document", context.getBean(io.github.chdurai.agenticbanking.controller.DocumentController.class).sample());
        for (var flow : otherFlows.entrySet()) {
            var submitted = cases.submit(flow.getKey(), UUID.randomUUID().toString(), json.valueToTree(flow.getValue()));
            cases.drain();
            var row = awaitCase(cases, submitted.record().caseId());
            check(row.status() != io.github.chdurai.agenticbanking.cases.CaseStatus.FAILED && row.resultJson() != null,
                    "No-key " + flow.getKey() + " failed");
        }
        String incomplete = showcase.launch("complete", UUID.randomUUID().toString()).caseId();
        cases.drain();
        awaitCase(cases, incomplete);
        context.getBean(JdbcTemplate.class).update("DELETE FROM loan_source_observation WHERE case_id=? AND source_name='bureau.credit-report'", incomplete);
        refuse(() -> controller.freeze(incomplete), "Mandatory source deleted before first freeze accepted");
        return laboratories;
    }

    private static void verifyFaultJourneys(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
        var showcase = context.getBean(ShowcaseController.class);
        var cases = context.getBean(io.github.chdurai.agenticbanking.cases.AsyncCaseService.class);
        var store = context.getBean(InvestigationStore.class);
        for (String fault : List.of("fault-hostile", "fault-duplicate", "fault-timeout", "fault-cancel")) {
            String id = showcase.launch(fault, UUID.randomUUID().toString()).caseId();
            String concurrentClean = fault.equals("fault-timeout") ? showcase.launch("complete", UUID.randomUUID().toString()).caseId() : null;
            cases.drain();
            if (fault.equals("fault-cancel")) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                var jdbc = context.getBean(JdbcTemplate.class);
                while (jdbc.queryForObject("SELECT COUNT(*) FROM loan_investigation_attempt WHERE run_id IN (SELECT run_id FROM loan_investigation WHERE case_id=?)",
                        Integer.class, id) == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                store.cancel(id);
            }
            var row = awaitCase(cases, id);
            check(row.status() == io.github.chdurai.agenticbanking.cases.CaseStatus.PENDING_APPROVAL, "Fault lost safe review routing: " + fault);
            var recorded = attempts(store.view(id));
            long tools = recorded.stream().filter(attempt -> "TOOL".equals(attempt.get("kind"))).count();
            check(tools == (fault.equals("fault-hostile") || fault.equals("fault-cancel") ? 0 : 1), "Fault tool bound violated: " + fault);
            if (fault.equals("fault-hostile")) {
                check(recorded.stream().anyMatch(attempt -> "REJECTED".equals(attempt.get("state"))), "Hostile proposal not rejected");
                refuse(() -> store.verifiedObservations(id), "Hostile proposal replay accepted");
            }
            if (fault.equals("fault-timeout") || fault.equals("fault-cancel")) {
                check(recorded.stream().anyMatch(attempt -> "UNCERTAIN".equals(attempt.get("state"))), "Fault uncertainty lost");
                refuse(() -> store.verifiedObservations(id), "Uncertain fault replay accepted");
            }
            if (concurrentClean != null) {
                check(awaitCase(cases, concurrentClean).status() == io.github.chdurai.agenticbanking.cases.CaseStatus.PENDING_APPROVAL, "Concurrent clean case failed");
                check(attempts(store.view(concurrentClean)).stream().noneMatch(attempt -> "TOOL".equals(attempt.get("kind"))),
                        "Timeout fault contaminated a concurrent clean investigation");
            }
        }
        refuse(() -> new io.github.chdurai.agenticbanking.showcase.ShowcaseFaults(new org.springframework.core.env.StandardEnvironment()),
                "Fault controls allowed outside local scripted profile");
    }

    private static io.github.chdurai.agenticbanking.cases.CaseRecord awaitCase(io.github.chdurai.agenticbanking.cases.AsyncCaseService cases, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var row = cases.get(id);
            if (row.status() != io.github.chdurai.agenticbanking.cases.CaseStatus.OPEN && row.status() != io.github.chdurai.agenticbanking.cases.CaseStatus.RUNNING) return row;
            Thread.sleep(10);
        }
        throw new AssertionError("Case worker timed out");
    }

    private static void crashCheck() throws Exception {
        Path dir = Files.createTempDirectory("showcase-crash-");
        String database = dir.resolve("journal").toString();
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ShowcaseContractProbe.class.getName(), "crash-child", database)
                .redirectErrorStream(true).start();
        try (var output = child.inputReader()) {
            boolean ready = false;
            for (String line; (line = output.readLine()) != null;) {
                if (line.equals("INTENT_COMMITTED")) { ready = true; break; }
            }
            check(ready, "Child never committed intent");
            child.destroyForcibly().waitFor();
            var store = new InvestigationStore(new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:file:" + database, "sa", "")));
            boolean blocked = false;
            try { store.begin("crash-case", "MISSING_INCOME"); }
            catch (org.springframework.dao.DuplicateKeyException expected) { blocked = true; }
            check(blocked, "Killed-JVM attempt replayed");
            refuse(() -> store.verifiedObservations("crash-case"), "Crashed attempt replay accepted");
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
            try (var paths = Files.list(dir)) { for (Path path : paths.toList()) Files.delete(path); }
            Files.delete(dir);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attempts(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("attempts");
    }
    private static void refuse(Runnable action, String failure) {
        boolean refused = false;
        try { action.run(); } catch (IllegalStateException expected) { refused = true; }
        check(refused, failure);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
