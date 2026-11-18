package com.y271727uy.computatio.algorithm.cycle;

import com.y271727uy.computatio.algorithm.amount.PlannerAmount;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Necessary material balance, independent of firing order: stock + (out - in) x >= target, x >= 0.
 * Exact two-phase simplex minimizes total firings; integer branching refines fractional answers.
 * Infeasibility is a proof only after every branch closes. A feasible vector is never a liveness proof.
 * Boundary ingredients have unlimited imports here; the caller still verifies their real DAG supply.
 */
public final class CycleStateEquation {
    public enum Status { OPTIMAL, FEASIBLE, INFEASIBLE, UNKNOWN }
    public record Result(Status status, PlannerAmount[] counts) {}
    private record Constraint(int[] indices, BigInteger[] coefficients, BigInteger bound) {
        static Constraint normalized(Map<Integer, BigInteger> row, BigInteger bound) {
            BigInteger gcd = BigInteger.ZERO;
            for (BigInteger coefficient : row.values()) gcd = gcd.gcd(coefficient.abs());
            if (gcd.signum() == 0) gcd = BigInteger.ONE;
            int[] indices = new int[row.size()];
            BigInteger[] coefficients = new BigInteger[row.size()];
            int i = 0;
            for (var entry : row.entrySet()) {
                indices[i] = entry.getKey(); coefficients[i++] = entry.getValue().divide(gcd);
            }
            // Integer lhs is a multiple of gcd, also when rhs is negative.
            BigInteger[] division = bound.divideAndRemainder(gcd);
            return new Constraint(indices, coefficients,
                division[1].signum() < 0 ? division[0].subtract(BigInteger.ONE) : division[0]);
        }
    }

    /** Sparse net consumption columns; gross inputs still require execution verification. */
    public static Result solveSparseMaterialBalance(List<Map<Integer, BigInteger>> columns, boolean[] imports,
            PlannerAmount[] stock, PlannerAmount[] target, boolean firstFeasible,
            Cancellation cancellation) throws InterruptedException {
        return solveSparseMaterialBalance(columns, imports, stock, target, Map.of(), firstFeasible, cancellation);
    }

    /** Optional proven or heuristic lower bounds restrict only this candidate search, never the original graph. */
    public static Result solveSparseMaterialBalance(List<Map<Integer, BigInteger>> columns, boolean[] imports,
            PlannerAmount[] stock, PlannerAmount[] target, Map<Integer, PlannerAmount> lowerBounds,
            boolean firstFeasible, Cancellation cancellation) throws InterruptedException {
        try {
            return solveSparse(columns, imports, stock, target, lowerBounds, firstFeasible, cancellation, new CycleMemoryBudget());
        } catch (CycleMemoryBudget.Exhausted exhausted) {
            return new Result(Status.UNKNOWN, null);
        }
    }

    /** Global static-pattern proposal. Imports are explicit; replay must still prove real supply and order. */
    public static Result solveMaterialBalance(long[][] consumed, long[][] produced, boolean[] imports,
            PlannerAmount[] stock, PlannerAmount[] target, Cancellation cancellation) throws InterruptedException {
        try {
            return solve(consumed, produced, imports, stock, target, cancellation, new CycleMemoryBudget());
        } catch (CycleMemoryBudget.Exhausted exhausted) {
            return new Result(Status.UNKNOWN, null);
        }
    }

    static Result solve(long[][] consumed, long[][] produced, boolean[] boundary,
            PlannerAmount[] stock, PlannerAmount[] target, Cancellation cancellation) throws InterruptedException {
        return solve(consumed, produced, boundary, stock, target, cancellation, new CycleMemoryBudget());
    }

    static Result solve(long[][] consumed, long[][] produced, boolean[] boundary,
            PlannerAmount[] stock, PlannerAmount[] target, Cancellation cancellation, CycleMemoryBudget memory)
            throws InterruptedException {
        List<Map<Integer, BigInteger>> columns = new ArrayList<>();
        for (int p = 0; p < consumed.length; p++) {
            cancellation.checkpoint();
            Map<Integer, BigInteger> column = new LinkedHashMap<>();
            for (int key = 0; key < stock.length; key++) {
                if (consumed[p][key] != produced[p][key]) column.put(key,
                    BigInteger.valueOf(consumed[p][key]).subtract(BigInteger.valueOf(produced[p][key])));
            }
            columns.add(column);
        }
        return solveSparse(columns, boundary, stock, target, Map.of(), false, cancellation, memory);
    }

