package com.y271727uy.computatio.algorithm.fastpath.debug;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PathDebugSubscriptionsTest {
    @Test
    void storesSubscriptionsWithoutPlatformPlayerTypes() {
        PathDebugSubscriptions subscriptions = new PathDebugSubscriptions();
        UUID playerId = new UUID(3L, 4L);

        assertTrue(subscriptions.setEnabled(playerId, true));
        assertTrue(subscriptions.isEnabled(playerId));
        assertFalse(subscriptions.isEmpty());

        assertFalse(subscriptions.setEnabled(playerId, false));
        assertFalse(subscriptions.isEnabled(playerId));
        assertTrue(subscriptions.isEmpty());
    }
}
