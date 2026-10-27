package io.github.chdurai.agenticbanking.incident;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory incident resolution history (audit + pattern detection).
 * Production: persist to Cosmos DB / Postgres for trend analytics.
 */
@Component
public class IncidentMemoryStore {

    private static final int CAPACITY = 500;

    private final ConcurrentLinkedDeque<IncidentResolution> resolutions = new ConcurrentLinkedDeque<>();

    public void record(IncidentResolution resolution) {
        resolutions.addLast(resolution);
        while (resolutions.size() > CAPACITY) {
            resolutions.pollFirst();
        }
    }

    public List<IncidentResolution> recent(int limit) {
        List<IncidentResolution> out = new java.util.ArrayList<>();
        var it = resolutions.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    public int total() {
        return resolutions.size();
    }
}
