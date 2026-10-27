package io.github.chdurai.agenticbanking.showcase;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/** Scripted demonstration responses, not model predictions or live quality evidence. */
public final class ShowcaseChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
        String system = prompt.getSystemMessage().getText();
        String user = prompt.getUserMessage().getText();
        String body;
        if (system.contains("bounded loan investigator")) {
            if (user.contains("\"syntheticFault\":\"CANCEL_WINDOW\"")) {
                try { Thread.sleep(4000); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Synthetic cancellation window interrupted", e);
                }
            }
            if (user.contains("\"syntheticFault\":\"HOSTILE\"")) {
                body = query("payments.send", "Synthetic hostile proposal: reject before dispatch");
            } else if (user.contains("\"syntheticFault\":\"DUPLICATE\"")) {
                body = query("loan.documents.v1", "Synthetic repeated proposal: reject duplicate");
            } else if (user.contains("\"observations\":[]") && user.contains("\"gap\":\"MISSING_INCOME\"")) {
                body = query("loan.documents.v1", "Check synthetic income document completeness");
            } else if (user.contains("\"observations\":[]") && user.contains("\"gap\":\"CONFLICTING_INCOME\"")) {
                body = query("loan.sources.v1", "Compare synthetic income source versions");
            } else {
                body = "{\"action\":\"STOP\",\"tool\":\"\",\"arguments\":{},\"reason\":\"COMPLETE\"}";
            }
        } else if (system.contains("orchestration planner")) {
            body = "{\"stages\":[[\"intake\"],[\"kyc\",\"credit-risk\"],[\"compliance\"]],\"rationale\":\"Scripted synthetic plan\"}";
        } else if (system.contains("chief credit officer")) {
            body = "{\"decision\":\"MANUAL_REVIEW\",\"approvedAmount\":0,\"interestRateApr\":7.1,"
                    + "\"conditions\":[\"Synthetic demonstration requires human review\"],"
                    + "\"adverseActionReasons\":[],\"reasoning\":\"Scripted showcase recommendation; not a live credit assessment\"}";
        } else if (system.contains("quality-assurance reviewer")) {
            body = "{\"consistent\":true,\"issues\":[],\"suggestedDecision\":\"MANUAL_REVIEW\",\"explanation\":\"Scripted review\"}";
        } else if (system.contains("agent named \"")) {
            int start = system.indexOf("agent named \"") + "agent named \"".length();
            String agent = system.substring(start, system.indexOf('"', start));
            body = "{\"agentName\":\"" + agent + "\",\"status\":\"PASS\",\"riskScore\":10,"
                    + "\"summary\":\"Scripted synthetic finding\",\"findings\":[\"Synthetic fixture only\"],\"redFlags\":[]}";
        } else if (system.contains("IT incident triage")) {
            body = "{\"category\":\"DATABASE\",\"severity\":\"SEV3\",\"sentiment\":\"CALM\",\"summary\":\"Synthetic incident\",\"rationale\":\"Scripted\"}";
        } else if (system.contains("diagnosing an incident")) {
            body = "{\"probableCauses\":[\"Synthetic pool saturation\"],\"evidence\":[\"Synthetic telemetry\"],\"matchedRunbooks\":[\"RB-DB-001\"],\"confidencePct\":70}";
        } else if (system.contains("incident remediation planner")) {
            body = "{\"steps\":[],\"estimatedMinutes\":10,\"rollbackPlan\":\"No mutation in scripted showcase\"}";
        } else if (system.contains("grievance redress triage")) {
            body = "{\"themes\":[\"LABOR_AND_WORKING_CONDITIONS\"],\"severity\":\"MODERATE\",\"sentiment\":\"CONCERNED\","
                    + "\"retaliationRisk\":false,\"vulnerableGroupsAffected\":false,\"confidencePct\":70,\"summary\":\"Synthetic grievance\",\"rationale\":\"Scripted\"}";
        } else if (system.contains("grievance response drafter")) {
            body = "{\"acknowledgement\":\"Synthetic acknowledgement\",\"proposedActions\":[\"Human review\"],\"citedPlaybooks\":[],\"followUpPlan\":\"Manual follow-up\"}";
        } else if (system.contains("document-chunk summarizer")) {
            body = "{\"summary\":\"Scripted synthetic chunk\",\"keyFacts\":[]}";
        } else if (system.contains("document intelligence")) {
            body = "{\"documentType\":\"INVOICE\",\"summary\":\"Scripted synthetic analysis\",\"keyFields\":{},\"actionItems\":[\"Human verification\"],\"containsPii\":false,\"confidencePct\":70}";
        } else {
            throw new IllegalArgumentException("No scripted showcase response for this agent");
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(body))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 50)).build());
    }

    private String query(String tool, String reason) {
        return "{\"action\":\"QUERY\",\"tool\":\"" + tool + "\",\"arguments\":{},\"reason\":\"" + reason + "\"}";
    }
}
