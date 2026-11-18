package com.y271727uy.computatio.algorithm.fastpath.search;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.model.PathResult.Status;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Grid based A* pathfinder that operates exclusively on an immutable {@link TraversalSnapshot}.
 *
 * <p>The search never touches the live world: every traversability decision is made from the
 * pre-sampled {@link TraversalCell} grid produced on the server thread. The resulting waypoint list is
 * geometrically smoothed so the follower can drive long straight segments, while keeping every
 * emitted edge provably walkable so the citizen never cuts a corner or crosses a gap it should
 * have routed around.
 */
public final class Pathfinder {
    private static final int MAX_ITERATIONS = 20000;
    private static final int NEAREST_RANGE = 5;
    private static final int SMOOTH_LOOKAHEAD = 24;
    private static final double WALK_CORRIDOR_HALF_WIDTH = 0.36D;
    private static final double WALK_CORRIDOR_SAMPLE_STEP = 0.20D;
    private static final double STEP_CLEARANCE = 0.55D;
    private static final ThreadLocal<List<Neighbor>> NEIGHBOR_SCRATCH =
            ThreadLocal.withInitial(() -> new ArrayList<>(32));

    private Pathfinder() {
    }

    /**
     * Computes a path from the request start to its target over the given snapshot.
     *
     * @param request the movement request describing start, target and intent
     * @param snapshot the immutable world sample the search runs on
     * @return a successful result with smoothed waypoints, or a failed result with a reason code
     */
    public static PathResult find(PathRequest request, TraversalSnapshot snapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!request.worldId().equals(snapshot.worldId())) {
            return PathResult.failure(Status.WORLD_MISMATCH);
        }
        GridPos requestedTarget = request.targetBlock();
        TraversalCell start = nearestCell(snapshot, request.start(), NEAREST_RANGE, request.capabilities());
        TraversalCell target = nearestCell(snapshot, requestedTarget, NEAREST_RANGE, request.capabilities());
        boolean exactTarget = target != null && requestedTarget.equals(target.position());
        if (!snapshot.complete() && (start == null || !exactTarget)) {
            return PathResult.failure(Status.INCOMPLETE_SNAPSHOT);
        }
        if (start == null || target == null) {
            return PathResult.failure(Status.MISSING_START_OR_TARGET_CELL);
        }
        if (start.key() == target.key()) {
            return routeResult(List.of(PathWaypoint.of(target, initialMode(snapshot, target, request))), exactTarget);
        }

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.fCost));
        Long2ObjectOpenHashMap<SearchNode> bestNodes = new Long2ObjectOpenHashMap<>();
        SearchNode startNode = new SearchNode(start, null, initialMode(snapshot, start, request), 0.0D, heuristic(start, target));
        open.add(startNode);
        bestNodes.put(start.key(), startNode);

        int iterations = 0;
        while (!open.isEmpty() && iterations++ < MAX_ITERATIONS) {
            SearchNode current = open.poll();
            if (bestNodes.get(current.cell.key()) != current) {
                continue;
            }
            if (current.cell.key() == target.key()) {
                return routeResult(smooth(snapshot, reconstruct(current)), exactTarget);
            }
            for (Neighbor neighbor : neighbors(snapshot, current.cell, target, request.intent(), request.capabilities())) {
                double nextCost = current.gCost + neighbor.cost;
                SearchNode existing = bestNodes.get(neighbor.cell.key());
                if (existing != null && existing.gCost <= nextCost) {
                    continue;
                }
                SearchNode next = new SearchNode(neighbor.cell, current, neighbor.mode, nextCost, nextCost + heuristic(neighbor.cell, target));
                bestNodes.put(neighbor.cell.key(), next);
                open.add(next);
            }
        }
        if (!open.isEmpty()) {
            return PathResult.failure(Status.SEARCH_LIMIT_REACHED);
        }
        return PathResult.failure(snapshot.complete() ? Status.NO_PATH : Status.INCOMPLETE_SNAPSHOT);
    }

    private static PathResult routeResult(List<PathWaypoint> waypoints, boolean exactTarget) {
        return exactTarget ? PathResult.success(waypoints) : PathResult.nearTarget(waypoints);
    }

    /**
     * Expands the traversable neighbours of a cell into a reusable scratch list.
     *
     * <p>The returned list is thread-local scratch storage that is cleared on every call, so a
     * caller must finish iterating it before invoking this method again. Because the search runs on
     * a fixed worker pool this avoids one list allocation per expanded node.
     */
    private static List<Neighbor> neighbors(TraversalSnapshot snapshot, TraversalCell current, TraversalCell target,
                                            PathIntent intent, PathCapabilities capabilities) {
        List<Neighbor> neighbors = NEIGHBOR_SCRATCH.get();
        neighbors.clear();
        if (capabilities.canFly()) {
            addFlightNeighbors(snapshot, current, capabilities, neighbors);
        }
        if (current.water()) {
            addWaterNeighbors(snapshot, current, intent, neighbors);
        } else {
            if (current.climbable() && capabilities.canClimbLadders()) {
            addClimbNeighbors(snapshot, current, intent, neighbors);
            addClimbTopExitNeighbors(snapshot, current, neighbors);
            addClimbDropOffNeighbors(snapshot, current, neighbors);
            }
            addWalkNeighbors(snapshot, current, intent, neighbors);
            addSpecialEntryNeighbors(snapshot, current, neighbors);
            addVerticalTransitions(snapshot, current, intent, neighbors);
        }
        if (capabilities.canClimbWalls()) {
            addWallClimbNeighbors(snapshot, current, capabilities, neighbors);
        }
        neighbors.removeIf(neighbor -> !isAllowedNeighbor(neighbor, capabilities));
        return neighbors;
    }

    /** Adds collision-safe 26-connected free-space edges for flying mobs. */
    private static void addFlightNeighbors(TraversalSnapshot snapshot, TraversalCell current,
                                           PathCapabilities capabilities, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    TraversalCell next = flightCell(snapshot, current.x() + dx, current.y() + dy,
                            current.z() + dz, capabilities);
                    if (next != null && canFlyBetween(snapshot, current, next, capabilities)) {
                        output.add(new Neighbor(next, MovementMode.FLY, distance(current, next)));
                    }
                }
            }
        }
    }

    /** Adds vertical wall-climbing edges where a body-sized wall surface is adjacent. */
    private static void addWallClimbNeighbors(TraversalSnapshot snapshot, TraversalCell current,
                                              PathCapabilities capabilities, List<Neighbor> output) {
        if (!hasWallSurface(snapshot, current)) {
            return;
        }
        for (int dy = -1; dy <= 1; dy += 2) {
            TraversalCell next = flightCell(snapshot, current.x(), current.y() + dy, current.z(), capabilities);
            if (next != null && hasVerticalPassage(snapshot, current, next)) {
                output.add(new Neighbor(next, MovementMode.WALL_CLIMB, 5.0D + distance(current, next)));
            }
        }
    }

    /** Creates a sampled cell for existing occupancy or a free-space cell used by flight/climbing. */
    private static TraversalCell flightCell(TraversalSnapshot snapshot, int x, int y, int z,
                                            PathCapabilities capabilities) {
        if (y < snapshot.minY() || y > snapshot.maxY()) {
            return null;
        }
        TraversalCell existing = snapshot.cell(x, y, z);
        if (existing != null) {
            return isAllowedCell(existing, capabilities) ? existing : null;
        }
        if (!snapshot.bodyPassage(x, y, z)) {
            return null;
        }
        return new TraversalCell(x, y, z, y, false, false, false, 1.0D);
    }

    /** Checks voxel clearance and thin barriers along the edges of the swept cell cube. */
    private static boolean canFlyBetween(TraversalSnapshot snapshot, TraversalCell from, TraversalCell to,
                                         PathCapabilities capabilities) {
        int dx = Integer.compare(to.x() - from.x(), 0);
        int dy = Integer.compare(to.y() - from.y(), 0);
        int dz = Integer.compare(to.z() - from.z(), 0);
        int nonZeroAxes = (dx == 0 ? 0 : 1) | (dy == 0 ? 0 : 2) | (dz == 0 ? 0 : 4);
        for (int mask = 0; mask <= nonZeroAxes; mask++) {
            if ((mask & nonZeroAxes) != mask) {
                continue;
            }
            int x = from.x() + ((mask & 1) != 0 ? dx : 0);
            int y = from.y() + ((mask & 2) != 0 ? dy : 0);
            int z = from.z() + ((mask & 4) != 0 ? dz : 0);
            if (mask != 0 && mask != nonZeroAxes && !isFlightPassage(snapshot, x, y, z, capabilities)) {
                return false;
            }
            if (dx != 0 && (mask & 1) == 0 && snapshot.blocksHorizontalBoundary(x, y, z, x + dx, z)) {
                return false;
            }
            if (dz != 0 && (mask & 4) == 0 && snapshot.blocksHorizontalBoundary(x, y, z, x, z + dz)) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether a free-space sample or an openable wooden door can contain the body. */
    private static boolean isFlightPassage(TraversalSnapshot snapshot, int x, int y, int z,
                                           PathCapabilities capabilities) {
        if (snapshot.bodyPassage(x, y, z)) {
            return true;
        }
        TraversalCell cell = snapshot.cell(x, y, z);
        return capabilities.canOpenDoors() && cell != null && cell.woodenDoor();
    }

    /** Detects a full-height solid face adjacent to a climbable free-space column. */
    private static boolean hasWallSurface(TraversalSnapshot snapshot, TraversalCell cell) {
        return (!snapshot.bodyPassage(cell.x() - 1, cell.y(), cell.z())
                    && !snapshot.bodyPassage(cell.x() - 1, cell.y() + 1, cell.z()))
                || (!snapshot.bodyPassage(cell.x() + 1, cell.y(), cell.z())
                    && !snapshot.bodyPassage(cell.x() + 1, cell.y() + 1, cell.z()))
                || (!snapshot.bodyPassage(cell.x(), cell.y(), cell.z() - 1)
                    && !snapshot.bodyPassage(cell.x(), cell.y() + 1, cell.z() - 1))
                || (!snapshot.bodyPassage(cell.x(), cell.y(), cell.z() + 1)
                    && !snapshot.bodyPassage(cell.x(), cell.y() + 1, cell.z() + 1));
    }

    /** Applies capability gates to generated edges and sampled door cells. */
    private static boolean isAllowedNeighbor(Neighbor neighbor, PathCapabilities capabilities) {
        return isAllowedCell(neighbor.cell, capabilities)
                && (capabilities.canClimbLadders() || neighbor.mode != MovementMode.CLIMB);
    }

    /** Returns whether a sampled cell is usable with the requested door capability. */
    private static boolean isAllowedCell(TraversalCell cell, PathCapabilities capabilities) {
        return capabilities.canOpenDoors() || !cell.woodenDoor();
    }

    /**
     * Adds 8-connected same-layer walk edges, validating each diagonal with a footprint corridor so
     * the body cannot clip the solid corner between two orthogonal blocks.
     */
    private static void addWalkNeighbors(TraversalSnapshot snapshot, TraversalCell current, PathIntent intent, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                TraversalCell next = snapshot.cell(current.x() + dx, current.y(), current.z() + dz);
                if (next != null && canWalkOnSameLayer(current, next)) {
                    if (dx != 0 && dz != 0 && !hasClearWalkLine(snapshot, current.position(), next.position())) {
                        continue;
                    }
                    if ((dx == 0 || dz == 0) && !canCrossHorizontalBoundary(snapshot, current, next)) {
                        continue;
                    }
                    output.add(new Neighbor(next, walkMode(intent), distance(current, next) * next.cost()));
                }
            }
        }
    }

    /**
     * Adds same-layer entries into water or climbable cells, guarding diagonals with the corner
     * test so the entry does not pass through a solid corner block.
     */
    private static void addSpecialEntryNeighbors(TraversalSnapshot snapshot, TraversalCell current, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx != 0 && dz != 0 && !diagonalClear(snapshot, current, dx, dz)) {
                    continue;
                }
                TraversalCell next = snapshot.cell(current.x() + dx, current.y(), current.z() + dz);
                if (next == null) {
                    continue;
                }
                if ((dx == 0 || dz == 0) && !canCrossHorizontalBoundary(snapshot, current, next)) {
                    continue;
                }
                if (next.water()) {
                    output.add(new Neighbor(next, MovementMode.SWIM, 1.0D + distance(current, next) * next.cost()));
                } else if (next.climbable()) {
                    output.add(new Neighbor(next, MovementMode.CLIMB, 1.0D + distance(current, next) * next.cost()));
                }
            }
        }
    }

    /**
     * Adds vertical step-up ({@link MovementMode#JUMP}) and drop ({@link MovementMode#FALL})
     * transitions.
     *
     * <p>Only orthogonal directions are emitted. A diagonal jump or fall would sweep the body
     * through the shared corner column at the destination level, which the snapshot's per-column
     * clearance never validates, so such edges let the citizen visibly cut across a corner or pit
     * while changing height. Diagonal elevation changes remain reachable as an L-shaped composition
     * of an orthogonal walk followed by an orthogonal jump or fall, where each composing edge is
     * individually corner-safe.
     *
     * <p>This generator also mounts a ladder/vine/scaffold one level above or below an adjacent
     * floor: a citizen standing on the rim of a shaft must be able to step sideways-and-down onto
     * the first rung to descend (the common "hole in the floor with a ladder below the rim" build),
     * or sideways-and-up onto a rung whose lowest cell sits one block above the floor. These
     * {@code CLIMB} entries are orthogonal-only for the same corner-safety reason, and once the
     * citizen is on the ladder {@link #addClimbNeighbors} drives the vertical travel.
     */
    private static void addVerticalTransitions(TraversalSnapshot snapshot, TraversalCell current, PathIntent intent, List<Neighbor> output) {
        if (current.climbable()) {
            return;
        }
        addDirectDropTransition(snapshot, current, output);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx != 0 && dz != 0) {
                    continue;
                }
                TraversalCell up = snapshot.cell(current.x() + dx, current.y() + 1, current.z() + dz);
                if (up != null
                        && !up.water()
                        && !up.climbable()
                        && up.standY() - current.standY() <= 1.25D
                        && canCrossHorizontalBoundary(snapshot, current, up)
                        && hasVerticalPassage(snapshot, current, up)) {
                    output.add(new Neighbor(up, MovementMode.JUMP, 2.5D + distance(current, up)));
                }
                if (up != null
                        && up.climbable()
                        && up.standY() - current.standY() <= 1.25D
                        && canCrossHorizontalBoundary(snapshot, current, up)
                        && hasVerticalPassage(snapshot, current, up)) {
                    output.add(new Neighbor(up, MovementMode.CLIMB, 8.0D + distance(current, up)));
                }
                TraversalCell ladderBelow = snapshot.cell(current.x() + dx, current.y() - 1, current.z() + dz);
                if (ladderBelow != null
                        && ladderBelow.climbable()
                        && current.standY() - ladderBelow.standY() <= 1.25D
                        && canCrossHorizontalBoundary(snapshot, current, ladderBelow)
                        && hasVerticalPassage(snapshot, current, ladderBelow)) {
                    output.add(new Neighbor(ladderBelow, MovementMode.CLIMB, 8.0D + distance(current, ladderBelow)));
                }
                for (int fall = 1; fall <= 3; fall++) {
                    TraversalCell down = snapshot.cell(current.x() + dx, current.y() - fall, current.z() + dz);
                    if (down != null
                            && !down.climbable()
                            && current.standY() - down.standY() <= 3.5D
                            && canCrossHorizontalBoundary(snapshot, current, down)
                            && hasVerticalPassage(snapshot, current, down)) {
                        output.add(new Neighbor(down, down.water() ? MovementMode.SWIM : MovementMode.FALL,
                                down.water() ? 1.8D : fallCost(current.standY() - down.standY())));
                        break;
                    }
                }
            }
        }
        TraversalCell waterBelow = snapshot.cell(current.x(), current.y() - 1, current.z());
        if (waterBelow != null && waterBelow.water() && hasVerticalPassage(snapshot, current, waterBelow)) {
            output.add(new Neighbor(waterBelow, MovementMode.SWIM, 1.8D));
        }
    }

    /**
     * Adds a straight fall through an open shaft, such as an open floor trapdoor. The destination
     * must already be a valid occupiable cell, and every intermediate body column must be open; a
     * solid floor therefore cannot be skipped just because it happens to have a lower cell below it.
     */
    private static void addDirectDropTransition(TraversalSnapshot snapshot, TraversalCell current,
                                                List<Neighbor> output) {
        for (int fall = 1; fall <= 3; fall++) {
            TraversalCell down = snapshot.cell(current.x(), current.y() - fall, current.z());
            if (down == null || down.climbable()) {
                continue;
            }
            double heightDrop = current.standY() - down.standY();
            if (heightDrop < 0.25D || heightDrop > 3.5D || !hasVerticalPassage(snapshot, current, down)) {
                break;
            }
            output.add(new Neighbor(down, down.water() ? MovementMode.SWIM : MovementMode.FALL,
                    down.water() ? 1.8D : fallCost(heightDrop)));
            break;
        }
    }

    /**
     * Adds movement edges that leave or traverse a water cell.
     *
     * <p>The {@link #neighbors} dispatcher returns early for water cells, so this generator is the
     * sole source of water-exit edges and must apply the same guards the land generators do.
     * Water-to-water moves stay permissive so narrow canals remain navigable, but a diagonal still
     * requires both orthogonal corner columns. A same-layer walk exit reuses the corridor check, an
     * adjacent ladder/vine/scaffold is entered with a {@code CLIMB} edge so the swimmer can climb
     * out of a pool, and vertical (jump/fall) exits are emitted for orthogonal directions only,
     * mirroring {@link #addVerticalTransitions} so the body never cuts the destination-level corner.
     */
    private static void addWaterNeighbors(TraversalSnapshot snapshot, TraversalCell current, PathIntent intent, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    TraversalCell next = snapshot.cell(current.x() + dx, current.y() + dy, current.z() + dz);
                    if (next == null) {
                        continue;
                    }
                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal && !diagonalClear(snapshot, current, dx, dz)) {
                        continue;
                    }
                    if (!diagonal && (dx != 0 || dz != 0) && !canCrossHorizontalBoundary(snapshot, current, next)) {
                        continue;
                    }
                    if (next.water()) {
                        output.add(new Neighbor(next, MovementMode.SWIM, distance(current, next) * next.cost()));
                        continue;
                    }
                    if (next.climbable()) {
                        // Grab an adjacent ladder/vine/scaffold so the swimmer can climb out; the
                        // climb generator then drives the ascent. A pure 3D diagonal is skipped
                        // because its destination-level corner cannot be validated.
                        if (dy == 0 || !diagonal) {
                            output.add(new Neighbor(next, MovementMode.CLIMB, 1.0D + distance(current, next) * next.cost()));
                        }
                        continue;
                    }
                    if (dy == 0) {
                        if (!canWalkOnSameLayer(current, next)) {
                            continue;
                        }
                        if (diagonal && !hasClearWalkLine(snapshot, current.position(), next.position())) {
                            continue;
                        }
                        output.add(new Neighbor(next, walkMode(intent), distance(current, next) * next.cost()));
                    } else if (dy == 1) {
                        // Orthogonal only, matching addVerticalTransitions: a diagonal jump would
                        // sweep the body through the destination-level corner column.
                        if (!diagonal
                                && next.standY() - current.standY() <= 1.25D
                                && canCrossHorizontalBoundary(snapshot, current, next)
                                && hasVerticalPassage(snapshot, current, next)) {
                            output.add(new Neighbor(next, MovementMode.JUMP, 2.5D + distance(current, next)));
                        }
                    } else {
                        if (!diagonal
                                && current.standY() - next.standY() <= 3.5D
                                && canCrossHorizontalBoundary(snapshot, current, next)
                                && hasVerticalPassage(snapshot, current, next)) {
                            output.add(new Neighbor(next, MovementMode.FALL, fallCost(current.standY() - next.standY())));
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds the straight up and down transitions available while climbing a ladder, vine or
     * scaffold.
     *
     * <p>The downward edge mirrors the upward edge's water handling: descending off the lowest rung
     * into a water column is a {@link MovementMode#SWIM}, not a {@code WALK} the follower would drive
     * as a flat land step into the pool.
     */
    private static void addClimbNeighbors(TraversalSnapshot snapshot, TraversalCell current, PathIntent intent, List<Neighbor> output) {
        TraversalCell up = snapshot.cell(current.x(), current.y() + 1, current.z());
        if (up != null && (up.climbable() || up.water())) {
            output.add(new Neighbor(up, up.water() ? MovementMode.SWIM : MovementMode.CLIMB, up.water() ? 2.0D : 6.0D));
        }
        TraversalCell down = snapshot.cell(current.x(), current.y() - 1, current.z());
        if (down != null) {
            MovementMode downMode = down.water() ? MovementMode.SWIM : down.climbable() ? MovementMode.CLIMB : walkMode(intent);
            output.add(new Neighbor(down, downMode, down.climbable() ? 6.0D : 2.0D));
        }
    }

    /**
     * Adds a virtual climb point above the top rung when the ladder stops one block below a floor.
     */
    private static void addClimbTopExitNeighbors(TraversalSnapshot snapshot, TraversalCell current, List<Neighbor> output) {
        TraversalCell sampledCurrent = snapshot.cell(current.position());
        if (sampledCurrent == null || !sampledCurrent.climbable()) {
            return;
        }
        int exitY = current.y() + 1;
        TraversalCell sampledTop = snapshot.cell(current.x(), exitY, current.z());
        if (sampledTop != null && (sampledTop.climbable() || sampledTop.water())) {
            return;
        }
        if (sampledTop == null && !snapshot.bodyPassage(current.x(), exitY, current.z())) {
            return;
        }
        TraversalCell topExit = sampledTop != null
                ? sampledTop
                : new TraversalCell(current.x(), exitY, current.z(), exitY, false, true, false, 2.0D);
        if (hasUpperFloorExit(snapshot, topExit)) {
            output.add(new Neighbor(topExit, MovementMode.CLIMB, 2.0D));
        }
    }

    /**
     * hasUpperFloorExit: 鍒ゆ柇铏氭嫙姊《鏃佽竟鏄惁瀛樺湪鍙蛋涓婂眰鍦版澘銆?
     */
    private static boolean hasUpperFloorExit(TraversalSnapshot snapshot, TraversalCell virtualTop) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || dx != 0 && dz != 0) {
                    continue;
                }
                TraversalCell exit = snapshot.cell(virtualTop.x() + dx, virtualTop.y(), virtualTop.z() + dz);
                if (canWalkOnSameLayer(virtualTop, exit)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds the controlled drop a citizen can take when stepping OFF a ladder/vine/scaffold onto a
     * floor that is one to three blocks lower and orthogonally adjacent.
     *
     * <p>{@link #addVerticalTransitions} early-returns for climbable cells and {@link
     * #addClimbNeighbors} only travels straight up/down the shaft, so without this edge a ladder
     * whose base sits above an offset ledge (the directly-below column blocked) is a dead end. Like
     * the rest of the vertical generators it is orthogonal-only so the body never cuts the
     * destination-level corner, it reuses {@link #hasVerticalPassage} so it cannot drill through a
     * solid floor, and a landing in water is a {@link MovementMode#SWIM}.
     */
    private static void addClimbDropOffNeighbors(TraversalSnapshot snapshot, TraversalCell current, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || dx != 0 && dz != 0) {
                    continue;
                }
                for (int fall = 1; fall <= 3; fall++) {
                    TraversalCell down = snapshot.cell(current.x() + dx, current.y() - fall, current.z() + dz);
                    if (down != null
                            && !down.climbable()
                            && current.standY() - down.standY() <= 3.5D
                            && canCrossHorizontalBoundary(snapshot, current, down)
                            && hasVerticalPassage(snapshot, current, down)) {
                        output.add(new Neighbor(down, down.water() ? MovementMode.SWIM : MovementMode.FALL,
                                down.water() ? 1.8D : fallCost(current.standY() - down.standY())));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Returns whether both orthogonal routes around a diagonal step are clear. A thin wall panel
     * on either route still intersects the body while it cuts the shared corner.
     */
    private static boolean diagonalClear(TraversalSnapshot snapshot, TraversalCell current, int dx, int dz) {
        TraversalCell acrossX = snapshot.cell(current.x() + dx, current.y(), current.z());
        TraversalCell acrossZ = snapshot.cell(current.x(), current.y(), current.z() + dz);
        TraversalCell target = snapshot.cell(current.x() + dx, current.y(), current.z() + dz);
        return target != null
                && acrossX != null
                && acrossZ != null
                && canCrossHorizontalBoundary(snapshot, current, acrossX)
                && canCrossHorizontalBoundary(snapshot, acrossX, target)
                && canCrossHorizontalBoundary(snapshot, current, acrossZ)
                && canCrossHorizontalBoundary(snapshot, acrossZ, target);
    }

    /** Returns whether a one-block horizontal move clears thin collision panels at both feet levels. */
    private static boolean canCrossHorizontalBoundary(TraversalSnapshot snapshot, TraversalCell from, TraversalCell to) {
        int horizontalDistance = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
        if (horizontalDistance != 1) {
            return horizontalDistance == 0;
        }
        if (snapshot.blocksHorizontalBoundary(from.x(), from.y(), from.z(), to.x(), to.z())) {
            return false;
        }
        return from.y() == to.y()
                || !snapshot.blocksHorizontalBoundary(from.x(), to.y(), from.z(), to.x(), to.z());
    }

    /**
     * Returns whether the target column has an actual open shaft for a vertical transition.
     */
    private static boolean hasVerticalPassage(TraversalSnapshot snapshot, TraversalCell from, TraversalCell to) {
        int minY = Math.min(from.y(), to.y()) + 1;
        int maxY = Math.max(from.y(), to.y());
        for (int y = minY; y <= maxY; y++) {
            if (!snapshot.bodyPassage(to.x(), y, to.z())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether {@code to} is a solid-floored cell the citizen can step onto from {@code from}
     * without changing layer, i.e. it is not water or climbable and the stand height differs by at
     * most a single step.
     */
    private static boolean canWalkOnSameLayer(TraversalCell from, TraversalCell to) {
        return to != null
                && !to.water()
                && !to.climbable()
                && Math.abs(from.standY() - to.standY()) <= 0.75D;
    }

    /**
     * Rebuilds the ordered waypoint list by walking parent links back from the goal node.
     */
    private static List<PathWaypoint> reconstruct(SearchNode end) {
        List<SearchNode> nodes = new ArrayList<>();
        SearchNode current = end;
        while (current != null) {
            nodes.add(current);
            current = current.parent;
        }
        List<PathWaypoint> waypoints = new ArrayList<>(nodes.size());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            SearchNode node = nodes.get(i);
            waypoints.add(PathWaypoint.of(node.cell, node.mode));
        }
        return waypoints;
    }

    /**
     * Collapses runs of collinear, same-mode waypoints into straight segments the follower can
     * drive directly, while preserving every waypoint a merge would unsafely skip.
     */
    private static List<PathWaypoint> smooth(TraversalSnapshot snapshot, List<PathWaypoint> rawWaypoints) {
        if (rawWaypoints.size() <= 2) {
            return rawWaypoints;
        }
        boolean[] woodenDoor = new boolean[rawWaypoints.size()];
        for (int index = 0; index < woodenDoor.length; index++) {
            woodenDoor[index] = isWoodenDoorWaypoint(snapshot, rawWaypoints.get(index));
        }
        List<PathWaypoint> smoothed = new ArrayList<>(rawWaypoints.size());
        int anchor = 0;
        smoothed.add(rawWaypoints.get(anchor));
        while (anchor < rawWaypoints.size() - 1) {
            int runCap = contiguousRunCap(rawWaypoints, woodenDoor, anchor);
            int maxCandidate = Math.min(runCap, anchor + SMOOTH_LOOKAHEAD);
            int best = anchor + 1;
            for (int candidate = maxCandidate; candidate > anchor + 1; candidate--) {
                if (canSmoothSegment(snapshot, rawWaypoints, anchor, candidate, woodenDoor)) {
                    best = candidate;
                    break;
                }
            }
            smoothed.add(rawWaypoints.get(best));
            anchor = best;
        }
        return smoothed;
    }

    /**
     * Returns the furthest waypoint index reachable from {@code anchor} for which every waypoint in
     * between shares the anchor's mode and feet level and is neither an action waypoint nor a wooden
     * door. Candidates past this index can never merge, so the smoothing probe stops there instead
     * of re-running the cheap checks for each candidate.
     */
    private static int contiguousRunCap(List<PathWaypoint> waypoints, boolean[] woodenDoor, int anchor) {
        PathWaypoint from = waypoints.get(anchor);
        if (isActionMode(from.mode()) || woodenDoor[anchor]) {
            return anchor + 1;
        }
        MovementMode mode = from.mode();
        int feetY = from.blockPosition().y();
        int cap = anchor;
        while (cap + 1 <= waypoints.size() - 1) {
            int candidate = cap + 1;
            PathWaypoint waypoint = waypoints.get(candidate);
            if (waypoint.mode() != mode
                    || isActionMode(waypoint.mode())
                    || waypoint.blockPosition().y() != feetY
                    || woodenDoor[candidate]) {
                break;
            }
            cap = candidate;
        }
        return Math.max(anchor + 1, cap);
    }

    /**
     * Determines whether the waypoints in {@code [fromIndex, toIndex]} can be merged into a single
     * straight segment.
     *
     * <p>A merge is allowed only when every intermediate waypoint shares the endpoints' movement
     * mode and feet level, is not an action waypoint or wooden door, stays within
     * {@link #STEP_CLEARANCE} of the interpolated endpoint chord, and the straight corridor between
     * the endpoints is clear. The chord check is essential: {@link #hasClearWalkLine} only bounds
     * the height change between consecutive cells, so without it a 64.0 to 64.75 to 64.0 run would
     * merge and the follower would drive a flat line straight through the 0.75 lip.
     */
    private static boolean canSmoothSegment(TraversalSnapshot snapshot, List<PathWaypoint> waypoints, int fromIndex, int toIndex, boolean[] woodenDoor) {
        PathWaypoint from = waypoints.get(fromIndex);
        PathWaypoint to = waypoints.get(toIndex);
        MovementMode mode = from.mode();
        if (mode != to.mode()
                || isActionMode(mode)
                || from.blockPosition().y() != to.blockPosition().y()
                || woodenDoor[fromIndex]
                || woodenDoor[toIndex]) {
            return false;
        }
        PathVector fromPosition = from.position();
        PathVector toPosition = to.position();
        double segmentX = toPosition.x() - fromPosition.x();
        double segmentZ = toPosition.z() - fromPosition.z();
        for (int index = fromIndex + 1; index < toIndex; index++) {
            PathWaypoint waypoint = waypoints.get(index);
            if (waypoint.mode() != mode
                    || isActionMode(waypoint.mode())
                    || waypoint.blockPosition().y() != from.blockPosition().y()
                    || woodenDoor[index]) {
                return false;
            }
            if (exceedsChordStep(fromPosition, toPosition, waypoint.position(), segmentX, segmentZ)) {
                return false;
            }
        }
        return hasClearWalkLine(snapshot, from.blockPosition(), to.blockPosition());
    }

    /**
     * Returns whether the stand height of {@code mid} rises more than {@link #STEP_CLEARANCE} above
     * the height the follower would interpolate at that point along the {@code from}-to-{@code to}
     * chord. Progress is measured on the dominant horizontal axis to stay stable for axis-aligned
     * segments.
     */
    private static boolean exceedsChordStep(PathVector from, PathVector to, PathVector mid, double segmentX, double segmentZ) {
        double progress;
        if (Math.abs(segmentX) >= Math.abs(segmentZ)) {
            progress = segmentX != 0.0D ? (mid.x() - from.x()) / segmentX : 0.0D;
        } else {
            progress = segmentZ != 0.0D ? (mid.z() - from.z()) / segmentZ : 0.0D;
        }
        progress = Math.max(0.0D, Math.min(1.0D, progress));
        double chordY = from.y() + (to.y() - from.y()) * progress;
        return mid.y() - chordY > STEP_CLEARANCE;
    }

    /**
     * Returns whether the waypoint stands on a wooden door cell, which must never be merged away
     * because the follower opens the door as it arrives.
     */
    private static boolean isWoodenDoorWaypoint(TraversalSnapshot snapshot, PathWaypoint waypoint) {
        TraversalCell cell = snapshot.cell(waypoint.blockPosition());
        return cell != null && cell.woodenDoor();
    }

    /**
     * Returns whether a citizen can walk the straight line from {@code from} to {@code to} on a
     * single layer without leaving the walkable floor.
     *
     * <p>The corridor is sampled at sub-block resolution; at each sample the full footprint corners
     * must rest on a same-layer cell, every entered cell must be walkable from the previous one, no
     * sample may jump more than one cell, and diagonal transitions must pass the corner test.
     */
    private static boolean hasClearWalkLine(TraversalSnapshot snapshot, GridPos from, GridPos to) {
        TraversalCell previous = snapshot.cell(from);
        TraversalCell target = snapshot.cell(to);
        if (previous == null || target == null || !canWalkOnSameLayer(previous, target)) {
            return false;
        }

        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        int footprintSteps = Math.max(1, (int) Math.ceil(horizontalDistance / WALK_CORRIDOR_SAMPLE_STEP));

        int lastX = from.x();
        int lastZ = from.z();
        for (int step = 1; step <= footprintSteps; step++) {
            double ratio = (double) step / (double) footprintSteps;
            double sampleX = from.x() + 0.5D + dx * ratio;
            double sampleZ = from.z() + 0.5D + dz * ratio;
            if (!hasFootprintClearance(snapshot, previous, sampleX, sampleZ)) {
                return false;
            }
            int x = (int) Math.floor(sampleX);
            int z = (int) Math.floor(sampleZ);
            if (x == lastX && z == lastZ) {
                continue;
            }
            int stepX = Integer.compare(x - lastX, 0);
            int stepZ = Integer.compare(z - lastZ, 0);
            if (Math.abs(x - lastX) > 1 || Math.abs(z - lastZ) > 1) {
                return false;
            }
            TraversalCell current = snapshot.cell(x, from.y(), z);
            if (current == null || !canWalkOnSameLayer(previous, current)) {
                return false;
            }
            if (stepX == 0 || stepZ == 0) {
                if (!canCrossHorizontalBoundary(snapshot, previous, current)) {
                    return false;
                }
            }
            if (stepX != 0 && stepZ != 0 && !diagonalClear(snapshot, previous, stepX, stepZ)) {
                return false;
            }
            previous = current;
            lastX = x;
            lastZ = z;
        }
        return true;
    }

    /**
     * Returns whether the citizen's square footprint, centred at the sample point, rests entirely
     * on cells that are walkable on the reference cell's layer.
     */
    private static boolean hasFootprintClearance(TraversalSnapshot snapshot, TraversalCell reference, double centerX, double centerZ) {
        return hasPointClearance(snapshot, reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH);
    }

    /**
     * Returns whether the cell under a single footprint corner exists and is walkable on the
     * reference cell's layer.
     */
    private static boolean hasPointClearance(TraversalSnapshot snapshot, TraversalCell reference, double x, double z) {
        TraversalCell cell = snapshot.cell((int) Math.floor(x), reference.y(), (int) Math.floor(z));
        return cell != null && canWalkOnSameLayer(reference, cell);
    }

    /**
     * Returns the snapshot cell at {@code target}, or the closest cell within {@code range} when the
     * exact block is not part of the grid.
     */
    private static TraversalCell nearestCell(TraversalSnapshot snapshot, GridPos target, int range,
                                             PathCapabilities capabilities) {
        if (capabilities.canFly()) {
            TraversalCell directFlight = flightCell(snapshot, target.x(), target.y(), target.z(), capabilities);
            if (directFlight != null) {
                return directFlight;
            }
        }
        TraversalCell direct = snapshot.cell(target);
        if (direct != null && isAllowedCell(direct, capabilities)) {
            return direct;
        }
        TraversalCell best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    TraversalCell cell = snapshot.cell(target.x() + dx, target.y() + dy, target.z() + dz);
                    if (cell == null || !isAllowedCell(cell, capabilities)) continue;
                    double d = (double)(dx * dx + dy * dy + dz * dz);
                    if (d < bestDistance) { best = cell; bestDistance = d; }
                }
            }
        }
        if ((capabilities.canFly() || capabilities.canClimbWalls()) && best == null) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dy = -range; dy <= range; dy++) {
                    for (int dz = -range; dz <= range; dz++) {
                        TraversalCell cell = flightCell(snapshot, target.x() + dx, target.y() + dy,
                                target.z() + dz, capabilities);
                        if (cell == null) continue;
                        double d = (double) (dx * dx + dy * dy + dz * dz);
                        if (d < bestDistance) { best = cell; bestDistance = d; }
                    }
                }
            }
        }
        return best;
    }

    /** Selects a movement mode for a virtual free-space source used by flight or wall climbing. */
    private static MovementMode initialMode(TraversalSnapshot snapshot, TraversalCell start,
                                            PathRequest request) {
        if (snapshot.cell(start.position()) == null) {
            if (request.capabilities().canFly()) {
                return MovementMode.FLY;
            }
            if (request.capabilities().canClimbWalls()) {
                return MovementMode.WALL_CLIMB;
            }
        }
        return start.defaultMode(request.intent());
    }

    /**
     * Maps a movement intent to the walking mode used for ground edges.
     */
    private static MovementMode walkMode(PathIntent intent) {
        return intent == PathIntent.RUN ? MovementMode.RUN : MovementMode.WALK;
    }

    /**
     * Returns whether the mode performs a discrete action that must not be smoothed away.
     */
    private static boolean isActionMode(MovementMode mode) {
        return mode == MovementMode.JUMP || mode == MovementMode.SWIM || mode == MovementMode.CLIMB
                || mode == MovementMode.WALL_CLIMB || mode == MovementMode.FLY || mode == MovementMode.FALL;
    }

    /**
     * Admissible heuristic: the straight-line distance between two cells.
     */
    private static double heuristic(TraversalCell from, TraversalCell to) {
        return distance(from, to);
    }

    /**
     * Returns the Euclidean distance between two cells using their stand heights.
     */
    /**
     * fallCost: 鎸夐珮搴﹀樊鍒嗘。璁＄畻璺宠惤浠ｄ环銆?
     * <1鏍? 浣庝唬浠凤紙寰彴闃讹級; ~1鏍? 涓唬浠? >1鏍? 楂樹唬浠凤紙灏介噺缁曡矾锛?
     */
    private static double fallCost(double heightDiff) {
        if (heightDiff < 1.0D) {
            return 1.5D;           // 浣庝唬浠凤細涓嶅埌1鏍?
        } else if (heightDiff <= 1.5D) {
            return 4.0D;           // 涓唬浠凤細绾?鏍?
        } else {
            return 8.0D + heightDiff * 2.0D; // 楂樹唬浠凤細瓒呰繃1鏍硷紝绾挎€ф儵缃?
        }
    }

    private static double distance(TraversalCell from, TraversalCell to) {
        double dx = from.x() - to.x();
        double dy = from.standY() - to.standY();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record Neighbor(TraversalCell cell, MovementMode mode, double cost) {
    }

    private static final class SearchNode {
        private final TraversalCell cell;
        private final SearchNode parent;
        private final MovementMode mode;
        private final double gCost;
        private final double fCost;

        private SearchNode(TraversalCell cell, SearchNode parent, MovementMode mode, double gCost, double fCost) {
            this.cell = cell;
            this.parent = parent;
            this.mode = mode;
            this.gCost = gCost;
            this.fCost = fCost;
        }
    }
}
