package com.y271727uy.computatio.algorithm.multisetindex;

public final class FeatureCountTable {
    final int[] ids;
    long[] amounts;

    public FeatureCountTable(int[] ids) {
        this.ids = ids.clone();
        this.amounts = new long[ids.length];
        java.util.Arrays.fill(this.amounts, 1L);
    }

    public boolean match(IntLongMultiset available) {
        for (int i = 0; i < ids.length; i++) {
            if (available.get(ids[i]) < amounts[i]) return false;
        }
        return true;
    }
}
