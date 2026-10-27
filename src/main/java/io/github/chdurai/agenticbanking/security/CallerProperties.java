package io.github.chdurai.agenticbanking.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("platform.callers")
public record CallerProperties(@DefaultValue("false") boolean required, Map<String, Credential> credentials,
                               Set<String> mcpClientAllowlist) {
    public CallerProperties {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        mcpClientAllowlist = mcpClientAllowlist == null ? Set.of() : Set.copyOf(mcpClientAllowlist);
        credentials.values().forEach(c -> {
            new CallerContext(c.tenantId(), c.actor(), Set.copyOf(c.roles()));
            if (c.secret() == null || c.secret().length() < 24) {
                throw new IllegalArgumentException("Caller secrets must contain at least 24 characters");
            }
        });
        if (credentials.values().stream().map(Credential::secret).distinct().count() != credentials.size()) {
            throw new IllegalArgumentException("Caller secrets must be unique");
        }
        if (required && credentials.isEmpty()) throw new IllegalArgumentException("Authenticated callers must be configured");
    }
    public record Credential(String secret, String tenantId, String actor, List<String> roles) {}
}
