package io.github.chdurai.agenticbanking.documents;

import java.util.List;

/** Map-step output for one chunk of a long document. */
public record ChunkSummary(String summary, List<String> keyFacts) {}
