package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.approval.Roles;
import io.github.chdurai.agenticbanking.grievance.GrievanceInsightsAgent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Legacy reviewer headers are supplied by CallerAuthenticationFilter in authenticated deployments. */
@RestController
@RequestMapping("/api/grievances/insights")
@ConditionalOnProperty(prefix = "platform.grievance.insights", name = "enabled", havingValue = "true")
public class GrievanceInsightsController {
    private final GrievanceInsightsAgent insights;

    public GrievanceInsightsController(GrievanceInsightsAgent insights) {
        this.insights = insights;
    }

    @GetMapping("/weekly")
    public GrievanceInsightsAgent.WeeklyInsights weekly(
            @RequestHeader(value = "X-Reviewer-Id", required = false) String actor,
            @RequestHeader(value = "X-Reviewer-Roles", required = false) String roleHeader) {
        if (actor == null || actor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated reviewer required");
        }
        Set<String> roles = roleHeader == null ? Set.of() : Arrays.stream(roleHeader.split(","))
                .map(String::trim).collect(Collectors.toSet());
        if (!roles.contains(Roles.GRM_OFFICER) && !roles.contains(Roles.PLATFORM_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GRM officer role required");
        }
        return insights.weekly();
    }
}
