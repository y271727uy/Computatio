package com.y271727uy.computatio.algorithm.fastpath.search;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 在工作线程上执行已冻结快照的寻路任务。
 *
 * <p>调用方必须先在所属平台的安全线程完成地形读取；{@code snapshotSupplier} 只能访问这份
 * 冻结数据，不能读取实时世界。
 */
public final class AsyncPathfinder {
    private AsyncPathfinder() {
    }

    /**
     * findAsync：在指定执行器中构建快照并计算路径。
     *
     * @param request 路径请求
     * @param snapshotSupplier 从已冻结平台数据构造 Core 快照的函数
     * @param workerExecutor 执行 CPU 密集型工作的线程池
     * @return 完成后包含路径计算结果的 Future
     */
    public static CompletableFuture<PathResult> findAsync(
            PathRequest request,
            Supplier<TraversalSnapshot> snapshotSupplier,
            Executor workerExecutor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        Objects.requireNonNull(workerExecutor, "workerExecutor");
        return CompletableFuture.supplyAsync(
                () -> Pathfinder.find(request, snapshotSupplier.get()), workerExecutor);
    }
}
