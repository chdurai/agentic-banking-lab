package io.github.chdurai.agenticbanking.tools.port;

import java.time.Instant;
import java.util.List;

public interface LogQueryPort extends ToolPort {

    record LogQuery(String service, String environment, int limit) {
    }

    record LogLine(Instant at, String source, String message) {
    }

    record LogSnapshot(String service, String environment, int returned, List<LogLine> lines) {
    }

    LogSnapshot query(LogQuery request);

    @Override
    default String id() {
        return "ops.logs.query";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.NONE;
    }
}
