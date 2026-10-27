package io.github.chdurai.agenticbanking.grievance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.grievance.insights")
public record GrievanceInsightsProperties(@DefaultValue("false") boolean enabled,
                                         @DefaultValue("5") int minimumGroupSize) {
    public GrievanceInsightsProperties {
        if (minimumGroupSize < 5 || minimumGroupSize > 500) {
            throw new IllegalArgumentException("Insights minimum-group-size must be between 5 and 500");
        }
    }
}
