package io.github.chdurai.agenticbanking.documents;

import java.util.List;
import java.util.Map;

/** Structured result of LLM document analysis: classification, extraction and summary. */
public record DocumentAnalysis(
        DocType documentType,
        String summary,
        Map<String, String> keyFields,
        List<String> actionItems,
        boolean containsPii,
        int confidencePct
) {
    public enum DocType {
        LOAN_AGREEMENT, INVOICE, IDENTITY_DOCUMENT, BANK_STATEMENT, PROJECT_REPORT, CORRESPONDENCE, OTHER
    }
}
