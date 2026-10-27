package io.github.chdurai.agenticbanking.ops;

import io.github.chdurai.agenticbanking.config.AgentProperties;
import io.github.chdurai.agenticbanking.llm.LlmProperties;
import io.github.chdurai.agenticbanking.llm.ProviderGovernance;
import io.github.chdurai.agenticbanking.security.CallerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AzureRuntimeContractTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CallerProperties.class, LlmProperties.class, AgentProperties.class})
    @org.springframework.context.annotation.Import(ProviderGovernance.class)
    static class Binding {}

    @Test void deploymentEnvironmentNamesBindTheAuthenticatedTenantAndSafetyControls() throws Exception {
        Map<String, Object> values = Map.ofEntries(
                Map.entry("PLATFORM_CALLERS_REQUIRED", "true"),
                Map.entry("PLATFORM_CALLERS_CREDENTIALS_OPERATOR_SECRET", "synthetic-bootstrap-token-not-a-secret"),
                Map.entry("PLATFORM_CALLERS_CREDENTIALS_OPERATOR_TENANTID", "alpha"),
                Map.entry("PLATFORM_CALLERS_CREDENTIALS_OPERATOR_ACTOR", "deployment-operator"),
                Map.entry("PLATFORM_CALLERS_CREDENTIALS_OPERATOR_ROLES", "PLATFORM_ADMIN"),
                Map.entry("PLATFORM_LLM_PROVIDER", "nvidia-nim"),
                Map.entry("PLATFORM_LLM_PROVIDERS_APPROVED", "nvidia-nim"),
                Map.entry("PLATFORM_LLM_GOVERNANCE_APPROVEDENDPOINTS", "https://integrate.api.nvidia.com"),
                Map.entry("LLM_REVIEWED_REGION", "reviewed-test-region"),
                Map.entry("LLM_NO_TRAINING_REVIEWED", "true"),
                Map.entry("LLM_RETENTION_REVIEWED", "true"),
                Map.entry("LLM_TERMS_REFERENCE", "synthetic-test-approval"),
                Map.entry("PLATFORM_LLM_ALLOWFALLBACKEQUALSPRIMARY", "false"),
                Map.entry("PLATFORM_LLM_RESILIENCE_KILLSWITCH", "true"),
                Map.entry("PLATFORM_LLM_RESILIENCE_QUOTA_TPM", "60000"),
                Map.entry("AGENTS_FALLBACKMODEL", "reviewed-distinct-model"));
        String template = Files.readString(Path.of("deploy/azure/containerapp.bicep"));
        values.keySet().forEach(name -> assertThat(template).contains("name: '" + name + "'"));
        var defaults = new org.springframework.boot.env.YamlPropertySourceLoader().load("runtime-defaults",
                new org.springframework.core.io.ClassPathResource("application.yml"));
        new ApplicationContextRunner().withUserConfiguration(Binding.class)
                .withInitializer(context -> {
                    defaults.forEach(context.getEnvironment().getPropertySources()::addLast);
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("systemEnvironment", values));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var callers = context.getBean(CallerProperties.class);
                    assertThat(callers.required()).isTrue();
                    assertThat(callers.credentials()).containsOnlyKeys("operator");
                    var operator = callers.credentials().get("operator");
                    assertThat(operator.tenantId()).isEqualTo("alpha");
                    assertThat(operator.actor()).isEqualTo("deployment-operator");
                    assertThat(operator.roles()).containsExactly("PLATFORM_ADMIN");
                    var llm = context.getBean(LlmProperties.class);
                    assertThat(llm.provider()).isEqualTo("nvidia-nim");
                    assertThat(llm.providers().approved()).containsExactly("nvidia-nim");
                    assertThat(llm.allowFallbackEqualsPrimary()).isFalse();
                    assertThat(llm.resilience().killSwitch()).isTrue();
                    assertThat(llm.resilience().quota().tpm()).isEqualTo(60000);
                    assertThat(context.getBean(AgentProperties.class).getFallbackModel()).isEqualTo("reviewed-distinct-model");
                    assertThat(context).hasSingleBean(ProviderGovernance.class);
                });
    }

    @Test void templateUsesPrivateNetworkingIdentityBasedSecretsAndImmutableImages() throws Exception {
        String template = Files.readString(Path.of("deploy/azure/containerapp.bicep"));
        assertThat(template).contains("Microsoft.ManagedIdentity/userAssignedIdentities",
                "scope: registry", "scope: vault", "principalType: 'ServicePrincipal'",
                "keyVaultUrl:", "identity: identity.id", "type: 'UserAssigned'",
                "infrastructureSubnetId: infrastructureSubnetId", "internal: true", "allowInsecure: false",
                "/agentic-banking@${imageDigest}", "sslmode=verify-full", "maxReplicas: 1",
                "path: '/actuator/health/liveness'", "path: '/actuator/health/readiness'",
                "name: 'PLATFORM_PERSISTENCE', value: 'jdbc'", "name: 'SPRING_PROFILES_ACTIVE', value: 'production'",
                "name: 'PLATFORM_DB_PASSWORD', secretRef: 'db-password'",
                "name: 'PLATFORM_IDEMPOTENCY_REQUIRED', value: 'true'");
        assertThat(template).doesNotContain("nimApiKey", "imageTag", "value: 'demo-key-not-set'", "identity: 'system'");
    }
}
