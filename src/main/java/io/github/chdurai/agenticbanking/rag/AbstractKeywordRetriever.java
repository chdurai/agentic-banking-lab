package io.github.chdurai.agenticbanking.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG v2 keyword retrieval (WI-08, design 7.6) over a markdown corpus on the classpath.
 * Documents are split at "## " headers into cited clauses and ranked by IDF-weighted
 * token overlap with a configurable minimum score. Document metadata (version,
 * effective-from/to) is parsed from a header comment and expired/future clauses are
 * filtered out. Citations use the uniform format {@code POL-CRD-001 §3 v2}.
 * Zero external dependencies - always available offline and in tests.
 */
public abstract class AbstractKeywordRetriever {

    private static final Logger log = LoggerFactory.getLogger(AbstractKeywordRetriever.class);

    protected record Clause(Citation citation, String text, Set<String> tokens,
                            LocalDate effectiveFrom, LocalDate effectiveTo, String tenantId) {}

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "must", "are", "not", "any", "all", "this",
            "that", "from", "per", "than", "into", "when", "has", "have", "been");

    private static final Pattern META = Pattern.compile(
            "<!--\\s*meta:(.*?)-->", Pattern.DOTALL);

    private final List<Clause> clauses;
    private final Map<String, Map<String, Double>> tenantIdf = new java.util.concurrent.ConcurrentHashMap<>();

    protected AbstractKeywordRetriever(String resourcePattern, String corpusName) {
        this.clauses = loadClauses(resourcePattern, LocalDate.now());
        log.info("Loaded {} {} clauses for keyword retrieval", clauses.size(), corpusName);
    }

    /** Retrieves with no minimum score beyond "some informative overlap" (legacy behaviour). */
    public List<String> retrieve(String query, int topK) {
        return retrieve(query, topK, 0.0);
    }

    /**
     * @param minScore minimum IDF-weighted score; filters weak single-common-token matches
     * @return clause excerpts, each prefixed with its uniform citation ({@code DOC §N vX: ...})
     */
    public List<String> retrieve(String query, int topK, double minScore) {
        Set<String> queryTokens = tokenize(query);
        Map<String, Double> idf = tenantIdf.computeIfAbsent(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(),
                tenant -> computeIdf(clauses.stream().filter(AbstractKeywordRetriever::visible).toList()));
        return clauses.stream()
                .filter(AbstractKeywordRetriever::visible)
                .map(c -> new ScoredClause(c, score(c.tokens(), queryTokens, idf)))
                .filter(sc -> sc.score() > 0 && sc.score() >= minScore)
                .sorted(Comparator.comparingDouble(ScoredClause::score).reversed())
                .limit(topK)
                .map(sc -> sc.clause().citation().format() + ": " + sc.clause().text())
                .toList();
    }

    private record ScoredClause(Clause clause, double score) {}

    private static List<Clause> loadClauses(String resourcePattern, LocalDate asOf) {
        List<Clause> result = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(resourcePattern);
            for (Resource r : resources) {
                result.addAll(parseDocument(r.getContentAsString(StandardCharsets.UTF_8),
                        r.getFilename(), asOf));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load corpus: " + resourcePattern, e);
        }
        return result;
    }

    /**
     * Parses one corpus document into clauses, honouring the optional header metadata
     * ({@code <!-- meta: version=v2; effectiveFrom=2026-01-01; effectiveTo=2027-12-31 -->}).
     * A document outside its effective window at {@code asOf} contributes no clauses.
     */
    static List<Clause> parseDocument(String content, String filename, LocalDate asOf) {
        String docId = extractDocId(content, filename);
        String version = meta(content, "version", "v1");
        LocalDate effectiveFrom = metaDate(content, "effectiveFrom", LocalDate.parse("2000-01-01"));
        LocalDate effectiveTo = metaDate(content, "effectiveTo", LocalDate.parse("2999-12-31"));
        if (asOf.isBefore(effectiveFrom) || asOf.isAfter(effectiveTo)) {
            return List.of();
        }
        List<Clause> result = new ArrayList<>();
        for (String section : content.split("(?m)^## ")) {
            if (section.isBlank() || !section.startsWith("Section")) {
                continue;
            }
            String header = section.lines().findFirst().orElse("").trim();
            String sectionNo = header.replaceAll("[^0-9]", "");
            Citation citation = new Citation(docId, Integer.valueOf(sectionNo), version);
            String text = header + "\n" + section.substring(section.indexOf('\n') + 1).trim();
            String tenant = meta(content, "tenant", "public");
            if (!tenant.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid corpus tenant");
            result.add(new Clause(citation, text, tokenize(text), effectiveFrom, effectiveTo, tenant));
        }
        return result;
    }

    static String meta(String content, String key, String fallback) {
        Matcher m = META.matcher(content);
        if (!m.find()) {
            return fallback;
        }
        for (String pair : m.group(1).split(";")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) {
                return kv[1].trim();
            }
        }
        return fallback;
    }

    private static LocalDate metaDate(String content, String key, LocalDate fallback) {
        try {
            return LocalDate.parse(meta(content, key, fallback.toString()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Extracts IDs like (POL-CRD-001) or (RB-DB-001) from the doc title. */
    private static String extractDocId(String content, String filename) {
        return content.lines().findFirst()
                .map(h -> h.replaceAll(".*\\(([A-Z]+-[A-Z]+-\\d+)\\).*", "$1"))
                .filter(id -> id.contains("-"))
                .orElse(filename == null ? "DOC" : filename.replace(".md", "").toUpperCase());
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String t : text.toLowerCase().split("[^a-z0-9%$.]+")) {
            if (t.length() > 2 && !STOPWORDS.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** BM25-style IDF: rare tokens weigh more, so a match on boilerplate alone scores low. */
    private static Map<String, Double> computeIdf(List<Clause> clauses) {
        Map<String, Integer> df = new java.util.HashMap<>();
        for (Clause c : clauses) {
            for (String t : c.tokens()) {
                df.merge(t, 1, Integer::sum);
            }
        }
        int n = Math.max(1, clauses.size());
        Map<String, Double> idf = new java.util.HashMap<>();
        df.forEach((token, count) -> idf.put(token, Math.log(1 + (n - count + 0.5) / (count + 0.5))));
        return idf;
    }

    private static double score(Set<String> clauseTokens, Set<String> queryTokens, Map<String, Double> idf) {
        double score = 0;
        for (String t : queryTokens) {
            if (clauseTokens.contains(t)) {
                score += idf.getOrDefault(t, 0.0);
            }
        }
        return score;
    }

    /** Exposed for tests. */
    public int clauseCount() {
        return (int) clauses.stream().filter(AbstractKeywordRetriever::visible).count();
    }

    /** Exposed for tests. */
    public List<String> allCitations() {
        return clauses.stream().filter(AbstractKeywordRetriever::visible).map(c -> c.citation().format()).sorted().toList();
    }

    private static boolean visible(Clause clause) {
        return clause.tenantId().equals("public") || clause.tenantId().equals("ALL")
                || clause.tenantId().equals(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId());
    }
}
