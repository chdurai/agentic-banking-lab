package io.github.chdurai.agenticbanking.rag;

import java.util.List;

/**
 * Retrieves relevant policy clauses for a query. The compliance agent grounds its
 * review in retrieved text and cites section IDs in its findings.
 */
public interface PolicyRetriever {

    /**
     * @param query natural-language query describing the loan file concerns
     * @param topK  max clauses to return
     * @return policy excerpts, each prefixed with its citation (e.g. "POL-CRD-001 §3: ...")
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
