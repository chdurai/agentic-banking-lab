package io.github.chdurai.agenticbanking.flow.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated execution plan (v3.1 §3.5): nodes in a stable topological order plus the edges between
 * them. Edges say "{@code to} runs after {@code from} and receives its result".
 */
public record DagPlan(String flow, List<String> nodes, List<Edge> edges, String rationale,
                      int planVersion, Source source, String fallbackReason) {

    public record Edge(String from, String to) {
        @Override
        public String toString() {
            return from + ">" + to;
        }
    }

    public enum Source { RULES, LLM }

    public DagPlan {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** Direct predecessors of {@code node}, in plan order. */
    public List<String> predecessors(String node) {
        return nodes.stream()
                .filter(n -> edges.contains(new Edge(n, node)))
                .toList();
    }

    /** Longest-path layers: layer i only depends on layers < i. Agents within a layer may run in parallel. */
    public List<List<String>> layers() {
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (String n : nodes) {
            int d = 0;
            for (String p : predecessors(n)) {
                d = Math.max(d, depth.get(p) + 1);
            }
            depth.put(n, d);
        }
        int max = depth.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<List<String>> layers = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            int layer = i;
            layers.add(nodes.stream().filter(n -> depth.get(n) == layer).toList());
        }
        return layers;
    }

    public boolean fallback() {
        return fallbackReason != null;
    }

    /**
     * Stage-barrier plan: every node waits for every node of every earlier stage (the legacy executor's
     * semantics, expressed as edges so one runtime serves both).
     */
    public static DagPlan barrier(String flow, List<List<String>> stages, String rationale, Source source) {
        List<String> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (List<String> stage : stages) {
            for (String node : stage) {
                for (String earlier : nodes) {
                    edges.add(new Edge(earlier, node));
                }
            }
            nodes.addAll(stage);
        }
        return new DagPlan(flow, nodes, edges, rationale, 1, source, null);
    }

    public String describe() {
        return "source=" + source + " v" + planVersion + " nodes=" + nodes + " edges=" + edges
                + (fallbackReason == null ? "" : " fallback=" + fallbackReason);
    }
}