    private static Result solveSparse(List<Map<Integer, BigInteger>> columns, boolean[] boundary,
            PlannerAmount[] stock, PlannerAmount[] target, Map<Integer, PlannerAmount> lowerBounds, boolean firstFeasible,
            Cancellation cancellation, CycleMemoryBudget memory) throws InterruptedException {
        int variables = columns.size();
        List<Map<Integer, BigInteger>> rows = new ArrayList<>();
        for (int key = 0; key < stock.length; key++) rows.add(new LinkedHashMap<>());
        long constraintBytes = 64L + 64L * stock.length;
        for (int p = 0; p < variables; p++) {
            cancellation.checkpoint();
            for (var entry : columns.get(p).entrySet()) {
                if (entry.getValue().signum() == 0) continue;
                rows.get(entry.getKey()).put(p, entry.getValue());
                constraintBytes += 64L + CycleMemoryBudget.integerBytes(entry.getValue());
            }
            memory.check(constraintBytes);
        }
        List<Constraint> constraints = new ArrayList<>();
        for (int key = 0; key < stock.length; key++) {
            cancellation.checkpoint();
            if (boundary[key]) continue;
            Map<Integer, BigInteger> row = rows.get(key);
            if (row.isEmpty()) {
                if (stock[key].compareTo(target[key]) < 0) return result(Status.INFEASIBLE, null);
                continue;
            }
            if (row.values().stream().noneMatch(value -> value.signum() > 0)
                    && stock[key].compareTo(target[key]) >= 0) continue;
            BigInteger bound = stock[key].subtract(target[key]).toBigInteger();
            constraintBytes += 64L + 16L * row.size() + CycleMemoryBudget.integerBytes(bound);
            memory.check(constraintBytes);
            constraints.add(Constraint.normalized(row, bound));
        }
        for (var lower : lowerBounds.entrySet()) {
            cancellation.checkpoint();
            if (lower.getKey() < 0 || lower.getKey() >= variables || lower.getValue().signum() < 0)
                throw new IllegalArgumentException("Invalid firing lower bound");
            constraints.add(new Constraint(new int[] {lower.getKey()}, new BigInteger[] {BigInteger.ONE.negate()},
                lower.getValue().toBigInteger().negate()));
            constraintBytes += 128L + CycleMemoryBudget.integerBytes(lower.getValue().toBigInteger());
            memory.check(constraintBytes);
        }
        return presolveAndMinimize(variables, constraints, firstFeasible, cancellation, memory, constraintBytes);
    }

