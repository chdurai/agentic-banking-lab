package io.github.chdurai.agenticbanking.llm;

import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CaseSpendingScopes {
    private final LlmProperties properties;
    private final Optional<CaseSpendLedger> ledger;

    public CaseSpendingScopes(LlmProperties properties, Optional<CaseSpendLedger> ledger) {
        this.properties = properties;
        this.ledger = ledger;
    }

    public CallerContext.Scope open(String flow, String caseId) {
        var caller = CallerContext.current();
        CaseBudget budget = caller.budget() == null ? properties.budget().newCaseBudget(flow) : caller.budget();
        ledger.ifPresent(store -> budget.bind(store, caseId));
        return caller.withBudget(budget).open();
    }
}
