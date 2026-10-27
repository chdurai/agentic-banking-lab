package io.github.chdurai.agenticbanking.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs golden cases through the live pipeline and scores decision accuracy.
 * "Acceptable" credit for adjacent conservative outcomes (e.g. expected
 * MANUAL_REVIEW, got DECLINED) since both protect the bank.
 */
@Component
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    /** Conservative-direction equivalences: stricter-than-expected still counts. */
    private static final Map<LoanDecision.Decision, Set<LoanDecision.Decision>> ACCEPTABLE = Map.of(
            LoanDecision.Decision.MANUAL_REVIEW, Set.of(LoanDecision.Decision.DECLINED),
            LoanDecision.Decision.APPROVED, Set.of(LoanDecision.Decision.MANUAL_REVIEW),
            LoanDecision.Decision.DECLINED, Set.of(LoanDecision.Decision.COMPLIANCE_HOLD),
            LoanDecision.Decision.COMPLIANCE_HOLD, Set.of());

    private final LoanOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public EvaluationRunner(LoanOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public List<GoldenCase> loadCases() {
        try {
            return objectMapper.readValue(
                    new ClassPathResource("eval/golden-cases.json").getInputStream(),
                    new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load golden cases", e);
        }
    }

    public EvaluationResult run() {
        List<GoldenCase> cases = loadCases();
        List<EvaluationResult.CaseOutcome> outcomes = cases.stream()
                .map(this::runCase)
                .toList();
        return aggregate(outcomes);
    }

    private EvaluationResult.CaseOutcome runCase(GoldenCase gc) {
        log.info("Eval case {}: {}", gc.id(), gc.description());
        LoanDecision decision = orchestrator.evaluate(gc.application());
        boolean exact = decision.decision() == gc.expectedDecision();
        boolean acceptable = exact || ACCEPTABLE.getOrDefault(gc.expectedDecision(), Set.of())
                .contains(decision.decision());
        return new EvaluationResult.CaseOutcome(gc.id(), gc.description(),
                gc.expectedDecision(), decision.decision(), exact, acceptable,
                decision.revisionCount());
    }

    /** Pure scoring - unit-testable without any model calls. */
    public static EvaluationResult aggregate(List<EvaluationResult.CaseOutcome> outcomes) {
        int exact = (int) outcomes.stream().filter(EvaluationResult.CaseOutcome::exactMatch).count();
        int acceptable = (int) outcomes.stream().filter(EvaluationResult.CaseOutcome::acceptable).count();
        double accuracy = outcomes.isEmpty() ? 0.0 : (double) acceptable / outcomes.size();
        return new EvaluationResult(outcomes.size(), exact, acceptable, accuracy, outcomes);
    }
}
