package io.github.chdurai.agenticbanking.flow.state;

import java.security.SecureRandom;
import java.util.UUID;

/** Time-ordered UUIDv7, so ids sort by creation. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String uuidV7() {
        long ms = System.currentTimeMillis();
        long msb = (ms << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFF);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }
}
