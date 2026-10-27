package io.github.chdurai.agenticbanking.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Enterprise knowledge graph (services, data stores, runbooks, owners) used for
 * GraphRAG-style grounding: an incident on one service is enriched with what it depends
 * on, what depends on it (blast radius), who owns it and which runbooks apply.
 *
 * Loaded from a JSON file; the same traversal API could sit on Cosmos DB (Gremlin) or
 * Neo4j in production.
 */
@Component
public class KnowledgeGraph {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraph.class);
    private static final int MAX_DEPTH = 6;

    public record Node(String id, String type, String name, String owner, int tier) {}

    public record Edge(String from, String to, String relation) {}

    record GraphFile(List<Node> nodes, List<Edge> edges) {}

    /** A node reached by traversal and its hop distance from the origin. */
    public record Reached(Node node, int distance) {}

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<Edge>> outgoing = new HashMap<>();
    private final Map<String, List<Edge>> incoming = new HashMap<>();

    public KnowledgeGraph() {
        this("graph/knowledge-graph.json");
    }

    KnowledgeGraph(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            GraphFile file = new ObjectMapper().readValue(in, GraphFile.class);
            file.nodes().forEach(n -> nodes.put(n.id(), n));
            for (Edge e : file.edges()) {
                if (!nodes.containsKey(e.from()) || !nodes.containsKey(e.to())) {
                    throw new IllegalStateException("Edge references unknown node: " + e);
                }
                outgoing.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
                incoming.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load knowledge graph " + resource, e);
        }
        log.info("Knowledge graph loaded: {} nodes", nodes.size());
    }

    public Collection<Node> allNodes() {
        return nodes.values();
    }

    /** Case-insensitive lookup by id, or by a unique name match. */
    public Optional<Node> find(String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        String key = idOrName.trim();
        for (Node n : nodes.values()) {
            if (n.id().equalsIgnoreCase(key) || n.name().equalsIgnoreCase(key)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /** Best-effort match of a free-text service reference (e.g. "Loan Service", "loan-service"). */
    public Optional<Node> resolve(String text) {
        Optional<Node> exact = find(text);
        if (exact.isPresent() || text == null) {
            return exact;
        }
        String norm = text.toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return nodes.values().stream().filter(n -> n.type().equals("SERVICE") || n.type().equals("APPLICATION")
                        || n.type().equals("DATABASE") || n.type().equals("INFRASTRUCTURE") || n.type().equals("EXTERNAL"))
                .filter(n -> norm.contains(n.id()) || n.id().contains(norm))
                .findFirst();
    }

    /** Everything this node needs in order to work (transitive downstream dependencies). */
    public List<Reached> dependencies(String id) {
        return traverse(id, outgoing, "DEPENDS_ON", true);
    }

    /** Blast radius: everything that breaks if this node fails (transitive upstream dependents). */
    public List<Reached> blastRadius(String id) {
        return traverse(id, incoming, "DEPENDS_ON", false);
    }

    /** Runbooks documenting this node or anything it depends on. */
    public List<Node> runbooksFor(String id) {
        Set<String> scope = new LinkedHashSet<>();
        scope.add(id);
        dependencies(id).forEach(r -> scope.add(r.node().id()));
        Set<Node> runbooks = new LinkedHashSet<>();
        for (String s : scope) {
            for (Edge e : outgoing.getOrDefault(s, List.of())) {
                if (e.relation().equals("DOCUMENTED_BY")) {
                    runbooks.add(nodes.get(e.to()));
                }
            }
        }
        return List.copyOf(runbooks);
    }

    private List<Reached> traverse(String start, Map<String, List<Edge>> adjacency, String relation, boolean forward) {
        if (!nodes.containsKey(start)) {
            return List.of();
        }
        List<Reached> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(List.of(start));
        Deque<Reached> queue = new ArrayDeque<>();
        queue.add(new Reached(nodes.get(start), 0));
        while (!queue.isEmpty()) {
            Reached current = queue.poll();
            if (current.distance() >= MAX_DEPTH) {
                continue;
            }
            for (Edge e : adjacency.getOrDefault(current.node().id(), List.of())) {
                if (!e.relation().equals(relation)) {
                    continue;
                }
                Node next = nodes.get(forward ? e.to() : e.from());
                if (seen.add(next.id())) {
                    Reached r = new Reached(next, current.distance() + 1);
                    out.add(r);
                    queue.add(r);
                }
            }
        }
        return out;
    }

    /** Compact grounding text for an LLM prompt. */
    public String describe(String idOrName) {
        Optional<Node> found = resolve(idOrName);
        if (found.isEmpty()) {
            return "No knowledge-graph entry for '" + idOrName + "'.";
        }
        Node n = found.get();
        StringBuilder sb = new StringBuilder();
        sb.append("%s (%s, tier %d, owner team: %s)\n".formatted(n.name(), n.type(), n.tier(), n.owner()));
        sb.append("Depends on: ").append(names(dependencies(n.id()))).append('\n');
        sb.append("Blast radius (impacted if it fails): ").append(names(blastRadius(n.id()))).append('\n');
        List<Node> runbooks = runbooksFor(n.id());
        sb.append("Related runbooks: ").append(runbooks.isEmpty() ? "none"
                : String.join(", ", runbooks.stream().map(r -> r.id() + " (" + r.name() + ")").toList()));
        return sb.toString();
    }

    /** Structured view for the REST API. */
    public Map<String, Object> view(String idOrName) {
        Optional<Node> found = resolve(idOrName);
        if (found.isEmpty()) {
            return Map.of();
        }
        Node n = found.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", n);
        out.put("dependencies", dependencies(n.id()));
        out.put("blastRadius", blastRadius(n.id()));
        out.put("runbooks", runbooksFor(n.id()));
        return out;
    }

    private static String names(List<Reached> reached) {
        return reached.isEmpty() ? "none"
                : String.join(", ", reached.stream().map(r -> r.node().name()).toList());
    }
}
