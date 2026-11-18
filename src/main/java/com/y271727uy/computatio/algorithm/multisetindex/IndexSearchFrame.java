package com.y271727uy.computatio.algorithm.multisetindex;

/** Search state holder retained for source compatibility with the upstream API. */
public final class IndexSearchFrame<R> {
    boolean probeByBranch;
    int cursor;
    boolean multiWord;
    long skipWord;
    long[] skipWords;
    IndexBranch<R> indexBranch;
    void push(IndexBranch<R> indexBranch, int cursor, IndexSearchFrame<R> ignored) { this.indexBranch = indexBranch; this.cursor = cursor; }
    void push(IndexBranch<R> indexBranch, int cursor, int ignored) { this.indexBranch = indexBranch; this.cursor = cursor; }
}
