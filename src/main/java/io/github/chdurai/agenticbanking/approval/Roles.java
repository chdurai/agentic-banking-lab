package io.github.chdurai.agenticbanking.approval;

import java.util.Map;

public final class Roles {
    public static final String LOAN_UNDERWRITER = "LOAN_UNDERWRITER";
    public static final String INCIDENT_COMMANDER = "INCIDENT_COMMANDER";
    public static final String GRM_OFFICER = "GRM_OFFICER";
    public static final String SENSITIVE_CASE_TEAM = "SENSITIVE_CASE_TEAM";
    public static final String COMPLIANCE_OFFICER = "COMPLIANCE_OFFICER";
    public static final String DOCUMENT_REVIEWER = "DOCUMENT_REVIEWER";
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private static final Map<String, String> BY_FLOW = Map.of(
            "loan", LOAN_UNDERWRITER,
            "compliance", COMPLIANCE_OFFICER,
            "incident", INCIDENT_COMMANDER,
            "grievance", GRM_OFFICER,
            "document", DOCUMENT_REVIEWER);

    private Roles() {}

    public static String forFlow(String flow) {
        return BY_FLOW.getOrDefault(flow, PLATFORM_ADMIN);
    }
}
