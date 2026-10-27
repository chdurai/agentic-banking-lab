package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.memory.MemoryLifecycleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Memory-tier operations (WI-25): subject erasure and retention lifecycle hooks. */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryLifecycleService lifecycle;

    public MemoryController(MemoryLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @DeleteMapping("/subjects/{subjectRef}")
    public Map<String, Object> eraseSubject(@PathVariable String subjectRef) {
        MemoryLifecycleService.ErasureResult erased = lifecycle.eraseSubject(subjectRef);
        return Map.of(
                "subjectRef", subjectRef,
                "caseRowsDeleted", erased.caseRowsDeleted(),
                "episodicRowsDeleted", erased.episodicRowsDeleted(),
                "totalDeleted", erased.total());
    }
}
