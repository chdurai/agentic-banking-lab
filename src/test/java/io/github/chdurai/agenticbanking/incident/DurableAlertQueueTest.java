package io.github.chdurai.agenticbanking.incident;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class DurableAlertQueueTest {
    @Test void acceptedObligationsSurviveRestartAndOverload(@TempDir Path directory) throws Exception {
        DurableAlertQueueProbe.verify(directory);
    }
}
