package io.github.chdurai.agenticbanking.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter("s3cret");

    private MockHttpServletResponse run(String uri, String key) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (key != null) {
            req.addHeader("X-API-Key", key);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void requestsWithoutKeyAreRejected() throws Exception {
        assertThat(run("/api/loans/sample", null).getStatus()).isEqualTo(401);
        assertThat(run("/api/loans/sample", "wrong").getStatus()).isEqualTo(401);
    }

    @Test
    void correctKeyPassesAndHealthStaysOpen() throws Exception {
        assertThat(run("/api/loans/sample", "s3cret").getStatus()).isEqualTo(200);
        assertThat(run("/actuator/health", null).getStatus()).isEqualTo(200);
        assertThat(run("/actuator/health/liveness", null).getStatus()).isEqualTo(200);
        assertThat(run("/actuator/health/readiness", null).getStatus()).isEqualTo(200);
        assertThat(run("/actuator/health/readiness/extra", null).getStatus()).isEqualTo(401);
        assertThat(run("/actuator/prometheus", null).getStatus()).isEqualTo(401);
    }

    @Test
    void filterIsInactiveWithoutConfiguredKey() throws Exception {
        ApiKeyAuthFilter open = new ApiKeyAuthFilter("");
        assertThat(open.shouldNotFilter(new MockHttpServletRequest("GET", "/api/x"))).isTrue();
    }
}
