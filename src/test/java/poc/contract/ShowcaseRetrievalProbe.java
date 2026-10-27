package poc.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.rag.Citation;
import io.github.chdurai.agenticbanking.rag.KeywordPolicyRetriever;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Frozen lexical baseline. Absence of a hybrid experiment is reported, never counted as a win. */
public final class ShowcaseRetrievalProbe {
    public static void main(String[] args) throws Exception {
        String labels;
        try (var input = Objects.requireNonNull(ShowcaseRetrievalProbe.class.getResourceAsStream("/eval/showcase-retrieval-v1.tsv"))) {
            labels = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var retriever = new KeywordPolicyRetriever();
        List<Map<String, Object>> results = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        double recall = 0;
        double reciprocalRank = 0;
        for (String line : labels.lines().toList()) {
            String[] fields = line.split("\t", 4);
            Citation expected = new Citation(fields[1], Integer.valueOf(fields[2]), "v1");
            retriever.retrieve(fields[3], 5);
            long start = System.nanoTime();
            var retrieved = retriever.retrieve(fields[3], 5);
            latencies.add(System.nanoTime() - start);
            int rank = 0;
            for (int i = 0; i < retrieved.size(); i++) {
                if (Citation.parse(retrieved.get(i)).filter(expected::satisfiedBy).isPresent()) { rank = i + 1; break; }
            }
            if (rank > 0) { recall++; reciprocalRank += 1.0 / rank; }
            results.add(Map.of("id", fields[0], "query", fields[3], "expected", expected.format(),
                    "rank", rank, "citations", retrieved.stream().map(s -> Citation.parse(s).orElseThrow().format()).toList()));
        }
        if (results.size() < 30) throw new AssertionError("Frozen benchmark requires 30 queries");
        latencies.sort(Long::compare);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", "showcase-retrieval-v1: hand-labelled synthetic lexical queries, not an independent live retrieval evaluation");
        report.put("datasetSha256", io.github.chdurai.agenticbanking.cases.CaseService.sha256(labels));
        report.put("sourceRevision", "UNCOMMITTED_WORKTREE");
        Map<String, String> corpus = new TreeMap<>();
        for (String name : List.of("credit-policy.md", "aml-kyc-policy.md", "fair-lending-policy.md")) {
            try (var input = Objects.requireNonNull(ShowcaseRetrievalProbe.class.getResourceAsStream("/policies/" + name))) {
                corpus.put(name, io.github.chdurai.agenticbanking.cases.CaseService.sha256(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        report.put("corpusSha256", corpus);
        report.put("createdAt", java.time.Instant.now().toString());
        report.put("environment", System.getProperty("os.name") + " / Java " + System.getProperty("java.version"));
        report.put("keyword", Map.of("recallAt5", recall / results.size(), "mrrAt5", reciprocalRank / results.size(),
                "warmP95Ms", latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1) / 1_000_000.0,
                "embeddingCalls", 0, "estimatedEmbeddingCostUsd", 0, "queries", results));
        report.put("hybrid", Map.of("status", "NOT_RUN", "adopted", false,
                "reason", "No PostgreSQL/pgvector experiment or approved embedding calls performed. This is not a benchmarked rejection."));
        report.put("adoptionGate", Map.of("recallAt5Improvement", 0.05, "mrrRegressionAllowed", false,
                "p95Ms", 500, "accessDateCitationChecksRequired", true));
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/showcase-retrieval-report.json").toFile(), report);
        System.out.println("PASS: 30-query keyword benchmark; hybrid NOT_RUN, remains unadopted");
    }
}
