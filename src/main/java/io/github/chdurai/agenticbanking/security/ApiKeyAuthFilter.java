package io.github.chdurai.agenticbanking.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Baseline API-key guard for service-to-service and demo hardening. Inactive unless
 * PLATFORM_API_KEY is set; only the exact health/probe paths stay open. Constant-time
 * comparison; the key comes from the environment (Key Vault in production).
 *
 * Production path for this platform: replace with OIDC resource-server (Entra ID) at the
 * API gateway. This filter exists so the demo can be locked
 * down without external dependencies.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String apiKey;

    public ApiKeyAuthFilter(@Value("${platform.security.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return apiKey.isBlank() || CallerAuthenticationFilter.HEALTH_PROBE_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String presented = request.getHeader("X-API-Key");
        if (presented != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or invalid X-API-Key header\"}");
    }
}
