package io.github.chdurai.agenticbanking.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic incident-autonomy policy.
 *
 * <p>The remediation LLM proposes a plan, but this policy decides whether it can run
 * automatically or must wait for a human.</p>
 */
@ConfigurationProperties("platform.policy.incident")
public record IncidentPolicyProperties(
        @DefaultValue("50") int minConfidencePct,
        @DefaultValue("2") int maxAutoBlastRadius,
        @DefaultValue("3") int minTierForAutoInProduction,
        @DefaultValue("false") boolean changeFreezeEnabled,
        @DefaultValue({"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"})
        List<String> freezeDaysUtc,
        @DefaultValue("00:00") String freezeStartUtc,
        @DefaultValue("00:00") String freezeEndUtc,
        @DefaultValue("25") int autonomyRegressionWindow,
        @DefaultValue("8") int autonomyRegressionMinSamples,
        @DefaultValue("0.55") double autonomyRegressionMinAutoRate
) {

    public boolean inChangeFreeze(Instant now) {
        if (!changeFreezeEnabled) {
            return false;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String d : freezeDaysUtc == null ? List.<String>of() : freezeDaysUtc) {
            try {
                days.add(DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT)));
            } catch (RuntimeException ignored) {
                // Invalid config value is treated as "day not included" (fail-safe happens in the gate).
            }
        }
        DayOfWeek day = now.atZone(ZoneOffset.UTC).getDayOfWeek();
        if (!days.contains(day)) {
            return false;
        }
        LocalTime t = now.atZone(ZoneOffset.UTC).toLocalTime();
        LocalTime start = LocalTime.parse(freezeStartUtc);
        LocalTime end = LocalTime.parse(freezeEndUtc);
        if (!start.isAfter(end)) {
            return !t.isBefore(start) && !t.isAfter(end);
        }
        // Overnight window, e.g. 22:00 -> 04:00.
        return !t.isBefore(start) || !t.isAfter(end);
    }
}
