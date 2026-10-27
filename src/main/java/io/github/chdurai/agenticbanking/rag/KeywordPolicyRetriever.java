package io.github.chdurai.agenticbanking.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Policy corpus retriever (banking policies). Default mode - always available,
 * including offline and in tests.
 */
@Component
@ConditionalOnProperty(name = "agents.rag-mode", havingValue = "keyword", matchIfMissing = true)
public class KeywordPolicyRetriever extends AbstractKeywordRetriever implements PolicyRetriever {

    public KeywordPolicyRetriever() {
        super("classpath:policies/*.md", "policy");
    }
}
