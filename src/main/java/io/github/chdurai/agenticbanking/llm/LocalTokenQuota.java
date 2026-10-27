package io.github.chdurai.agenticbanking.llm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/** Sliding one-minute token window. Disabled when {@code platform.llm.resilience.quota.tpm} is 0. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.persistence", havingValue = "memory", matchIfMissing = true)
public class LocalTokenQuota implements QuotaLimiter {

    private static final long WINDOW_NANOS = 60_000_000_000L;

    private record Entry(long atNanos, long tokens) {}

    private final long tpm;
    private final LongSupplier clock;
    private final Deque<Entry> window = new ArrayDeque<>();
    private long inWindow;

    @Autowired
    public LocalTokenQuota(LlmProperties props) {
        this(props.resilience().quota().tpm(), System::nanoTime);
    }

    LocalTokenQuota(long tpm, LongSupplier clock) {
        if (tpm < 0) throw new IllegalArgumentException("Token quota must not be negative");
        this.tpm = tpm;
        this.clock = clock;
    }

    @Override
    public synchronized boolean tryAcquire(long estimatedTokens) {
        if (estimatedTokens < 1) throw new IllegalArgumentException("Token reservation must be positive");
        if (tpm <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        while (!window.isEmpty() && now - window.peekFirst().atNanos() >= WINDOW_NANOS) {
            inWindow -= window.pollFirst().tokens();
        }
        if (estimatedTokens > tpm - inWindow) {
            return false;
        }
        window.addLast(new Entry(now, estimatedTokens));
        inWindow += estimatedTokens;
        return true;
    }
}
