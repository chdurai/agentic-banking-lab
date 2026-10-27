package io.github.chdurai.agenticbanking.agents;

import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Shared JSON structured-output helpers for agents that don't extend LoanAgent.
 */
public final class AgentJsonSupport {

    private AgentJsonSupport() {}

    /** Strip markdown fences / prose around a JSON object. */
    public static String extractJson(String content) {
        if (content == null) {
            throw new IllegalStateException("Empty model response");
        }
        String c = content.trim();
        if (c.startsWith("```")) {
            c = c.replaceFirst("^```(json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = c.indexOf('{');
        int end = c.lastIndexOf('}');
        if (start >= 0 && end > start) {
            c = c.substring(start, end + 1);
        }
        return c;
    }

    /** Text of the first generation, or null when the response is empty. */
    public static String textOf(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    public static <T> T convert(String content, Class<T> type) {
        return new BeanOutputConverter<>(type).convert(extractJson(content));
    }

    public static <T> String formatInstructions(Class<T> type) {
        return new BeanOutputConverter<>(type).getFormat();
    }
}
