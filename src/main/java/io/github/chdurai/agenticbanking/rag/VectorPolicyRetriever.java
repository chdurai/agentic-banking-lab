package io.github.chdurai.agenticbanking.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Semantic retriever backed by NIM embeddings (e.g. nvidia/nemotron-3.5-embed-8b).
 * Active only when agents.rag-mode=vector AND a valid NVIDIA_API_KEY is present,
 * since embeddings are computed at startup.
 */
@Component
@ConditionalOnProperty(name = "agents.rag-mode", havingValue = "vector")
public class VectorPolicyRetriever implements PolicyRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorPolicyRetriever.class);

    private final SimpleVectorStore vectorStore;

    public VectorPolicyRetriever(EmbeddingModel embeddingModel, io.github.chdurai.agenticbanking.llm.ProviderGovernance governance) {
        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        this.vectorStore.add(loadDocuments());
        log.info("Policy vector store initialized");
    }

    private static List<Document> loadDocuments() {
        List<Document> docs = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:policies/*.md");
            for (Resource r : resources) {
                String content = r.getContentAsString(StandardCharsets.UTF_8);
                for (String section : content.split("(?m)^## ")) {
                    if (!section.isBlank() && section.startsWith("Section")) {
                        docs.add(new Document(section.trim(),
                                java.util.Map.of("source", String.valueOf(r.getFilename()),
                                        "tenant", AbstractKeywordRetriever.meta(content, "tenant", "public"))));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load policy documents", e);
        }
        return docs;
    }

    @Override
    public List<String> retrieve(String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query).topK(topK)
                        .filterExpression("tenant == 'public' || tenant == 'ALL' || tenant == '"
                                + io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId() + "'").build())
                .stream()
                .map(d -> "[" + d.getMetadata().get("source") + "] " + d.getText())
                .toList();
    }
}