    /** Exact interval propagation; only quantities forced in every feasible vector are substituted. */
    private static Result presolveAndMinimize(int variables, List<Constraint> constraints, boolean firstFeasible,
            Cancellation cancellation, CycleMemoryBudget memory, long bytes) throws InterruptedException {
        BigInteger[] lower = new BigInteger[variables], upper = new BigInteger[variables];
        memory.check(bytes + 64L + 32L * variables);
        Arrays.fill(lower, BigInteger.ZERO);
        boolean changed;
        int passes = 0;
        do {
            changed = false;
            for (Constraint row : constraints) {
                cancellation.checkpoint();
                BigInteger minimum = BigInteger.ZERO;
                BigInteger[] terms = new BigInteger[row.indices.length];
                int unbounded = 0;
                for (int i = 0; i < row.indices.length; i++) {
                    cancellation.checkpoint();
                    int variable = row.indices[i]; BigInteger coefficient = row.coefficients[i];
                    BigInteger bound = coefficient.signum() > 0 ? lower[variable] : upper[variable];
                    if (bound == null) unbounded++;
                    else {
                        terms[i] = coefficient.multiply(bound);
                        minimum = minimum.add(terms[i]);
                    }
                }
                if (unbounded == 0 && minimum.compareTo(row.bound) > 0) return result(Status.INFEASIBLE, null);
                for (int i = 0; i < row.indices.length; i++) {
                    cancellation.checkpoint();
                    int variable = row.indices[i]; BigInteger coefficient = row.coefficients[i];
                    if (unbounded - (terms[i] == null ? 1 : 0) != 0) continue;
                    BigInteger other = terms[i] == null ? minimum : minimum.subtract(terms[i]);
                    BigInteger rhs = row.bound.subtract(other);
                    BigInteger bound = coefficient.signum() > 0
                        ? floorDiv(rhs, coefficient) : floorDiv(rhs, coefficient.negate()).negate();
                    if (coefficient.signum() > 0 && (upper[variable] == null || bound.compareTo(upper[variable]) < 0)) {
                        upper[variable] = bound; changed = true;
                    } else if (coefficient.signum() < 0 && bound.compareTo(lower[variable]) > 0) {
                        lower[variable] = bound; changed = true;
                    }
                    if (upper[variable] != null && lower[variable].compareTo(upper[variable]) > 0)
                        return result(Status.INFEASIBLE, null);
                }
            }
        // Contradictory unbounded feedback may raise lower bounds forever. A finite preprocessing
        // pass is sufficient: all substitutions remain exact and simplex closes the remaining proof.
        } while (changed && ++passes <= variables);
        int[] remap = new int[variables]; int retained = 0;
        for (int p = 0; p < variables; p++) remap[p] = lower[p].equals(upper[p]) ? -1 : retained++;
        if (retained == variables) return minimize(variables, constraints, firstFeasible, cancellation, memory, bytes);
        List<Constraint> reduced = new ArrayList<>();
        for (Constraint row : constraints) {
            cancellation.checkpoint();
            Map<Integer, BigInteger> coefficients = new LinkedHashMap<>(); BigInteger bound = row.bound;
            for (int i = 0; i < row.indices.length; i++) {
                int p = row.indices[i];
                if (remap[p] < 0) bound = bound.subtract(row.coefficients[i].multiply(lower[p]));
                else coefficients.put(remap[p], row.coefficients[i]);
            }
            if (coefficients.isEmpty()) {
                if (bound.signum() < 0) return result(Status.INFEASIBLE, null);
            } else reduced.add(Constraint.normalized(coefficients, bound));
        }
        Result result = minimize(retained, reduced, firstFeasible, cancellation, memory, bytes);
        if (result.counts == null) return result;
        PlannerAmount[] full = new PlannerAmount[variables];
        for (int p = 0; p < variables; p++) full[p] = remap[p] < 0 ? PlannerAmount.of(lower[p]) : result.counts[remap[p]];
        return new Result(result.status, full);
    }

    private static BigInteger floorDiv(BigInteger numerator, BigInteger positiveDenominator) {
        BigInteger[] division = numerator.divideAndRemainder(positiveDenominator);
        return division[1].signum() < 0 ? division[0].subtract(BigInteger.ONE) : division[0];
    }

