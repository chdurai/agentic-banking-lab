package io.github.chdurai.agenticbanking.llm;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Decides what a failure means. Two separate questions:
 * <ul>
 *   <li>{@link #retryable}: is another attempt on the same model worthwhile? Only 429, 5xx
 *       and transport errors qualify - never timeouts, empty bodies or bad JSON.</li>
 *   <li>{@link #countsAsProviderFailure}: should the circuit breaker count it? Only provider
 *       health signals do, so one agent's bad output can never open the breaker for the rest.</li>
 * </ul>
 */
final class LlmErrorClassifier {

    private LlmErrorClassifier() {}

    static boolean retryable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof LlmException) {
                return false;
            }
            if (c instanceof TransientAiException || c instanceof ResourceAccessException
                    || c instanceof IOException) {
                return true;
            }
            if (c instanceof HttpStatusCodeException h) {
                int code = h.getStatusCode().value();
                return code == 429 || code >= 500;
            }
            if (c instanceof NonTransientAiException n && mentions429(n.getMessage())) {
                return true;
            }
        }
        return false;
    }

    static boolean countsAsProviderFailure(Throwable t) {
        if (t instanceof LlmException e) {
            return e.kind() == LlmException.Kind.TIMEOUT || e.kind() == LlmException.Kind.PROVIDER;
        }
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof TimeoutException) {
                return true;
            }
        }
        return retryable(t);
    }

    private static boolean mentions429(String message) {
        return message != null && (message.contains("429") || message.toLowerCase().contains("rate limit"));
    }
}
