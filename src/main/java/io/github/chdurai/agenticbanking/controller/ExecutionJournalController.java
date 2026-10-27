package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.cases.AsyncCaseService;
import io.github.chdurai.agenticbanking.flow.FlowExecutionJournal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Metadata only: raw agent output snapshots are not exported through this endpoint. */
@RestController
@RequestMapping("/api/v1/cases")
@ConditionalOnProperty(name = "platform.persistence", havingValue = "jdbc")
public class ExecutionJournalController {
    public record Execution(FlowExecutionJournal.RunView run, List<FlowExecutionJournal.AttemptView> attempts) {}
    private final AsyncCaseService cases;
    private final FlowExecutionJournal journal;

    public ExecutionJournalController(AsyncCaseService cases, FlowExecutionJournal journal) {
        this.cases = cases;
        this.journal = journal;
    }

    @GetMapping("/{id}/executions")
    public List<Execution> executions(@PathVariable String id) {
        cases.get(id);
        return journal.runs(id, 100).stream().map(run -> new Execution(run, journal.attempts(run.runId()))).toList();
    }
}
