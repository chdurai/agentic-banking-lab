package io.github.chdurai.agenticbanking.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Thread-safe, capacity-bounded, newest-first history for audit views. */
public class BoundedHistory<T> {

    private final int capacity;
    private final java.util.concurrent.ConcurrentHashMap<String, ConcurrentLinkedDeque<T>> tenants =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ConcurrentLinkedDeque<T> items() {
        return tenants.computeIfAbsent(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(),
                key -> new ConcurrentLinkedDeque<>());
    }

    public BoundedHistory(int capacity) {
        this.capacity = capacity;
    }

    public void add(T item) {
        var items = items();
        items.addLast(item);
        while (items.size() > capacity) {
            items.pollFirst();
        }
    }

    public List<T> recent(int limit) {
        List<T> out = new ArrayList<>();
        var it = items().descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    public int size() {
        return items().size();
    }
}
