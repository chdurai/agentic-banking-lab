package io.github.chdurai.agenticbanking.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FlowExecutionJournalTest {
    @Test void durableCheckpointsOwnershipAndInterruption(@TempDir Path directory) throws Exception {
        FlowExecutionJournalProbe.verify(directory);
    }
}
