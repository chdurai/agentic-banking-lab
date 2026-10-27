package io.github.chdurai.agenticbanking.rag;

import org.springframework.stereotype.Component;

/**
 * Runbook corpus retriever for the incident-management module.
 */
@Component
public class KeywordRunbookRetriever extends AbstractKeywordRetriever implements RunbookRetriever {

    public KeywordRunbookRetriever() {
        super("classpath:runbooks/*.md", "runbook");
    }
}
