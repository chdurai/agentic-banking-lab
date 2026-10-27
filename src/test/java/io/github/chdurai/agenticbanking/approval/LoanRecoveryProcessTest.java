package io.github.chdurai.agenticbanking.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Real killed/restarted JVMs with file-backed H2; not PostgreSQL or a deployed banking adapter. */
class LoanRecoveryProcessTest {
    @TempDir Path directory;

    @Test void killedWorkerAndBothApprovalEffectCrashWindowsRecoverWithoutDuplication() throws Exception {
        LoanRecoveryProcessProbe.verify(directory);
    }
}
