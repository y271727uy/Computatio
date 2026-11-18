package com.y271727uy.computatio.algorithm.fastpath.debug;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 线程安全的调试路径订阅者注册表。 */
public final class PathDebugSubscriptions {
    private final ConcurrentMap<UUID, Boolean> subscriberIds = new ConcurrentHashMap<>();

    /** setEnabled：启用或禁用指定订阅者的调试路径推送。 */
    public boolean setEnabled(UUID subscriberId, boolean enabled) {
        UUID id = Objects.requireNonNull(subscriberId, "subscriberId");
        if (enabled) {
            subscriberIds.put(id, Boolean.TRUE);
        } else {
            subscriberIds.remove(id);
        }
        return enabled;
    }

    /** isEnabled：返回指定订阅者是否已启用。 */
    public boolean isEnabled(UUID subscriberId) {
        return subscriberId != null && subscriberIds.containsKey(subscriberId);
    }

    /** isEmpty：返回当前是否没有订阅者。 */
    public boolean isEmpty() {
        return subscriberIds.isEmpty();
    }

    /** remove：移除离线订阅者。 */
    public void remove(UUID subscriberId) {
        if (subscriberId != null) {
            subscriberIds.remove(subscriberId);
        }
    }

    /** clear：释放所有临时订阅状态。 */
    public void clear() {
        subscriberIds.clear();
    }
}
