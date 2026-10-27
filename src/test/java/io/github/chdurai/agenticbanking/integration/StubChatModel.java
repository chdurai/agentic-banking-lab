package io.github.chdurai.agenticbanking.integration;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for the LLM. Routes on markers in the system prompt and returns
 * canned JSON, so the whole agent graph (planner, parallel agents, critic loop, HITL,
 * metrics) is exercised end-to-end without network access or an API key.
 */
public class StubChatModel implements ChatModel {

    public final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    public final List<String> userPrompts = new CopyOnWriteArrayList<>();
    public final Map<String, List<String>> userPromptsByAgent = new ConcurrentHashMap<>();
    public final Map<String, List<Prompt>> promptsByAgent = new ConcurrentHashMap<>();
    public final Map<String, String> agentReports = new ConcurrentHashMap<>();
    public final Set<String> failingAgents = new HashSet<>();
    public final Deque<String> decisions = new ArrayDeque<>();
    public final Deque<String> critiques = new ArrayDeque<>();
    public final Map<String, String> fixed = new ConcurrentHashMap<>();

    public StubChatModel() {
        reset();
    }

    public synchronized void reset() {
        calls.clear();
        userPrompts.clear();
        userPromptsByAgent.clear();
        promptsByAgent.clear();
        agentReports.clear();
        failingAgents.clear();
        decisions.clear();
        critiques.clear();
        fixed.clear();
        fixed.put("planner", "{\"stages\":[[\"intake\"],[\"kyc\",\"credit-risk\"],[\"compliance\"]],\"rationale\":\"standard\"}");
        fixed.put("incident-triage", triage("SEV3", "DATABASE"));
        fixed.put("incident-diagnosis", diagnosis(85));
        fixed.put("incident-remediation", remediation(false));
        fixed.put("grievance-triage", grievanceTriage("MODERATE", "LABOR_AND_WORKING_CONDITIONS", false, false, 85));
        fixed.put("grievance-response", grievanceResponse());
        fixed.put("document-analysis", documentAnalysis("INVOICE", 90));
        fixed.put("document-chunk", "{\"summary\":\"part summary\",\"keyFacts\":[\"fact\"]}");
        decisions.add(decision("APPROVED"));
        critiques.add(critique(true));
    }

    public int callsTo(String agent) {
        AtomicInteger n = calls.get(agent);
        return n == null ? 0 : n.get();
    }

    public int totalCalls() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public List<String> promptsFor(String agent) {
        return userPromptsByAgent.getOrDefault(agent, List.of());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = prompt.getSystemMessage() == null ? "" : prompt.getSystemMessage().getText();
        String user = prompt.getUserMessage() == null ? "" : prompt.getUserMessage().getText();
        String agent = route(system);
        calls.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
        userPrompts.add(user);
        userPromptsByAgent.computeIfAbsent(agent, k -> new CopyOnWriteArrayList<>()).add(user);
        promptsByAgent.computeIfAbsent(agent, k -> new CopyOnWriteArrayList<>()).add(prompt);
        synchronized (this) {
            if (failingAgents.contains(agent)) {
                throw new IllegalStateException("stub: " + agent + " unavailable");
            }
            String body = switch (agent) {
                case "decision" -> next(decisions);
                case "critic" -> next(critiques);
                default -> agentReports.containsKey(agent) ? agentReports.get(agent)
                        : fixed.containsKey(agent) ? fixed.get(agent) : report(agent, "PASS", 10);
            };
            return new ChatResponse(List.of(new Generation(new AssistantMessage(body))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(100, 50)).build());
        }
    }

    private static String next(Deque<String> queue) {
        return queue.size() > 1 ? queue.poll() : queue.peek();
    }

