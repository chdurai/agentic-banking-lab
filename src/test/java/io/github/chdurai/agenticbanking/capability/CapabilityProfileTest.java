package io.github.chdurai.agenticbanking.capability;

import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WI-20 acceptance: unknown tool/view/corpus fails startup validation; violations are
 * audited and fail the agent safely; allow-listed access passes; deny-by-default.
 */
class CapabilityProfileTest {

    private static CapabilityProperties propsWith(String agent, CapabilityProperties.ProfileSpec spec) {
        CapabilityProperties props = new CapabilityProperties();
        props.setProfiles(new java.util.LinkedHashMap<>(Map.of(agent, spec)));
        return props;
    }

    private static CapabilityProperties.ProfileSpec spec() {
        return new CapabilityProperties.ProfileSpec();
    }

    private static CapabilityGuard guard(CapabilityRegistry registry, List<PlatformEvent> audit) {
        return new CapabilityGuard(registry, new io.github.chdurai.agenticbanking.ops.PlatformEventPublisher(
                event -> audit.add((PlatformEvent) event)));
    }

    @Test
    void unknownToolFailsStartupValidation() {
        CapabilityProperties.ProfileSpec spec = spec();
        spec.setTools(List.of("sanctions.screen", "evildoer.delete-database"));
        CapabilityRegistry registry = new CapabilityRegistry(propsWith("kyc", spec));
        IllegalStateException e = assertThrows(IllegalStateException.class, registry::validate);
        assertTrue(e.getMessage().contains("evildoer.delete-database"), e.getMessage());
    }

    @Test
    void unknownViewAndCorpusFailStartupValidation() {
        CapabilityProperties.ProfileSpec badView = spec();
        badView.setDataViews(List.of("loan-application", "everybody-everything"));
        CapabilityProperties.ProfileSpec badCorpus = spec();
        badCorpus.setRetrieval(List.of("policies", "darkweb-leaks"));

        CapabilityProperties props = new CapabilityProperties();
        props.setProfiles(new java.util.LinkedHashMap<>(Map.of("intake", badView, "compliance", badCorpus)));
        CapabilityRegistry registry = new CapabilityRegistry(props);

        IllegalStateException e = assertThrows(IllegalStateException.class, registry::validate);
        assertTrue(e.getMessage().contains("everybody-everything"), e.getMessage());
        assertTrue(e.getMessage().contains("darkweb-leaks"), e.getMessage());
    }

    @Test
    void validProfilesPassValidation() {
        CapabilityProperties.ProfileSpec kyc = spec();
        kyc.setTools(List.of("sanctions.screen", "sanctions.pep"));
        kyc.setMaxToolCalls(4);
        kyc.setDataViews(List.of("loan-application", "kyc-case"));
        CapabilityProperties.ProfileSpec compliance = spec();
        compliance.setRetrieval(List.of("policies"));

        CapabilityProperties props = new CapabilityProperties();
        props.setProfiles(new java.util.LinkedHashMap<>(Map.of("kyc", kyc, "compliance", compliance)));
        CapabilityRegistry registry = new CapabilityRegistry(props);

        assertDoesNotThrow(registry::validate);
        assertTrue(registry.profile("kyc").isPresent());
        assertTrue(registry.profile("compliance").isPresent());
    }

    @Test
    void inconsistentBudgetFailsValidation() {
        CapabilityProperties.ProfileSpec spec = spec();
        spec.setMaxToolCalls(3); // but no tools allow-listed
        CapabilityRegistry registry = new CapabilityRegistry(propsWith("kyc", spec));
        assertThrows(IllegalStateException.class, registry::validate);
    }

    @Test
    void allowedToolCallPassesAndAuditsNothing() {
        CapabilityProperties.ProfileSpec kyc = spec();
        kyc.setTools(List.of("sanctions.screen", "sanctions.pep"));
        kyc.setMaxToolCalls(4);
        CapabilityRegistry registry = new CapabilityRegistry(propsWith("kyc", kyc));
        registry.validate();
        List<PlatformEvent> audit = new ArrayList<>();
        CapabilityGuard guard = guard(registry, audit);

        try (var scope = guard.enter("kyc")) {
            assertDoesNotThrow(() -> guard.checkTool("sanctions.screen"));
            assertDoesNotThrow(() -> guard.checkTool("sanctions.pep"));
        }
        assertTrue(audit.isEmpty());
    }

