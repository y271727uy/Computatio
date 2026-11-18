package com.y271727uy.computatio.api.fastpath;

import com.y271727uy.computatio.algorithm.fastpath.model.PathRequest;
import com.y271727uy.computatio.algorithm.fastpath.model.PathResult;
import com.y271727uy.computatio.algorithm.fastpath.model.TraversalSnapshot;
import com.y271727uy.computatio.algorithm.fastpath.search.AsyncPathfinder;
import com.y271727uy.computatio.algorithm.fastpath.search.Pathfinder;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Searches immutable traversal snapshots prepared by the platform adapter. */
public final class FastPath {
    private FastPath() {
    }

    /**
     * Searches a frozen snapshot on the calling thread.
     * The search can resolve an unsampled or blocked endpoint to a nearby usable cell.
     * Inspect {@link PathResult#status()} before treating a route as exact.
     */
    public static PathResult find(PathRequest request, TraversalSnapshot snapshot) {
        return Pathfinder.find(request, snapshot);
    }

    /**
     * Searches a frozen snapshot on the supplied executor. Capture live terrain and build the
     * snapshot on the platform's world thread before calling this method.
     */
    public static CompletableFuture<PathResult> findAsync(
            PathRequest request,
            TraversalSnapshot snapshot,
            Executor workerExecutor) {
        Objects.requireNonNull(snapshot, "snapshot");
        return findAsync(request, () -> snapshot, workerExecutor);
    }

    /**
     * Builds a snapshot and searches it on the supplied executor. The supplier runs on that
     * executor and must only use data already captured on the platform's world thread.
     */
    public static CompletableFuture<PathResult> findAsync(
            PathRequest request,
            Supplier<TraversalSnapshot> snapshotSupplier,
            Executor workerExecutor) {
        return AsyncPathfinder.findAsync(request, snapshotSupplier, workerExecutor);
    }
}
