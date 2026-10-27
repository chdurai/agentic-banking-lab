package io.github.chdurai.agenticbanking.integration;

import org.junit.jupiter.api.Test;

class LoanRecoveryFlowTest {
    @Test void defaultLoanReviewHasNoSimulatedAction() {
        LoanRecoveryFlowProbe.verify(false);
    }

    @Test void enabledLoanReviewBindsSpecialistEvidenceAndReconcilesTheReceipt() {
        LoanRecoveryFlowProbe.verify(true);
    }
}
