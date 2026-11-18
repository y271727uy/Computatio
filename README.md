# Computatio

Computatio is a Java 17 algorithm library. The `fastpath` module provides platform-independent path planning and route execution data structures.

## FastPath API

Use `com.y271727uy.computatio.api.fastpath.FastPath` to search an immutable terrain snapshot.

```java
import com.y271727uy.computatio.api.fastpath.FastPath;
import com.y271727uy.computatio.algorithm.fastpath.model.GridPos;
import com.y271727uy.computatio.algorithm.fastpath.model.PathIntent;
import com.y271727uy.computatio.algorithm.fastpath.model.PathRequest;
import com.y271727uy.computatio.algorithm.fastpath.model.PathResult;
import com.y271727uy.computatio.algorithm.fastpath.model.PathVector;
import com.y271727uy.computatio.algorithm.fastpath.model.TraversalCell;
import com.y271727uy.computatio.algorithm.fastpath.model.TraversalSnapshot;

var start = new GridPos(0, 64, 0);
var target = new GridPos(0, 64, 1);
var request = new PathRequest("example:world", start, new PathVector(0.5, 64, 1.5), PathIntent.WALK);
var builder = TraversalSnapshot.builder(request.worldId(), start, target, 63, 65, 0L, true);
builder.addCell(new TraversalCell(0, 64, 0, 64, false, false, false, 1));
builder.addCell(new TraversalCell(0, 64, 1, 64, false, false, false, 1));
var snapshot = builder.build();

PathResult result = FastPath.find(request, snapshot);
```

Sample terrain and build the `TraversalSnapshot` on the platform's world thread. Pass `complete=false` if any terrain in the sampled region was unavailable. Submit the frozen data with `FastPath.findAsync(request, snapshot, workerExecutor)` for background search. If you use the supplier overload, the supplier runs on the supplied executor and must use only data captured on the world thread. Apply the route to the entity on its owning thread.

Check `result.status()` when arrival matters. `EXACT_TARGET` reaches the requested grid cell. `NEAR_TARGET` reaches a substitute cell when the target is unavailable in a complete snapshot. Both provide waypoints and return `true` from `success()`. The last waypoint gives the actual destination.

`INCOMPLETE_SNAPSHOT` means the sample is incomplete and the search could not establish a route to the requested cell. `NO_PATH` means the search exhausted its options in a complete snapshot. `SEARCH_LIMIT_REACHED` means the search stopped at its iteration limit. `WORLD_MISMATCH` indicates conflicting world IDs. `MISSING_START_OR_TARGET_CELL` means no usable cell was found near an endpoint in a complete snapshot. Failure results provide a machine-readable `reason()`.

The facade and its `algorithm.fastpath.model` types form the search API. Platforms using `PathRouteFollower` can implement `PathRouteFollowerPort.moveToWaypoint` to receive the full waypoint, including its movement mode. Existing ports receive the coordinates through `moveTo`.

## Multiset Index API

For data that is loaded once and queried repeatedly, build a reusable index:

```java
import com.y271727uy.computatio.api.multisetindex.FeatureMultiset;
import com.y271727uy.computatio.api.multisetindex.MultisetIndex;

MultisetIndex<Recipe> index = MultisetIndex.build(recipes, this::featuresFor);
Recipe match = index.findAny(availableFeatures, recipe -> true);
List<Recipe> matches = index.findAll(availableFeatures, recipe -> true);
int total = index.size();
int indexed = index.indexedSize();
int fallback = index.fallbackSize();

FeatureMultiset featuresFor(Recipe recipe) {
    return new FeatureMultiset().add(1, 1L);
}
```

`MultisetSearch.findAny` and `findAll` remain available for small one-shot searches, but they build an index for each call. Use `MultisetIndex` for long-lived databases such as recipe registries.

## Cycle Solver API

Use `com.y271727uy.computatio.api.cycle.CycleSolving` to solve one cyclic recipe component, modelled as a small Petri net. Each key is a material and each transition is a deduplicated recipe with gross per-firing consumption and production:

```java
import com.y271727uy.computatio.algorithm.cycle.*;
import com.y271727uy.computatio.api.cycle.CycleSolving;

// keys: 0 bucket, 1 water bucket, 2 water (supplied from outside), 3 sand (supplied), 4 concrete (target)
CycleNet.Builder net = CycleNet.builder(5).member(0).member(1).boundary(2).boundary(3)
        .stock(0, 1).required(4, 64);
net.addTransition("fill", new long[] {1, 0, 1, 0, 0}, new long[] {0, 1, 0, 0, 0});
net.addTransition("mix",  new long[] {0, 1, 0, 1, 0}, new long[] {1, 0, 0, 0, 1});

CycleSolution result = CycleSolving.solve(net.build());          // calling thread, default budget
CompletableFuture<CycleSolution> later = CycleSolving.solveAsync(net.build(), workerExecutor);
```

`SUCCESS` carries exact firing counts, a compact replayable `plan`, external demand, and the start-up seed. `INSUFFICIENT_EXTERNAL_INPUT` is returned only after an exhaustive search or an exact integer-infeasibility proof. `UNKNOWN_BUDGET` means the search stopped early and proves nothing. Amounts are exact (`PlannerAmount` promotes to `BigInteger` on overflow). Mark a transition as not `circuitSafe` when its recipe contract forbids static circuit algebra; the solver then uses the bounded marking search only. `PlanningBudget.Exhausted` and `InterruptedException` propagate to the caller.

`CycleSolving` also exposes `verifyFirings`, exact integer `materialBalance` / `sparseMaterialBalance`, and `repeatMultipliers`. The facade and its `algorithm.cycle` model types (`CycleNet`, `CycleSolution`, `CycleRun`, `CycleSolveLimits`, `PlanningBudget`, ...) form the cycle API.

## Graph and Math APIs

`com.y271727uy.computatio.api.graph.Graphs` provides iterative Tarjan SCC, Kahn topological sorting (FIFO, ordered, or with a checkpoint), `isAcyclic`, and integer adjacency. None of them recurses, so million-node chains are fine.

`com.y271727uy.computatio.api.math.SafeMath` provides saturating `long`/`int` arithmetic, `clampToLong`, `nonNegative`, and bucketed capacity accounting. Use `algorithm.amount.PlannerAmount` when an exact value is required; it stays on `long` and promotes to `BigInteger` on overflow.

Gradle consumers can depend on the published library:

```groovy
dependencies {
    implementation 'com.y271727uy:computatio:0.1.0'
}
```

`fastutil` is currently used behind the public multiset-index API for primitive collection performance. Public callers should use `FeatureMultiset` rather than importing the internal `algorithm.multisetindex` types.
