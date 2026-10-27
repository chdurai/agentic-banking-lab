package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.guardrails.MultilingualSensitiveDetector;

/**
 * Deterministic circuit breaker for the most sensitive grievances: matching cases bypass every model
 * call and go straight to a restricted human team. The multilingual lexicon lives in
 * {@link MultilingualSensitiveDetector}.
 */
final class SensitiveCaseDetector {

    private SensitiveCaseDetector() {}

    static boolean isSensitive(String... texts) {
        return MultilingualSensitiveDetector.isSensitive(texts);
    }
}