    @Test
    void disallowedToolIsAuditedAndFailsSafe() {
        CapabilityProperties.ProfileSpec intake = spec(); // no tools
        CapabilityRegistry registry = new CapabilityRegistry(propsWith("intake", intake));
        registry.validate();
        List<PlatformEvent> audit = new ArrayList<>();
        CapabilityGuard guard = guard(registry, audit);

        try (var scope = guard.enter("intake")) {
            CapabilityViolation v = assertThrows(CapabilityViolation.class,
                    () -> guard.checkTool("sanctions.screen"));
            assertTrue(v.getMessage().contains("intake"));
        }
        assertEquals(1, audit.size());
        assertEquals("capability.violation", audit.get(0).type());
        assertEquals("intake", audit.get(0).subjectId());
    }

    @Test
    void toolCallBudgetIsEnforced() {
        CapabilityProperties.ProfileSpec kyc = spec();
        kyc.setTools(List.of("sanctions.screen"));
        kyc.setMaxToolCalls(1);
        CapabilityRegistry registry = new CapabilityRegistry(propsWith("kyc", kyc));
        registry.validate();
        CapabilityGuard guard = guard(registry, new ArrayList<>());

        try (var scope = guard.enter("kyc")) {
            guard.checkTool("sanctions.screen"); // 1st: allowed
            CapabilityViolation v = assertThrows(CapabilityViolation.class,
                    () -> guard.checkTool("sanctions.screen"));
            assertTrue(v.getMessage().contains("budget"), v.getMessage());
        }
    }

    @Test
    void agentWithoutProfileIsDeniedByDefault() {
        CapabilityRegistry registry = new CapabilityRegistry(new CapabilityProperties());
        registry.validate();
        List<PlatformEvent> audit = new ArrayList<>();
        CapabilityGuard guard = guard(registry, audit);

        try (var scope = guard.enter("ghost-agent")) {
            assertThrows(CapabilityViolation.class, () -> guard.checkTool("sanctions.screen"));
            assertThrows(CapabilityViolation.class, () -> guard.checkRetrieval("policies"));
            assertThrows(CapabilityViolation.class, () -> guard.checkView("loan-application"));
        }
        assertEquals(3, audit.size());
    }

    @Test
    void retrievalAllowListIsEnforced() {
        CapabilityProperties.ProfileSpec compliance = spec();
        compliance.setRetrieval(List.of("policies"));
        CapabilityRegistry registry = new CapabilityRegistry(propsWith("compliance", compliance));
        registry.validate();
        List<PlatformEvent> audit = new ArrayList<>();
        CapabilityGuard guard = guard(registry, audit);

        try (var scope = guard.enter("compliance")) {
            assertDoesNotThrow(() -> guard.checkRetrieval("policies"));
            assertThrows(CapabilityViolation.class, () -> guard.checkRetrieval("runbooks"));
        }
        assertEquals(1, audit.size());
    }

    @Test
    void platformCodeWithoutAgentScopeIsNotConstrained() {
        CapabilityRegistry registry = new CapabilityRegistry(new CapabilityProperties());
        registry.validate();
        CapabilityGuard guard = guard(registry, new ArrayList<>());
        // Controllers / MCP tools / tests call directly: no scope, no profile check.
        assertDoesNotThrow(() -> guard.checkTool("sanctions.screen"));
        assertDoesNotThrow(() -> guard.checkRetrieval("policies"));
    }

    @Test
    void nestedScopesRestoreTheOuterAgent() {
        CapabilityProperties.ProfileSpec kyc = spec();
        kyc.setTools(List.of("sanctions.screen"));
        kyc.setMaxToolCalls(2);
        CapabilityRegistry registry = new CapabilityRegistry(propsWith("kyc", kyc));
        registry.validate();
        CapabilityGuard guard = guard(registry, new ArrayList<>());

        try (var outer = guard.enter("kyc")) {
            assertEquals("kyc", AgentCapabilityContext.current().orElseThrow().agentId());
            try (var inner = guard.enter("ghost")) {
                assertEquals("ghost", AgentCapabilityContext.current().orElseThrow().agentId());
            }
            assertEquals("kyc", AgentCapabilityContext.current().orElseThrow().agentId());
            assertDoesNotThrow(() -> guard.checkTool("sanctions.screen"));
        }
        assertTrue(AgentCapabilityContext.current().isEmpty());
    }
}