    private static Result minimize(int variables, List<Constraint> constraints, boolean firstFeasible, Cancellation cancellation,
            CycleMemoryBudget memory, long constraintBytes)
            throws InterruptedException {
        var pending = new ArrayDeque<List<Constraint>>();
        pending.push(constraints);
        BigInteger[] best = null;
        BigInteger bestCost = null;
        Budget budget = new Budget(cancellation, memory, constraintBytes);
        try {
            while (!pending.isEmpty()) {
                cancellation.checkpoint();
                List<Constraint> branch = pending.pop();
                // Branches share constraint rows; account for list references and new split rows.
                long pendingBytes = 0;
                for (List<Constraint> queued : pending) pendingBytes += 64L + 8L * queued.size();
                budget.branchBytes = pendingBytes + 2L * branch.size() * (64L + 80L * variables);
                Rational[] vector = new Tableau(variables, branch, budget).solve();
                if (vector == null) continue;
                Rational cost = Rational.ZERO;
                int fractional = -1;
                for (int p = 0; p < variables; p++) {
                    cost = cost.add(vector[p]);
                    if (!vector[p].isInteger() && fractional < 0) fractional = p;
                }
                // Every physical firing count is integral, so the integer objective is at least ceil(LP).
                // Comparing only the fractional bound keeps exploring equivalent joint-output patterns
                // even after an optimal integer vector is known (e.g. every split of 10,682.75 firings).
                if (bestCost != null && cost.ceiling().compareTo(bestCost) >= 0) continue;
                if (fractional < 0) {
                    best = Arrays.stream(vector).map(Rational::numerator).toArray(BigInteger[]::new);
                    bestCost = cost.numerator();
                    if (firstFeasible) return result(Status.FEASIBLE, best);
                    continue;
                }
                BigInteger floor = vector[fractional].numerator().divide(vector[fractional].denominator());
                var lower = new ArrayList<>(branch);
                lower.add(new Constraint(new int[] {fractional}, new BigInteger[] {BigInteger.ONE}, floor));
                var upper = new ArrayList<>(branch);
                upper.add(new Constraint(new int[] {fractional}, new BigInteger[] {BigInteger.ONE.negate()},
                    floor.add(BigInteger.ONE).negate()));
                pending.push(lower);
                pending.push(upper);
            }
        } catch (PlanningBudget.Exhausted | CycleMemoryBudget.Exhausted exhausted) {
            if (best != null) return result(Status.FEASIBLE, best);
            throw exhausted;
        }
        return result(best == null ? Status.INFEASIBLE : Status.OPTIMAL, best);
    }

    private static Result result(Status status, BigInteger[] vector) {
        return new Result(status, vector == null ? null
            : Arrays.stream(vector).map(PlannerAmount::of).toArray(PlannerAmount[]::new));
    }

    private static final class Budget {
        final Cancellation cancellation;
        final CycleMemoryBudget memory;
        final long constraintBytes;
        long branchBytes;
        Budget(Cancellation cancellation, CycleMemoryBudget memory, long constraintBytes) {
            this.cancellation = cancellation; this.memory = memory; this.constraintBytes = constraintBytes;
        }
        void pivot() throws InterruptedException {
            cancellation.checkpoint();
        }
    }

    /** Dictionary for Ax <= b, x >= 0; artificial variable supplies the phase-one feasible basis. */
    private static final class Tableau {
        final int m, n;
        final int[] basic, nonbasic;
        final Rational[][] d;
        /** Per-row sum of entry estimates, kept current on every write so checkMemory need not rescan entries. */
        final long[] rowBytes;
        final Budget budget;

        Tableau(int variables, List<Constraint> rows, Budget budget) throws InterruptedException {
            m = rows.size(); n = variables; this.budget = budget;
            budget.memory.check(budget.constraintBytes + budget.branchBytes + 160L * (m + 2L) * (n + 2L));
            basic = new int[m]; nonbasic = new int[n + 1];
            d = new Rational[m + 2][n + 2];
            for (var row : d) Arrays.fill(row, Rational.ZERO);
            for (int i = 0; i < m; i++) {
                budget.cancellation.checkpoint();
                Constraint row = rows.get(i);
                for (int j = 0; j < row.indices.length; j++) d[i][row.indices[j]] = Rational.of(row.coefficients[j]);
                basic[i] = n + i;
                d[i][n] = Rational.NEGATIVE_ONE;
                d[i][n + 1] = Rational.of(rows.get(i).bound);
            }
            for (int j = 0; j < n; j++) {
                nonbasic[j] = j;
                d[m][j] = Rational.ONE; // Maximize -sum(x).
            }
            nonbasic[n] = -1;
            d[m + 1][n] = Rational.ONE;
            rowBytes = new long[m + 2];
            for (int i = 0; i < m + 2; i++) for (Rational value : d[i]) rowBytes[i] += entryBytes(value);
            checkMemory();
        }

        static long entryBytes(Rational value) {
            return 32L + value.numeratorBytes() + value.denominatorBytes();
        }

        void set(int i, int j, Rational value) {
            rowBytes[i] += entryBytes(value) - entryBytes(d[i][j]);
            d[i][j] = value;
        }

