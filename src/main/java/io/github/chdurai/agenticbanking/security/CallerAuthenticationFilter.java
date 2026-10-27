package io.github.chdurai.agenticbanking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
@Order(-110)
public class CallerAuthenticationFilter extends OncePerRequestFilter {
    static final Set<String> HEALTH_PROBE_PATHS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");
    private final CallerProperties properties;
    private final ObjectMapper json;

    public CallerAuthenticationFilter(CallerProperties properties, ObjectMapper json, Environment environment) {
        this.properties = properties;
        this.json = json;
        boolean localShowcase = io.github.chdurai.agenticbanking.showcase.ShowcaseConfiguration.isLocal(environment);
        boolean deployed = java.util.Arrays.stream(environment.getActiveProfiles()).anyMatch(p ->
                !Set.of("dev", "test").contains(p) && !(p.equals("showcase") && localShowcase));
        if (deployed && (!properties.required()
                || !environment.getProperty("platform.persistence", "memory").equals("jdbc"))) {
            throw new IllegalStateException("Non-dev profiles require authenticated callers and platform.persistence=jdbc");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HEALTH_PROBE_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String presented = request.getHeader("Authorization");
        CallerContext caller = null;
        if (presented != null && presented.startsWith("Bearer ")) {
            byte[] secret = presented.substring(7).getBytes(StandardCharsets.UTF_8);
            for (var c : properties.credentials().values()) {
                if (MessageDigest.isEqual(secret, c.secret().getBytes(StandardCharsets.UTF_8))) {
                    caller = new CallerContext(c.tenantId(), c.actor(), Set.copyOf(c.roles()));
                }
            }
        }
        if (caller == null && (properties.required() || presented != null || !properties.credentials().isEmpty())) {
            problem(response, 401, "authentication-required", "Valid tenant-scoped bearer credential required");
            return;
        }
        if (caller == null) caller = CallerContext.DEV;
        String requestedTenant = request.getHeader("X-Tenant-Id");
        if (requestedTenant != null && !requestedTenant.equals(caller.tenantId())) {
            problem(response, 403, "tenant-mismatch", "Tenant override is forbidden");
            return;
        }
        String path = request.getRequestURI();
        if ((path.startsWith("/api/ops") || path.startsWith("/api/v1/ops")
                || path.startsWith("/actuator") || path.startsWith("/api/eval") || path.startsWith("/api/memory"))
                && !caller.roles().contains("PLATFORM_ADMIN")) {
            problem(response, 403, "role-required", "Operator role required");
            return;
        }
        for (var route : java.util.Map.of("/api/loans", "LOAN_UNDERWRITER", "/api/incidents", "INCIDENT_COMMANDER",
                "/api/grievances", "GRM_OFFICER", "/api/documents", "DOCUMENT_REVIEWER",
                "/api/knowledge", "KNOWLEDGE_READER").entrySet()) {
            if (path.startsWith(route.getKey()) && !caller.roles().contains(route.getValue())
                    && !caller.roles().contains("PLATFORM_ADMIN")) {
                problem(response, 403, "role-required", "Flow role required");
                return;
            }
        }
        final CallerContext authenticated = caller;
        try (var ignored = caller.open()) {
            // Legacy adapters receive identity from authentication, never caller-controlled identity headers.
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public String getHeader(String name) {
                    if (name.equalsIgnoreCase("X-Reviewer-Id")) return authenticated.actor();
                    if (name.equalsIgnoreCase("X-Reviewer-Roles")) return String.join(",", authenticated.roles());
                    return super.getHeader(name);
                }
                @Override public java.util.Enumeration<String> getHeaders(String name) {
                    if (name.equalsIgnoreCase("X-Reviewer-Id") || name.equalsIgnoreCase("X-Reviewer-Roles")) {
                        return java.util.Collections.enumeration(java.util.List.of(getHeader(name)));
                    }
                    return super.getHeaders(name);
                }
                @Override public java.util.Enumeration<String> getHeaderNames() {
                    var names = new java.util.LinkedHashSet<>(java.util.Collections.list(super.getHeaderNames()));
                    names.removeIf(name -> name.equalsIgnoreCase("X-Reviewer-Id") || name.equalsIgnoreCase("X-Reviewer-Roles"));
                    names.add("X-Reviewer-Id");
                    names.add("X-Reviewer-Roles");
                    return java.util.Collections.enumeration(names);
                }
            }, response);
        }
    }

    private void problem(HttpServletResponse response, int status, String type, String detail) throws IOException {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), detail);
        body.setType(URI.create("urn:agentic-banking:problem:" + type));
        response.setStatus(status);
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), body);
    }
}