    static String route(String system) {
        if (system.contains("orchestration planner")) return "planner";
        if (system.contains("chief credit officer")) return "decision";
        if (system.contains("quality-assurance reviewer")) return "critic";
        if (system.contains("IT incident triage")) return "incident-triage";
        if (system.contains("diagnosing an incident")) return "incident-diagnosis";
        if (system.contains("incident remediation planner")) return "incident-remediation";
        if (system.contains("grievance redress triage")) return "grievance-triage";
        if (system.contains("grievance response drafter")) return "grievance-response";
        if (system.contains("document-chunk summarizer")) return "document-chunk";
        if (system.contains("document intelligence")) return "document-analysis";
        int i = system.indexOf("agent named \"");
        if (i >= 0) {
            int s = i + "agent named \"".length();
            return system.substring(s, system.indexOf('"', s));
        }
        return "unknown";
    }

    public static String report(String agent, String status, int risk) {
        return "{\"agentName\":\"%s\",\"status\":\"%s\",\"riskScore\":%d,\"summary\":\"%s check %s\","
                .formatted(agent, status, risk, agent, status)
                + "\"findings\":[\"f1\"],\"redFlags\":[]}";
    }

    public static String decision(String outcome) {
        boolean approved = outcome.equals("APPROVED");
        return "{\"decision\":\"%s\",\"approvedAmount\":%s,\"interestRateApr\":7.1,\"conditions\":[%s],"
                .formatted(outcome, approved ? "35000" : "0", approved ? "\"autopay\"" : "")
                + "\"adverseActionReasons\":[%s],\"reasoning\":\"stub reasoning\"}"
                .formatted(outcome.equals("DECLINED") ? "\"FICO 540 below 580 minimum\"" : "");
    }

    public static String critique(boolean consistent) {
        return "{\"consistent\":%s,\"issues\":[%s],\"suggestedDecision\":\"APPROVED\",\"explanation\":\"stub\"}"
                .formatted(consistent, consistent ? "" : "\"pricing does not match risk\"");
    }

    public static String triage(String severity, String category) {
        return "{\"category\":\"%s\",\"severity\":\"%s\",\"sentiment\":\"CALM\",\"summary\":\"s\",\"rationale\":\"r\"}"
                .formatted(category, severity);
    }

    public static String grievanceTriage(String severity, String theme, boolean retaliation,
                                         boolean vulnerable, int confidence) {
        return "{\"themes\":[\"%s\"],\"severity\":\"%s\",\"sentiment\":\"CONCERNED\",\"retaliationRisk\":%s,"
                .formatted(theme, severity, retaliation)
                + "\"vulnerableGroupsAffected\":%s,\"confidencePct\":%d,".formatted(vulnerable, confidence)
                + "\"summary\":\"s\",\"rationale\":\"r\"}";
    }

    public static String grievanceResponse() {
        return "{\"acknowledgement\":\"We received your grievance.\",\"proposedActions\":[\"Verify payroll (ESF-LAB-001 §2)\"],"
                + "\"citedPlaybooks\":[\"ESF-LAB-001 §2\"],\"followUpPlan\":\"GRM officer in 7 days\"}";
    }

    public static String documentAnalysis(String type, int confidence) {
        return "{\"documentType\":\"%s\",\"summary\":\"doc summary\",\"keyFields\":{\"total\":\"48200\"},"
                .formatted(type)
                + "\"actionItems\":[\"pay within 30 days\"],\"containsPii\":false,\"confidencePct\":" + confidence + "}";
    }

    public static String diagnosis(int confidence) {
        return "{\"probableCauses\":[\"pool exhausted\"],\"evidence\":[\"50/50 active\"],"
                + "\"matchedRunbooks\":[\"RB-DB-001\"],\"confidencePct\":" + confidence + "}";
    }

    public static String remediation(boolean risky) {
        return "{\"steps\":[{\"action\":\"kill_idle_sessions\",\"params\":{\"maxSessions\":\"25\"},"
                + "\"riskLevel\":\"LOW\",\"requiresApproval\":false}"
                + (risky ? ",{\"action\":\"rollback_deploy\",\"params\":{\"maxRevisions\":\"1\"},"
                + "\"riskLevel\":\"HIGH\",\"requiresApproval\":true}" : "")
                + "],\"estimatedMinutes\":10,\"rollbackPlan\":\"redeploy 2.4.1\"}";
    }
}
