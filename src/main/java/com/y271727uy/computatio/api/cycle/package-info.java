/**
 * Public entry points for cyclic recipe solving and exact material balance. Signatures use only JDK types and the
 * types of this package.
 *
 * <ul>
 *   <li>{@link com.y271727uy.computatio.api.cycle.CycleProblem}: describe one cyclic component in your own key and
 *   recipe types; it builds the problem by NeoECO's rules (recipe dedup and order, key indexing, boundary, remainders,
 *   rejections, import caps). {@code solve}, {@code solveAsync} and {@code verifyFirings} answer with a
 *   {@link com.y271727uy.computatio.api.cycle.CyclePlan}.</li>
 *   <li>{@link com.y271727uy.computatio.api.cycle.CycleSolving}: exact material balance ({@code balance}, answering
 *   with a {@link com.y271727uy.computatio.api.cycle.MaterialBalance}) and lap multipliers of a compact plan.</li>
 *   <li>{@link com.y271727uy.computatio.api.cycle.CycleCheck} cancels; a
 *   {@link com.y271727uy.computatio.api.cycle.CycleBudget} is a check that also carries one shared allowance and throws
 *   {@link com.y271727uy.computatio.api.cycle.CycleBudgetExhaustedException} when it runs out.</li>
 * </ul>
 */
package com.y271727uy.computatio.api.cycle;
