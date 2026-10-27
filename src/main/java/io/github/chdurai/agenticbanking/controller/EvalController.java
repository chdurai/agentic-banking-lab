package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.eval.EvaluationResult;
import io.github.chdurai.agenticbanking.eval.EvaluationRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev tooling: runs the golden-case suite against the live agent pipeline.
 * Requires a valid NVIDIA_API_KEY - every case makes real model calls.
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvaluationRunner runner;

    public EvalController(EvaluationRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/run")
    public EvaluationResult run() {
        return runner.run();
    }
}
