package com.y271727uy.computatio.api.multisetindex;

@FunctionalInterface
public interface FeatureCounts {
    boolean matches(FeatureMultiset available);
}