        void checkMemory() throws InterruptedException {
            long bytes = budget.constraintBytes + budget.branchBytes + 32L + 16L * (m + n + 4L);
            for (int i = 0; i < d.length; i++) {
                budget.cancellation.checkpoint();
                bytes += 24L + 8L * d[i].length + rowBytes[i];
                budget.memory.check(bytes);
            }
        }

        void pivot(int row, int column) throws InterruptedException {
            budget.pivot();
            Rational inverse = Rational.ONE.divide(d[row][column]);
            // x - 0 * f is x itself, so elimination only touches the pivot row's nonzero columns (rings are sparse).
            Rational[] pivotRow = d[row];
            int[] support = new int[n + 2];
            int supportSize = 0;
            for (int j = 0; j < n + 2; j++) if (j != column && pivotRow[j].signum() != 0) support[supportSize++] = j;
            for (int i = 0; i < m + 2; i++) {
                if (i == row || d[i][column].signum() == 0) continue;
                budget.cancellation.checkpoint();
                Rational factor = d[i][column].multiply(inverse);
                Rational[] target = d[i];
                for (int s = 0; s < supportSize; s++) {
                    int j = support[s];
                    set(i, j, target[j].subtract(pivotRow[j].multiply(factor)));
                }
            }
            // Zero entries stay zero under scaling, so only the support and the nonzero column entries are written.
            for (int s = 0; s < supportSize; s++) set(row, support[s], pivotRow[support[s]].multiply(inverse));
            for (int i = 0; i < m + 2; i++) {
                if (i != row && d[i][column].signum() != 0) set(i, column, d[i][column].multiply(inverse).negate());
            }
            set(row, column, inverse);
            int previous = basic[row]; basic[row] = nonbasic[column]; nonbasic[column] = previous;
            checkMemory();
        }

        boolean simplex(int phase) throws InterruptedException {
            int objective = phase == 1 ? m + 1 : m;
            while (true) {
                budget.cancellation.checkpoint();
                int entering = -1;
                // Bland's rule, including tie-breaking on the leaving variable, prevents cycling.
                for (int j = 0; j <= n; j++) {
                    if (phase == 2 && nonbasic[j] == -1 || d[objective][j].signum() >= 0) continue;
                    if (entering < 0 || nonbasic[j] < nonbasic[entering]) entering = j;
                }
                if (entering < 0) return true;
                int leaving = -1;
                for (int i = 0; i < m; i++) {
                    if (d[i][entering].signum() <= 0) continue;
                    int comparison = leaving < 0 ? -1 : d[i][n + 1].divide(d[i][entering])
                        .compareTo(d[leaving][n + 1].divide(d[leaving][entering]));
                    if (comparison < 0 || comparison == 0 && basic[i] < basic[leaving]) leaving = i;
                }
                if (leaving < 0) return false;
                pivot(leaving, entering);
            }
        }

        Rational[] solve() throws InterruptedException {
            int row = -1;
            for (int i = 0; i < m; i++) if (row < 0 || d[i][n + 1].compareTo(d[row][n + 1]) < 0) row = i;
            if (row >= 0 && d[row][n + 1].signum() < 0) {
                pivot(row, n);
                if (!simplex(1)) throw new IllegalStateException("Unbounded phase-one objective");
                if (d[m + 1][n + 1].signum() < 0) return null;
                if (d[m + 1][n + 1].signum() != 0) throw new IllegalStateException("Invalid phase-one basis");
                for (int i = 0; i < m; i++) {
                    if (basic[i] != -1) continue;
                    int column = -1;
                    for (int j = 0; j <= n; j++) {
                        if (d[i][j].signum() != 0 && (column < 0 || nonbasic[j] < nonbasic[column])) column = j;
                    }
                    if (column >= 0) pivot(i, column);
                }
            }
            if (!simplex(2)) throw new IllegalStateException("Unbounded nonnegative firing objective");
            Rational[] vector = new Rational[n];
            Arrays.fill(vector, Rational.ZERO);
            for (int i = 0; i < m; i++) if (basic[i] >= 0 && basic[i] < n) vector[basic[i]] = d[i][n + 1];
            return vector;
        }
    }

