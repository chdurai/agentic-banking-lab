package io.github.chdurai.agenticbanking.security;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.cases.OutboxRelay;
import io.github.chdurai.agenticbanking.grievance.GrievanceSlaMonitor;
import io.github.chdurai.agenticbanking.memory.MemoryLifecycleService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Background repository access must run inside the same scope as request processing. */
@Component
public class TenantMaintenance {
    private final CallerProperties callers;
    private final ApprovalQueue approvals;
    private final OutboxRelay outbox;
    private final GrievanceSlaMonitor slas;
    private final MemoryLifecycleService retention;

    public TenantMaintenance(CallerProperties callers, ApprovalQueue approvals, OutboxRelay outbox,
                             GrievanceSlaMonitor slas, MemoryLifecycleService retention) {
        this.callers = callers;
        this.approvals = approvals;
        this.outbox = outbox;
        this.slas = slas;
        this.retention = retention;
    }

    public static java.util.List<CallerContext> scopes(CallerProperties properties) {
        Map<String, CallerContext> scopes = new LinkedHashMap<>();
        if (!properties.required()) scopes.put("dev", CallerContext.DEV);
        properties.credentials().values().forEach(c -> scopes.put(c.tenantId(),
                new CallerContext(c.tenantId(), "system:worker", Set.of("PLATFORM_ADMIN"))));
        return java.util.List.copyOf(scopes.values());
    }

    @Scheduled(fixedDelayString = "${platform.hitl.sweep-interval-ms:60000}")
    public void sweepApprovals() { each(approvals::sweep); }

    @Scheduled(fixedDelayString = "${platform.outbox.relay-interval-ms:2000}")
    public void relayOutbox() { each(outbox::relayPending); }

    @Scheduled(fixedDelayString = "${platform.grievance.sla-sweep-interval-ms:60000}")
    public void sweepSlas() { each(slas::sweep); }

    @Scheduled(fixedDelayString = "${platform.retention.purge-interval-ms:3600000}")
    public void purgeMemory() { each(retention::purgeExpired); }

    private void each(Runnable maintenance) {
        for (CallerContext caller : scopes(callers)) {
            try (var ignored = caller.open()) {
                maintenance.run();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(TenantMaintenance.class)
                        .error("Tenant {} maintenance failed", caller.tenantId(), e);
            }
        }
    }
}
