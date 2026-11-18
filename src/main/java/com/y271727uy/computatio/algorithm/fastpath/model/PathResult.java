package com.y271727uy.computatio.algorithm.fastpath.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable search outcome. {@link #success()} indicates that a route is available.
 * Use {@link #status()} to distinguish a route to the requested target block from a route
 * to a nearby block. The last waypoint gives the actual destination.
 */
public record PathResult(boolean success, List<PathWaypoint> waypoints, String reason, Status status) {
    public enum Status {
        EXACT_TARGET(true, ""),
        NEAR_TARGET(true, ""),
        WORLD_MISMATCH(false, "world_mismatch"),
        MISSING_START_OR_TARGET_CELL(false, "missing_start_or_target_cell"),
        INCOMPLETE_SNAPSHOT(false, "incomplete_snapshot"),
        NO_PATH(false, "path_not_found"),
        SEARCH_LIMIT_REACHED(false, "search_limit_reached"),
        UNKNOWN_FAILURE(false, "unknown");

        private final boolean hasRoute;
        private final String reason;

        Status(boolean hasRoute, String reason) {
            this.hasRoute = hasRoute;
            this.reason = reason;
        }

        private static Status fromLegacy(boolean success, String reason) {
            if (success) {
                return EXACT_TARGET;
            }
            for (Status status : values()) {
                if (!status.hasRoute && status.reason.equals(reason)) {
                    return status;
                }
            }
            return UNKNOWN_FAILURE;
        }
    }

    /** Creates a result using the original boolean and reason code. */
    public PathResult(boolean success, List<PathWaypoint> waypoints, String reason) {
        this(success, waypoints, reason, Status.fromLegacy(success, reason));
    }

    public PathResult {
        waypoints = List.copyOf(waypoints);
        reason = Objects.requireNonNullElse(reason, "unknown");
        Objects.requireNonNull(status, "status");
        if (success != status.hasRoute) {
            throw new IllegalArgumentException("success must agree with status");
        }
    }

    /** Returns a route to the requested target block. */
    public static PathResult success(List<PathWaypoint> waypoints) {
        return new PathResult(true, waypoints, "", Status.EXACT_TARGET);
    }

    /** Returns a route to a substitute target block. */
    public static PathResult nearTarget(List<PathWaypoint> waypoints) {
        return new PathResult(true, waypoints, "", Status.NEAR_TARGET);
    }

    /** Returns an outcome without a route. */
    public static PathResult failure(Status status) {
        return new PathResult(false, List.of(), status.reason, status);
    }

    /** Returns a failure result with a stable machine-readable reason. */
    public static PathResult failed(String reason) {
        return new PathResult(false, List.of(), reason);
    }
}
