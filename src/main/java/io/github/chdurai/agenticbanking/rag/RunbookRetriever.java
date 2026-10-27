package io.github.chdurai.agenticbanking.rag;

import java.util.List;

/**
 * Retrieves relevant operational runbook sections for incident diagnosis.
 */
public interface RunbookRetriever {

    /**
     * @param query natural-language description of symptoms
     * @param topK  max sections to return
     * @return runbook excerpts, each prefixed with its citation (e.g. "RB-DB-001 §2: ...")
     */
    List<String> retrieve(String query, int topK);

    /**
     * WI-08: retrieves with a minimum IDF-weighted score. Retrievers without scoring
     * (e.g. the vector retriever until it is calibrated) ignore the threshold.
     */
    default List<String> retrieve(String query, int topK, double minScore) {
        return retrieve(query, topK);
    }
}
