package io.github.chdurai.agenticbanking.rag;

import org.springframework.stereotype.Component;

@Component
public class KeywordGrievancePlaybookRetriever extends AbstractKeywordRetriever
        implements GrievancePlaybookRetriever {

    public KeywordGrievancePlaybookRetriever() {
        super("classpath:esf/*.md", "grievance playbook");
    }
}