    /**
     * Exact reduced fraction with a {@code long} fast path and a {@code BigInteger} fallback on overflow (the exact-LP
     * idea of doing most work in cheap arithmetic, after Gleixner, Steffy and Wolter, but without any rounding: long
     * arithmetic with overflow checks is itself exact). Reduced fractions are unique, so every operation returns
     * exactly the value the all-BigInteger arithmetic returned. Invariant: denominator > 0, gcd(|num|, den) = 1, and
     * the BigInteger form is used only when the numerator or the denominator does not fit in a long.
     */
    private static final class Rational implements Comparable<Rational> {
        static final Rational ZERO = new Rational(0L, 1L), ONE = new Rational(1L, 1L), NEGATIVE_ONE = new Rational(-1L, 1L);
        private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

        private final long num, den;          // valid when bigNum == null
        private final BigInteger bigNum, bigDen;

        private Rational(long num, long den) { this.num = num; this.den = den; bigNum = null; bigDen = null; }
        private Rational(BigInteger num, BigInteger den) { this.num = 0L; this.den = 1L; bigNum = num; bigDen = den; }

        static Rational of(BigInteger value) { return reduce(value, BigInteger.ONE); }

        /** Reduces and normalises an arbitrary BigInteger fraction (the behaviour of the former record constructor). */
        private static Rational reduce(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) throw new ArithmeticException("Zero denominator");
            if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
            if (!denominator.equals(BigInteger.ONE)) {
                BigInteger gcd = numerator.gcd(denominator);
                if (!gcd.equals(BigInteger.ONE)) { numerator = numerator.divide(gcd); denominator = denominator.divide(gcd); }
            }
            if (fits(numerator) && fits(denominator)) return small(numerator.longValue(), denominator.longValue());
            return new Rational(numerator, denominator);
        }

        /** Already reduced, denominator positive. */
        private static Rational small(long num, long den) {
            if (num == 0L) return ZERO;
            if (den == 1L) {
                if (num == 1L) return ONE;
                if (num == -1L) return NEGATIVE_ONE;
            }
            return new Rational(num, den);
        }

        /** Reduces a long fraction with a positive denominator; null when the reduction cannot stay in longs. */
        private static Rational reduceSmall(long num, long den) {
            if (num == Long.MIN_VALUE) return null;
            if (num == 0L) return ZERO;
            long g = gcd(Math.abs(num), den);
            return small(num / g, den / g);
        }

        private static boolean fits(BigInteger value) {
            return value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0;
        }

        /** a, b >= 0 and not both zero. */
        private static long gcd(long a, long b) {
            // Binary (Stein) gcd: shifts and subtractions instead of a 64-bit division per Euclid step.
            if (a == 0L) return b;
            if (b == 0L) return a;
            int shift = Long.numberOfTrailingZeros(a | b);
            a >>>= Long.numberOfTrailingZeros(a);
            do {
                b >>>= Long.numberOfTrailingZeros(b);
                if (a > b) {
                    long t = a;
                    a = b;
                    b = t;
                }
                b -= a;
            } while (b != 0L);
            return a << shift;
        }

        private static boolean multiplyFits(long a, long b) {
            long high = Math.multiplyHigh(a, b);
            long low = a * b;
            return (high == 0L && low >= 0L) || (high == -1L && low < 0L);
        }

        private boolean isSmall() { return bigNum == null; }
        BigInteger numerator() { return isSmall() ? BigInteger.valueOf(num) : bigNum; }
        BigInteger denominator() { return isSmall() ? BigInteger.valueOf(den) : bigDen; }
        boolean isInteger() { return isSmall() ? den == 1L : bigDen.equals(BigInteger.ONE); }
        int signum() { return isSmall() ? Long.signum(num) : bigNum.signum(); }

        long numeratorBytes() {
            return isSmall() ? CycleMemoryBudget.integerBytes(num) : CycleMemoryBudget.integerBytes(bigNum);
        }

        long denominatorBytes() {
            return isSmall() ? CycleMemoryBudget.integerBytes(den) : CycleMemoryBudget.integerBytes(bigDen);
        }

