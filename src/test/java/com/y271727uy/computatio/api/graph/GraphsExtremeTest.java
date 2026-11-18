package com.y271727uy.computatio.api.graph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GraphsExtremeTest {
    private static final int MILLION = 1_000_000;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void millionNodeChainNeedsNoRecursion() {
        List<Integer> nodes = IntStream.range(0, MILLION).boxed().collect(Collectors.toList());
        var components = Graphs.stronglyConnectedComponents(List.of(0),
            node -> node + 1 < MILLION ? List.of(node + 1) : List.of());
        assertEquals(MILLION, components.size());
        assertEquals(List.of(MILLION - 1), components.get(0), "sinks come first");
        var sorted = Graphs.topologicalSort(nodes, node -> node + 1 < MILLION ? List.of(node + 1) : List.of());
        assertTrue(sorted.acyclic());
        assertEquals(nodes, sorted.order());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void millionNodeRingIsOneComponentAndNotAcyclic() {
        List<Integer> nodes = IntStream.range(0, MILLION).boxed().collect(Collectors.toList());
        var components = Graphs.stronglyConnectedComponents(nodes, node -> List.of((node + 1) % MILLION));
        assertEquals(1, components.size());
        assertEquals(MILLION, components.get(0).size());
        assertFalse(Graphs.isAcyclic(nodes, node -> List.of((node + 1) % MILLION)));
    }

    @Test
    void randomGraphsAgreeWithReachabilityDefinition() {
        Random random = new Random(42);
        for (int sample = 0; sample < 300; sample++) {
            int n = 1 + random.nextInt(25);
            List<List<Integer>> edges = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                List<Integer> out = new ArrayList<>();
                for (int j = 0; j < n; j++) if (random.nextInt(8) == 0) out.add(j);
                edges.add(out);
            }
            List<Integer> nodes = IntStream.range(0, n).boxed().collect(Collectors.toList());
            var components = Graphs.stronglyConnectedComponents(nodes, edges::get);
            boolean[][] reach = closure(edges);
            Set<Integer> covered = new HashSet<>();
            int[] componentOf = new int[n];
            for (int c = 0; c < components.size(); c++) {
                for (int member : components.get(c)) {
                    assertTrue(covered.add(member), "each node in exactly one component");
                    componentOf[member] = c;
                }
            }
            assertEquals(n, covered.size());
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    boolean same = componentOf[a] == componentOf[b];
                    assertEquals(a == b || reach[a][b] && reach[b][a], same, "sample " + sample);
                    // Sinks first: a reachable different component is listed earlier.
                    if (!same && reach[a][b]) assertTrue(componentOf[b] < componentOf[a]);
                }
            }
            boolean hasCycle = false;
            for (int a = 0; a < n; a++) hasCycle |= reach[a][a];
            var sorted = Graphs.topologicalSort(nodes, edges::get);
            assertEquals(!hasCycle, sorted.acyclic(), "sample " + sample);
            List<Integer> order = sorted.order();
            for (int i = 0; i < order.size(); i++) {
                for (int j = i + 1; j < order.size(); j++) assertFalse(reach[order.get(j)][order.get(i)]);
            }
        }
    }

    @Test
    void adjacencyHandlesExtremeIds() {
        record Edge(int from, int to) {}
        var adjacency = Graphs.adjacency(List.of(new Edge(Integer.MIN_VALUE, Integer.MAX_VALUE),
            new Edge(Integer.MAX_VALUE, Integer.MIN_VALUE)), Edge::from, Edge::to);
        assertEquals(Set.of(Integer.MAX_VALUE), adjacency.downstream(Integer.MIN_VALUE));
        assertEquals(Set.of(Integer.MAX_VALUE), adjacency.upstream(Integer.MIN_VALUE));
    }

    private static boolean[][] closure(List<List<Integer>> edges) {
        int n = edges.size();
        boolean[][] reach = new boolean[n][n];
        for (int a = 0; a < n; a++) for (int b : edges.get(a)) reach[a][b] = true;
        for (int k = 0; k < n; k++) for (int a = 0; a < n; a++) if (reach[a][k])
            for (int b = 0; b < n; b++) if (reach[k][b]) reach[a][b] = true;
        return reach;
    }
}
