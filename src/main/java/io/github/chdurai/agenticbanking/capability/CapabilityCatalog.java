package io.github.chdurai.agenticbanking.capability;

import java.util.Set;

/**
 * Code-owned catalog of every tool, data view and retrieval corpus that exists in the
 * platform. Profiles may only reference ids listed here; anything else fails startup.
 * Views are declarative until WI-18 wires typed minimization views; tool and corpus
 * ids map to real beans ({@code io.github.chdurai.agenticbanking.tools.*}, {@code io.github.chdurai.agenticbanking.rag.*}).
 */
public final class CapabilityCatalog {

    public static final Set<String> TOOLS = Set.of(
            "sanctions.screen", "sanctions.pep",
            "bureau.credit-report", "bureau.public-records",
            "income.verify-employment", "employment.lookup",
            "ops.metrics.query", "ops.logs.query", "ticket.create",
            "loan.documents.v1", "loan.sources.v1");

    public static final Set<String> VIEWS = Set.of(
            "loan-application", "kyc-case", "incident-case", "grievance-case", "document-set");

    public static final Set<String> CORPORA = Set.of(
            "policies", "runbooks", "grievance-playbooks");

    private CapabilityCatalog() {
    }
}