        BigInteger ceiling() {
            if (isSmall()) {
                long quotient = num / den;
                return num % den > 0L ? BigInteger.valueOf(quotient).add(BigInteger.ONE) : BigInteger.valueOf(quotient);
            }
            BigInteger[] quotient = bigNum.divideAndRemainder(bigDen);
            return quotient[1].signum() > 0 ? quotient[0].add(BigInteger.ONE) : quotient[0];
        }

        Rational negate() {
            if (isSmall() && num != Long.MIN_VALUE) return small(-num, den);
            return reduce(numerator().negate(), denominator());
        }

        Rational add(Rational other) {
            if (other.signum() == 0) return this;
            if (signum() == 0) return other;
            if (isSmall() && other.isSmall()) {
                if (den == 1L && other.den == 1L) {
                    long a = num, b = other.num, sum = a + b;
                    if (((a ^ sum) & (b ^ sum)) >= 0) return small(sum, 1L);
                } else {
                    long g = gcd(den, other.den);
                    long left = other.den / g, right = den / g;
                    if (multiplyFits(num, left) && multiplyFits(other.num, right) && multiplyFits(den, left)) {
                        long a = num * left, b = other.num * right, sum = a + b;
                        if (((a ^ sum) & (b ^ sum)) >= 0) {
                            Rational reduced = reduceSmall(sum, den * left);
                            if (reduced != null) return reduced;
                        }
                    }
                }
            }
            BigInteger d1 = denominator(), d2 = other.denominator();
            BigInteger gcd = d1.gcd(d2);
            BigInteger left = d2.divide(gcd), right = d1.divide(gcd);
            return reduce(numerator().multiply(left).add(other.numerator().multiply(right)), d1.multiply(left));
        }

        Rational subtract(Rational other) { return add(other.negate()); }

        Rational multiply(Rational other) {
            if (signum() == 0 || other.signum() == 0) return ZERO;
            if (isSmall() && other.isSmall() && num != Long.MIN_VALUE && other.num != Long.MIN_VALUE) {
                long left = gcd(Math.abs(num), other.den), right = gcd(Math.abs(other.num), den);
                long a = num / left, b = other.num / right, c = den / right, e = other.den / left;
                if (multiplyFits(a, b) && multiplyFits(c, e)) return small(a * b, c * e);
            }
            BigInteger n1 = numerator(), d1 = denominator(), n2 = other.numerator(), d2 = other.denominator();
            if (d1.equals(BigInteger.ONE) && d2.equals(BigInteger.ONE)) return reduce(n1.multiply(n2), BigInteger.ONE);
            BigInteger left = n1.gcd(d2), right = n2.gcd(d1);
            return reduce(n1.divide(left).multiply(n2.divide(right)), d1.divide(right).multiply(d2.divide(left)));
        }

        Rational divide(Rational other) {
            if (other.signum() == 0) throw new ArithmeticException("Zero divisor");
            if (signum() == 0) return ZERO;
            Rational reciprocal;
            if (other.isSmall() && other.num != Long.MIN_VALUE) {
                reciprocal = other.num > 0 ? small(other.den, other.num) : small(-other.den, -other.num);
            } else {
                reciprocal = reduce(other.denominator(), other.numerator());
            }
            return multiply(reciprocal);
        }

        @Override public int compareTo(Rational other) {
            if (isSmall() && other.isSmall()) {
                if (den == other.den) return Long.compare(num, other.num);
                long highLeft = Math.multiplyHigh(num, other.den), lowLeft = num * other.den;
                long highRight = Math.multiplyHigh(other.num, den), lowRight = other.num * den;
                if (highLeft != highRight) return Long.compare(highLeft, highRight);
                return Long.compareUnsigned(lowLeft, lowRight);
            }
            BigInteger d1 = denominator(), d2 = other.denominator();
            if (d1.equals(d2)) return numerator().compareTo(other.numerator());
            return numerator().multiply(d2).compareTo(other.numerator().multiply(d1));
        }

        @Override public String toString() { return numerator() + "/" + denominator(); }
    }
}
