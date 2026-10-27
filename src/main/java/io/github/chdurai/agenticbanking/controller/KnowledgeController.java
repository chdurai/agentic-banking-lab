package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.knowledge.KnowledgeGraph;
import io.github.chdurai.agenticbanking.rag.PolicyRetriever;
import io.github.chdurai.agenticbanking.rag.RunbookRetriever;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Intelligent knowledge discovery: semantic/keyword search over the policy and
 * runbook corpora. Works offline in keyword mode - no LLM call required.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final PolicyRetriever policies;
    private final RunbookRetriever runbooks;
    private final KnowledgeGraph graph;

    public KnowledgeController(PolicyRetriever policies, RunbookRetriever runbooks, KnowledgeGraph graph) {
        this.graph = graph;
        this.policies = policies;
        this.runbooks = runbooks;
    }

    /** Dependencies, blast radius, owner and runbooks for a service/database/etc. */
    @GetMapping("/graph/{entity}")
    public ResponseEntity<Map<String, Object>> graph(@PathVariable String entity) {
        Map<String, Object> view = graph.view(entity);
        return view.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    @GetMapping("/search")
    public Map<String, List<String>> search(@RequestParam String query,
                                            @RequestParam(defaultValue = "3") int topK) {
        return Map.of(
                "policies", policies.retrieve(query, topK),
                "runbooks", runbooks.retrieve(query, topK));
    }
}
