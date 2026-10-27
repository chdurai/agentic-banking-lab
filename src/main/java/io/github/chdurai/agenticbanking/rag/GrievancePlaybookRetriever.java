package io.github.chdurai.agenticbanking.rag;

import java.util.List;

/** Retrieves grievance-handling playbook sections for response drafting. */
public interface GrievancePlaybookRetriever {

    /** @return playbook excerpts, each prefixed with its citation (e.g. "ESF-LAB-001 §2: ...") */
    List<String> retrieve(String query, int topK);

    /**
     * WI-08: retrieves with a minimum IDF-weighted score. Retrievers without scoring
     * (e.g. the vector retriever until it is calibrated) ignore the threshold.
     */
    default List<String> retrieve(String query, int topK, double minScore) {
        return retrieve(query, topK);
    }
}
