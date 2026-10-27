package io.github.chdurai.agenticbanking.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention windows for memory tiers and replay surfaces.
 */
@ConfigurationProperties("platform.retention")
public record RetentionProperties(
        @DefaultValue("2555") int caseDays,
        @DefaultValue("90") int episodicDays,
        @DefaultValue("30") int sensitiveDays,
        @DefaultValue("14") int replayVaultDays,
        java.util.Map<String, Windows> tenants
) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RetentionProperties {
        tenants = tenants == null ? java.util.Map.of() : java.util.Map.copyOf(tenants);
        if (caseDays < 1 || episodicDays < 1 || sensitiveDays < 1 || replayVaultDays < 1) {
            throw new IllegalArgumentException("Retention windows must be positive");
        }
    }
    public RetentionProperties(int caseDays, int episodicDays, int sensitiveDays, int replayVaultDays) {
        this(caseDays, episodicDays, sensitiveDays, replayVaultDays, java.util.Map.of());
    }
    public record Windows(int caseDays, int episodicDays, int sensitiveDays, int replayVaultDays) {
        public Windows {
            if (caseDays < 1 || episodicDays < 1 || sensitiveDays < 1 || replayVaultDays < 1) {
                throw new IllegalArgumentException("Tenant retention windows must be positive");
            }
        }
    }
    private Windows scoped() { return tenants.get(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId()); }
    @Override public int caseDays() { return scoped() == null ? caseDays : scoped().caseDays(); }
    @Override public int episodicDays() { return scoped() == null ? episodicDays : scoped().episodicDays(); }
    @Override public int sensitiveDays() { return scoped() == null ? sensitiveDays : scoped().sensitiveDays(); }
    @Override public int replayVaultDays() { return scoped() == null ? replayVaultDays : scoped().replayVaultDays(); }

    public static RetentionProperties defaults() {
        return new RetentionProperties(2555, 90, 30, 14);
    }
}
