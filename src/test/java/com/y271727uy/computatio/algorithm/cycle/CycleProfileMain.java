package com.y271727uy.computatio.algorithm.cycle;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Manual profiling entry point (run under JFR); not a test. Args: [reference|optimised] [repeat]. */
public final class CycleProfileMain {
    private CycleProfileMain() {}

    public static void main(String[] args) throws Exception {
        boolean reference = args.length > 0 && args[0].equals("reference");
        int repeat = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        String only = args.length > 2 ? args[2] : null;
        List<CycleNet> corpus = CycleSolverEquivalenceTest.corpus(new Random(0xEC0L));
        CycleSolver solver = reference ? new ReferenceBoundedCycleSolver() : new BoundedCycleSolver();
        String[] kinds = {"fuelled", "free", "scaled", "ring"};
        for (int r = 0; r < repeat; r++) {
            Map<String, double[]> buckets = new TreeMap<>();
            long started = System.nanoTime();
            java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
            long cpuStarted = threads.getCurrentThreadCpuTime();
            long checksum = 0;
            for (int i = 0; i < corpus.size(); i++) {
                if (only != null && !kinds[i % 4].equals(only)) continue;
                long a = System.nanoTime();
                CycleSolution result = CycleSolverEquivalenceTest.run(solver, corpus.get(i));
                double ms = (System.nanoTime() - a) / 1e6;
                checksum += result.status().ordinal();
                double[] bucket = buckets.computeIfAbsent(kinds[i % 4] + "/" + result.status(), k -> new double[3]);
                bucket[0]++;
                bucket[1] += ms;
                bucket[2] = Math.max(bucket[2], ms);
            }
            System.out.printf("%s run %d: %d nets in %.0f ms wall, %.0f ms cpu (checksum %d)%n", reference ? "reference" : "optimised", r,
                corpus.size(), (System.nanoTime() - started) / 1e6, (threads.getCurrentThreadCpuTime() - cpuStarted) / 1e6, checksum);
            buckets.forEach((k, v) -> System.out.printf("  %-36s n=%5.0f total=%8.0f ms  avg=%7.2f ms  max=%7.0f ms%n",
                k, v[0], v[1], v[1] / v[0], v[2]));
        }
    }
}
