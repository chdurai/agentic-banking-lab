package io.github.chdurai.agenticbanking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CallerAuthenticationHeadersTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void everyServletHeaderAccessPathUsesTheAuthenticatedIdentity(boolean spoofed) throws Exception {
        String secret = "header-contract-token-not-a-secret";
        var properties = new CallerProperties(true, Map.of("officer",
                new CallerProperties.Credential(secret, "alpha", "authenticated-officer", List.of("GRM_OFFICER"))), Set.of());
        var filter = new CallerAuthenticationFilter(properties, new ObjectMapper(), new MockEnvironment());
        var request = new MockHttpServletRequest("GET", "/api/grievances/insights/weekly");
        request.addHeader("Authorization", "Bearer " + secret);
        if (spoofed) {
            request.addHeader("X-REVIEWER-ID", "spoofed-admin");
            request.addHeader("X-REVIEWER-ROLES", "PLATFORM_ADMIN");
            request.addHeader("X-REVIEWER-ROLES", "SENSITIVE_CASE_TEAM");
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (wrapped, ignored) -> {
            var authenticated = (HttpServletRequest) wrapped;
            assertThat(authenticated.getHeader("x-reviewer-id")).isEqualTo("authenticated-officer");
            assertThat(authenticated.getHeader("X-Reviewer-Roles")).isEqualTo("GRM_OFFICER");
            assertThat(Collections.list(authenticated.getHeaders("X-Reviewer-Id"))).containsExactly("authenticated-officer");
            assertThat(Collections.list(authenticated.getHeaders("x-reviewer-roles"))).containsExactly("GRM_OFFICER");
            var headers = Collections.list(authenticated.getHeaderNames());
            assertThat(headers).contains("X-Reviewer-Id", "X-Reviewer-Roles");
            assertThat(headers.stream().filter(name -> name.equalsIgnoreCase("X-Reviewer-Id")).count()).isEqualTo(1);
            assertThat(headers.stream().filter(name -> name.equalsIgnoreCase("X-Reviewer-Roles")).count()).isEqualTo(1);
            assertThat(CallerContext.current().actor()).isEqualTo("authenticated-officer");
        });
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(CallerContext.isBound()).isFalse();
    }
}
