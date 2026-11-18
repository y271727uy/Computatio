package com.y271727uy.computatio.algorithm.fastpath.execution;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.List;
import java.util.Objects;

/**
 * 平台无关的路径执行状态机。
 *
 * <p>实例仅允许由一个实体所属的游戏线程使用。游泳、攀爬和飞行路点直接使用路径的 Y 坐标，
 * 普通地面路点则委托平台解析落脚高度。
 */
public final class PathRouteFollower {
    private static final double PROGRESS_EPSILON_SQR = 0.0025D;
    private static final int STUCK_REPATH_TICKS = 40;
    private static final double CLIMB_REACHED_DISTANCE_SQR = 0.28D * 0.28D;
    private static final double FLY_REACHED_DISTANCE_SQR = 0.35D * 0.35D;

    private final PathRouteFollowerPort port;
    private final PathCapabilities capabilities;
    private List<PathWaypoint> waypoints = List.of();
    private int waypointIndex;
    private PathVector lastProgressPosition;
    private int stalledTicks;
    private boolean stuck;
    private boolean repairRequested;

    /** 创建与平台实体端口绑定的路线跟随器。 */
    public PathRouteFollower(PathRouteFollowerPort port, PathCapabilities capabilities) {
        this.port = Objects.requireNonNull(port, "port");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** setRoute：接受计算完成的路径并重置跟随状态。 */
    public boolean setRoute(PathResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.success() || result.waypoints().isEmpty()) {
            stop();
            return false;
        }
        waypoints = result.waypoints();
        waypointIndex = 0;
        lastProgressPosition = port.currentPosition();
        stalledTicks = 0;
        stuck = false;
        repairRequested = false;
        return true;
    }

    /** tick：推进一次实体所属游戏线程的路线跟随。 */
    public void tick(double speedModifier) {
        if (isDone()) {
            return;
        }
        PathWaypoint waypoint = waypoints.get(waypointIndex);
        PathVector position = port.currentPosition();
        if (reached(position, waypoint)) {
            waypointIndex++;
            stalledTicks = 0;
            stuck = false;
            return;
        }

        updateStuckState(position);
        if (capabilities.canOpenDoors()) {
            port.openDoor(waypoint.blockPosition());
        }
        double targetY = waypoint.mode().usesDirectTargetHeight()
                ? waypoint.position().y()
                : port.resolveGroundTargetY(waypoint);
        port.moveToWaypoint(waypoint, targetY, speedModifier);
    }

    /** consumeRepathRequest：在卡住后仅返回一次重寻路请求。 */
    public boolean consumeRepathRequest() {
        if (!stuck || repairRequested) {
            return false;
        }
        repairRequested = true;
        return true;
    }

    /** isDone：返回当前路线是否已走完。 */
    public boolean isDone() {
        return waypointIndex >= waypoints.size();
    }

    /** isStuck：返回当前路线是否已达到重寻路阈值。 */
    public boolean isStuck() {
        return stuck;
    }

    /** stop：清除当前路线及其临时进度。 */
    public void stop() {
        waypoints = List.of();
        waypointIndex = 0;
        stalledTicks = 0;
        stuck = false;
        repairRequested = false;
    }

    private void updateStuckState(PathVector position) {
        if (distanceSquared(position, lastProgressPosition) <= PROGRESS_EPSILON_SQR) {
            stalledTicks++;
            if (stalledTicks >= STUCK_REPATH_TICKS) {
                stuck = true;
            }
            return;
        }
        lastProgressPosition = position;
        stalledTicks = 0;
        stuck = false;
        repairRequested = false;
    }

    private boolean reached(PathVector position, PathWaypoint waypoint) {
        PathVector target = waypoint.position();
        if (waypoint.mode() == MovementMode.CLIMB || waypoint.mode() == MovementMode.WALL_CLIMB) {
            return distanceSquared(position, target) <= CLIMB_REACHED_DISTANCE_SQR;
        }
        if (waypoint.mode() == MovementMode.FLY) {
            return distanceSquared(position, target) <= FLY_REACHED_DISTANCE_SQR;
        }
        double bodyWidth = port.bodyWidth();
        double horizontalTolerance = bodyWidth > 0.75D
                ? bodyWidth / 2.0D
                : 0.75D - bodyWidth / 2.0D;
        double horizontalDistanceSqr = horizontalDistanceSquared(position, target);
        return horizontalDistanceSqr <= horizontalTolerance * horizontalTolerance
                && Math.abs(position.y() - target.y()) < 1.0D;
    }

    private static double distanceSquared(PathVector first, PathVector second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizontalDistanceSquared(PathVector first, PathVector second) {
        double dx = first.x() - second.x();
        double dz = first.z() - second.z();
        return dx * dx + dz * dz;
    }
}
