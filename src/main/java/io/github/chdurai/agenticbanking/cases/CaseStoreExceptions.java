package io.github.chdurai.agenticbanking.cases;

public final class CaseStoreExceptions {

    private CaseStoreExceptions() {
    }

    /** Same Idempotency-Key reused with a different request body. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    /** Another request holds the key and has not finished yet. */
    public static class InProgressException extends RuntimeException {
        public InProgressException(String message) {
            super(message);
        }
    }

    /** Optimistic-locking failure: the case changed since it was read. */
    public static class StaleVersionException extends RuntimeException {
        public StaleVersionException(String message) {
            super(message);
        }
    }
}
