package io.github.chdurai.agenticbanking.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned prompts. The inline prompt a caller supplies is version {@code v1}; a route may pin
 * another version loaded from {@code classpath:prompts/<agentId>/<version>.md}. Every call
 * records version and content hash so decisions are reproducible and prompt changes are
 * attributable. A pinned version that does not exist fails fast at startup, not at first call.
 */
@Component
public class PromptRegistry {

    public static final String INLINE_VERSION = "v1";

    public record Prompt(String text, String version, String hash) {}

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public Prompt resolve(String agentId, String inlineSystem, String pinnedVersion) {
        if (pinnedVersion == null || pinnedVersion.isBlank() || INLINE_VERSION.equals(pinnedVersion)) {
            return new Prompt(inlineSystem, INLINE_VERSION, hash(inlineSystem));
        }
        String text = load(agentId, pinnedVersion);
        if (text == null) {
            throw new IllegalStateException("Prompt " + agentId + "@" + pinnedVersion
                    + " not found at classpath:prompts/" + agentId + "/" + pinnedVersion + ".md");
        }
        return new Prompt(text, pinnedVersion, hash(text));
    }

    /** True when the pinned version can be resolved; used by startup validation. */
    public boolean exists(String agentId, String version) {
        return version == null || version.isBlank() || INLINE_VERSION.equals(version)
                || load(agentId, version) != null;
    }

    private String load(String agentId, String version) {
        String path = "prompts/" + agentId + "/" + version + ".md";
        return cache.computeIfAbsent(path, p -> {
            ClassPathResource res = new ClassPathResource(p);
            if (!res.exists()) {
                return "";
            }
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                return "";
            }
        }).transform(s -> s.isEmpty() ? null : s);
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
