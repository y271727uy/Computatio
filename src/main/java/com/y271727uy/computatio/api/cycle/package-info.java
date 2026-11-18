/**
 * Public entry points for cyclic recipe solving and exact material balance.
 *
 * <ul>
 *   <li>{@link com.y271727uy.computatio.api.cycle.CycleProblem}: describe one cyclic component in your own key and
 *   recipe types; it builds the net by NeoECO's rules (recipe dedup and order, key indexing, boundary, remainders,
 *   rejections, recipe totals) and maps the answer back, including the long-range status.</li>
 *   <li>{@link com.y271727uy.computatio.api.cycle.CycleSolving}: static facade over a ready
 *   {@link com.y271727uy.computatio.algorithm.cycle.CycleNet}: solve, async solve, growth-only solve, candidate
 *   verification, exact material balance and repeat multipliers.</li>
 * </ul>
 */
package com.y271727uy.computatio.api.cycle;
