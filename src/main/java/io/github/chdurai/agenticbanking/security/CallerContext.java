package io.github.chdurai.agenticbanking.security;

import java.util.Set;
import java.util.concurrent.Callable;

/** Immutable authenticated scope, explicitly propagated across executor boundaries. */
public record CallerContext(String tenantId, String actor, Set<String> roles, io.github.chdurai.agenticbanking.llm.CaseBudget budget) {
    public CallerContext(String tenantId, String actor, Set<String> roles) { this(tenantId, actor, roles, null); }
    public static final CallerContext DEV = new CallerContext("dev", "dev", Set.of("PLATFORM_ADMIN"));
    private static final ThreadLocal<CallerContext> CURRENT = new ThreadLocal<>();
    private static boolean reactorEnabled;

    public static synchronized void enableReactorPropagation() {
        if (reactorEnabled) return;
        io.micrometer.context.ContextRegistry.getInstance().registerThreadLocalAccessor(
                "banking.caller", CURRENT::get, CURRENT::set, CURRENT::remove);
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
        reactorEnabled = true;
    }

    public CallerContext {
        if (tenantId == null || !tenantId.matches("[A-Za-z0-9_-]{1,64}") || actor == null || actor.isBlank()
                || actor.length() > 128 || actor.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid caller scope");
        }
        roles = Set.copyOf(roles);
    }

    public static CallerContext current() {
        CallerContext context = CURRENT.get();
        return context == null ? DEV : context;
    }

    public static boolean isBound() { return CURRENT.get() != null; }

    public static String key(String value) { return current().tenantId() + ":" + value; }

    public CallerContext withBudget(io.github.chdurai.agenticbanking.llm.CaseBudget value) {
        return new CallerContext(tenantId, actor, roles, value);
    }

    public Scope open() {
        CallerContext previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Runnable wrap(Runnable action) {
        CallerContext captured = current();
        return () -> { try (var ignored = captured.open()) { action.run(); } };
    }

    public static <T> Callable<T> wrapCall(Callable<T> action) {
        CallerContext captured = current();
        return () -> { try (var ignored = captured.open()) { return action.call(); } };
    }

    public interface Scope extends AutoCloseable { @Override void close(); }
}
