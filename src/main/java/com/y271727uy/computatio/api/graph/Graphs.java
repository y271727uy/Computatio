package com.y271727uy.computatio.api.graph;

import com.y271727uy.computatio.algorithm.graph.GraphAdjacency;
import com.y271727uy.computatio.algorithm.graph.StronglyConnected;
import com.y271727uy.computatio.algorithm.graph.TopoSort;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Iterative directed-graph algorithms. None of them uses Java recursion, so depth is bounded by heap only.
 * Ordering contracts are documented on {@link StronglyConnected} and {@link TopoSort}.
 */
public final class Graphs {
    private Graphs() {
    }

    /** Tarjan components, sinks first; members in stack-pop order. */
    public static <N> List<List<N>> stronglyConnectedComponents(Iterable<? extends N> roots,
            Function<? super N, ? extends Iterable<? extends N>> successors) {
        return StronglyConnected.components(roots, successors, () -> { });
    }

    /** Tarjan components with a checkpoint polled before each root and each search step. */
    public static <N, E extends Exception> List<List<N>> stronglyConnectedComponents(Iterable<? extends N> roots,
            Function<? super N, ? extends Iterable<? extends N>> successors, TopoSort.Checkpoint<E> checkpoint)
            throws E {
        return StronglyConnected.components(roots, successors, Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    /** Kahn sort with a FIFO ready queue. Nodes on or downstream of a cycle are reported as remaining. */
    public static <T> TopoSort.Result<T> topologicalSort(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors) {
        return TopoSort.kahnFifo(nodes, successors);
    }

    /** FIFO Kahn sort with a checkpoint polled before each emitted node. */
    public static <T, E extends Exception> TopoSort.Result<T> topologicalSort(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors, TopoSort.Checkpoint<E> checkpoint)
            throws E {
        return TopoSort.kahnFifo(nodes, successors, Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    /** Kahn sort whose ready queue is ordered by {@code readyOrder}. */
    public static <T> TopoSort.Result<T> topologicalSort(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors, Comparator<? super T> readyOrder) {
        return TopoSort.kahnOrdered(nodes, successors, readyOrder);
    }

    /** True when {@code nodes} and the edges among them form no cycle. */
    public static <T> boolean isAcyclic(Collection<T> nodes,
            Function<? super T, ? extends Iterable<? extends T>> successors) {
        return TopoSort.kahnFifo(nodes, successors).acyclic();
    }

    /** Immutable upstream/downstream adjacency over integer node ids. */
    public static <E> GraphAdjacency adjacency(Iterable<? extends E> edges, ToIntFunction<? super E> from,
            ToIntFunction<? super E> to) {
        return GraphAdjacency.of(edges, from, to);
    }
}
