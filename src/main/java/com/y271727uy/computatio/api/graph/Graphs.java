package com.y271727uy.computatio.api.graph;

import com.y271727uy.computatio.algorithm.graph.GraphAdjacency;
import com.y271727uy.computatio.algorithm.graph.StronglyConnected;
import com.y271727uy.computatio.algorithm.graph.TopoSort;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Iterative directed-graph algorithms. None of them uses Java recursion, so depth is bounded by heap only.
 *
 * <ul>
 *   <li>Strongly connected components: roots are visited in iteration order, successors are requested once per node
 *   and followed in iteration order; components come sinks first (Tarjan completion order), members in stack-pop
 *   order (the node entered last first, the root last).</li>
 *   <li>Topological sort (Kahn): ready nodes leave in FIFO order unless a ready order is given; successors outside the
 *   node collection are ignored; nodes on or downstream of a cycle are reported as remaining.</li>
 * </ul>
 */
public final class Graphs {
    private Graphs() {
    }

    /** Polled by long-running graph work; a throwing implementation aborts the work with that exception. */
    @FunctionalInterface
    public interface Checkpoint<E extends Exception> {
        void check() throws E;
    }

    /** Topological order of the acyclic part; {@code remaining} holds nodes on or downstream of a cycle. */
    public record TopologicalOrder<T>(List<T> order, List<T> remaining) {
        public boolean acyclic() {
            return remaining.isEmpty();
        }
    }

    /** Immutable directed adjacency over integer node ids; neighbour sets keep first-occurrence edge order. */
    public static final class Adjacency {
        private final GraphAdjacency adjacency;

        private Adjacency(GraphAdjacency adjacency) {
            this.adjacency = adjacency;
        }

        public Set<Integer> upstream(int id) {
            return adjacency.upstream(id);
        }

        public Set<Integer> downstream(int id) {
            return adjacency.downstream(id);
        }

        /** Upstream neighbours first, then downstream; a node linked in both directions appears once. */
        public Set<Integer> neighborhood(int id) {
            return adjacency.neighborhood(id);
        }
    }

    /** Tarjan components, sinks first; members in stack-pop order. */
    public static <N> List<List<N>> stronglyConnectedComponents(Iterable<? extends N> roots,
            Function<? super N, ? extends Iterable<? extends N>> successors) {
        return StronglyConnected.components(roots, successors, () -> { });
    }

    /** Tarjan components with {@code checkpoint} polled before each root and each search step. */
    public static <N, E extends Exception> List<List<N>> stronglyConnectedComponents(Iterable<? extends N> roots,
            Function<? super N, ? extends Iterable<? extends N>> successors, Checkpoint<E> checkpoint) throws E {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return StronglyConnected.components(roots, successors, checkpoint::check);
    }

    /** Kahn sort with a FIFO ready queue. */
    public static <T> TopologicalOrder<T> topologicalSort(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors) {
        return order(TopoSort.kahnFifo(nodes, successors));
    }

    /** FIFO Kahn sort with {@code checkpoint} polled before each emitted node. */
    public static <T, E extends Exception> TopologicalOrder<T> topologicalSort(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors, Checkpoint<E> checkpoint) throws E {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return order(TopoSort.kahnFifo(nodes, successors, checkpoint::check));
    }

    /** Kahn sort whose ready queue is ordered by {@code readyOrder}. */
    public static <T> TopologicalOrder<T> topologicalSort(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors, Comparator<? super T> readyOrder) {
        return order(TopoSort.kahnOrdered(nodes, successors, readyOrder));
    }

    /** True when {@code nodes} and the edges among them form no cycle. */
    public static <T> boolean isAcyclic(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors) {
        return TopoSort.kahnFifo(nodes, successors).acyclic();
    }

    /** Immutable upstream/downstream adjacency over integer node ids. */
    public static <E> Adjacency adjacency(Iterable<? extends E> edges, ToIntFunction<? super E> from,
            ToIntFunction<? super E> to) {
        return new Adjacency(GraphAdjacency.of(edges, from, to));
    }

    private static <T> TopologicalOrder<T> order(TopoSort.Result<T> result) {
        return new TopologicalOrder<>(result.order(), result.remaining());
    }
}
