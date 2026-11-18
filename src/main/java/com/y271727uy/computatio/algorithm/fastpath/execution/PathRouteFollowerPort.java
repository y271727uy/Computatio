package com.y271727uy.computatio.algorithm.fastpath.execution;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

/**
 * Core 路线跟随器访问实体和平台移动 API 的最小端口。
 *
 * <p>实现必须只在实体所属的游戏线程调用；Core 不持有任何平台实体对象。
 */
public interface PathRouteFollowerPort {
    /** currentPosition：返回实体当前世界坐标。 */
    PathVector currentPosition();

    /** bodyWidth：返回实体碰撞箱宽度，用于通用到点判定。 */
    double bodyWidth();

    /** resolveGroundTargetY：解析普通地面路点的实际落脚高度。 */
    double resolveGroundTargetY(PathWaypoint waypoint);

    /** openDoor：尝试打开指定路点处的平台门方块。 */
    void openDoor(GridPos position);

    /** moveTo：将实体移动控制器指向给定目标。 */
    void moveTo(PathVector target, double targetY, double speedModifier);

    /** 接收完整路点。平台可覆写此方法，根据移动模式执行跳跃、攀爬或飞行。 */
    default void moveToWaypoint(PathWaypoint waypoint, double targetY, double speedModifier) {
        moveTo(waypoint.position(), targetY, speedModifier);
    }
}
