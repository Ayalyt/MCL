package org.example.constraint;

import lombok.Getter;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.utils.Z3Solver;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 表示合取约束的析取 (Disjunctive Normal Form - DNF)。
 * 每个实例关联一个特定的时钟集合。
 * 使用 Z3Solver 缓存其逻辑真/假状态。
 *
 * 设计：
 * 1. 持有 `clocks` 字段。
 * 2. `constraints` 字段：包含的 `Constraint` 实例集合，所有实例共享相同的 `clocks` 集合。
 * 3. 使用 `cachedStatus` 字段缓存 Z3 的判断结果 (TRUE, FALSE, SATISFIABLE_UNKNOWN, NOT_YET_CHECKED)。
 * 4. `isTrue()` / `isFalse()` 方法通过缓存或 Z3Solver 判断逻辑真/假。
 * 5. 操作需要时钟集兼容性检查。
 * 6. 实例是不可变的。
 */
@Getter
public final class DisjunctiveConstraint {

    /** 关联的时钟全集 */
    private final Set<Clock> clocks;

    /** 包含的合取约束 (Constraint) 集合 (不可变) */
    private final Set<Constraint> constraints;

    // --- Caching Mechanism ---
    /** Caches the validity status determined by Z3. Volatile for visibility. */
    private transient volatile ValidityStatus cachedStatus = ValidityStatus.NOT_YET_CHECKED;
    /** Lock object for thread-safe computation of cachedStatus. */
    private final transient Object statusLock = new Object();
    /** Static instance of Z3Solver for checks. Assumes Z3Solver is thread-safe. */
    private static final Z3Solver z3Solver = new Z3Solver(); // Shared solver instance


    // --- 构造函数 ---

    /**
     * 私有构造函数，用于创建规范的 TRUE 或 FALSE 实例。
     * 这些实例的缓存状态会被直接设置。
     * @param status    初始缓存状态 (TRUE 或 FALSE)。
     * @param allClocks 关联的时钟集合。
     */
    private DisjunctiveConstraint(ValidityStatus status, Set<Clock> allClocks) {
        Objects.requireNonNull(status, "DisjunctiveConstraint-构造函数(特殊): 状态不能为空");
        Objects.requireNonNull(allClocks, "DisjunctiveConstraint-构造函数(特殊): 时钟集合不能为 null");
        if (status != ValidityStatus.TRUE && status != ValidityStatus.FALSE) {
            throw new IllegalArgumentException("DisjunctiveConstraint-构造函数(特殊): 状态必须是 TRUE 或 FALSE");
        }

        this.clocks = Set.copyOf(allClocks);
        this.cachedStatus = status;

        if (status == ValidityStatus.TRUE) {
            // TRUE DNF contains one TRUE constraint term
            this.constraints = Set.of(Constraint.trueConstraint(this.clocks));
        } else { // FALSE
            // FALSE DNF contains zero terms
            this.constraints = Set.of(Constraint.falseConstraint(this.clocks));
        }
    }

    public ValidityStatus getKnownStatus() {
        return this.cachedStatus;
    }

    /**
     * 私有构造函数，用于根据给定的合取约束集合创建实例。
     * 会进行规范化处理（去除 FALSE 项，如果包含 TRUE 则整体为 TRUE）并检查时钟集兼容性。
     *
     * @param inputConstraints 合取约束的集合。可以为 null 或空。
     * @param allClocks        关联的时钟集合。不允许为 null。
     * @throws NullPointerException     如果 allClocks 为 null。
     * @throws IllegalArgumentException 如果 inputConstraints 中的约束具有与 allClocks 不兼容的时钟集。
     */
    private DisjunctiveConstraint(Set<Constraint> inputConstraints, Set<Clock> allClocks) {
        Objects.requireNonNull(allClocks, "DisjunctiveConstraint-构造函数(普通): 时钟集合不能为 null");
        this.clocks = Set.copyOf(allClocks);

        if (inputConstraints == null || inputConstraints.isEmpty()) {
            // Empty disjunction is FALSE
            this.constraints = Collections.emptySet();
            this.cachedStatus = ValidityStatus.FALSE;
            return;
        }

        Set<Constraint> normalizedConstraints = new HashSet<>();
        boolean containsTrue = false;

        for (Constraint c : inputConstraints) {
            Objects.requireNonNull(c, "DisjunctiveConstraint-构造函数(普通): 输入集合不能包含 null 约束");
            // 检查时钟集兼容性
            if (!this.clocks.equals(c.getClocks())) {
                throw new IllegalArgumentException("DisjunctiveConstraint-构造函数(普通): 输入的 Constraint " + c + " 时钟集与目标时钟集不匹配");
            }
            // Use isTrue()/isFalse() which access cache/Z3
            if (c.isTrue()) {
                containsTrue = true;
                break; // If any term is TRUE, the whole disjunction is TRUE
            }
            if (!c.isFalse()) { // Only add non-FALSE terms
                normalizedConstraints.add(c);
            }
        }

        if (containsTrue) {
            // If contains TRUE term, result is canonical TRUE
            Constraint trueConst = Constraint.trueConstraint(this.clocks);
            this.constraints = Set.of(trueConst);
            this.cachedStatus = ValidityStatus.TRUE;
        } else if (normalizedConstraints.isEmpty()) {
            // If all terms were FALSE (or input was empty)
            this.constraints = Collections.emptySet();
            this.cachedStatus = ValidityStatus.FALSE;
        } else {
            // Normal disjunction, status needs Z3 check
            this.constraints = Set.copyOf(normalizedConstraints);
            this.cachedStatus = ValidityStatus.NOT_YET_CHECKED;
        }
    }

    // --- 工厂方法 ---

    /**
     * 工厂方法，获取表示逻辑真 (⊤) 的析取约束实例。
     * @param allClocks 关联的时钟集合。
     * @return DisjunctiveConstraint 实例。
     */
    public static DisjunctiveConstraint trueConstraint(Set<Clock> allClocks) {
        return new DisjunctiveConstraint(ValidityStatus.TRUE, allClocks);
    }

    /**
     * 工厂方法，获取表示逻辑假 (⊥) 的析取约束实例。
     * @param allClocks 关联的时钟集合。
     * @return DisjunctiveConstraint 实例。
     */
    public static DisjunctiveConstraint falseConstraint(Set<Clock> allClocks) {
        return new DisjunctiveConstraint(ValidityStatus.FALSE, allClocks);
    }

    /**
     * 工厂方法，从合取约束集合创建 DisjunctiveConstraint。
     * @param constraints 合取约束的集合。所有约束必须共享相同的时钟集。
     * @param allClocks   期望的关联时钟集合 (必须与 constraints 中的时钟集匹配)。
     * @return DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果时钟集不匹配。
     */
    public static DisjunctiveConstraint of(Set<Constraint> constraints, Set<Clock> allClocks) {
        // 构造函数会处理 null/empty 和规范化及检查
        return new DisjunctiveConstraint(constraints, allClocks);
    }

    /**
     * 工厂方法，从可变参数的合取约束创建 DisjunctiveConstraint。
     * @param allClocks   关联的时钟集合。
     * @param constraints 合取约束。所有约束必须具有与 allClocks 相同的时钟集。
     * @return DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果时钟集不匹配或 constraints 为空但需要时钟集。
     */
    public static DisjunctiveConstraint of(Set<Clock> allClocks, Constraint... constraints) {
        if (constraints == null || constraints.length == 0) {
            // Empty varargs implies FALSE, requires clocks
            return DisjunctiveConstraint.falseConstraint(allClocks);
        }
        // 构造函数会检查时钟集
        return new DisjunctiveConstraint(Set.of(constraints), allClocks);
    }

    // --- 核心方法 ---

    /**
     * 检查给定的时钟赋值是否满足此析取约束。
     * 注意：这是一个具体的检查，不使用 Z3 或缓存。
     * @param clockValues 时钟到其 Rational 值的映射。
     * @return 如果赋值满足至少一个合取约束，则返回 true。
     * @throws NullPointerException 如果 clockValues 为 null。
     * @throws IllegalArgumentException 如果 clockValues 缺少关联时钟的值。
     */
    public boolean isSatisfied(ClockValuation clockValues) {
        Objects.requireNonNull(clockValues, "DisjunctiveConstraint-isSatisfied: ClockValuation 不能为 null");

        // 检查 clockValues 是否包含所有关联时钟
        for (Clock clock : this.clocks) {
            if (!clock.isZeroClock() && clockValues.getValue(clock) == null) {
                throw new IllegalArgumentException("DisjunctiveConstraint-isSatisfied: ClockValuation 缺少关联时钟 " + clock.getName() + " 的值");
            }
        }

        // If it's known to be TRUE/FALSE, use that info (though it's a specific check)
        // ValidityStatus localStatus = this.cachedStatus; // Quick check without computation
        // if (localStatus == ValidityStatus.TRUE) return true;
        // if (localStatus == ValidityStatus.FALSE) return false;
        // Optimization: if TRUE, we know it's satisfied. If FALSE, we know it isn't.
        // But this method is about specific valuation, so check terms directly.

        if (this.constraints.isEmpty()){
            return false; // Empty disjunction is false
        }

        for (Constraint constraint : constraints) {
            // Use Constraint's isSatisfied for the specific valuation
            if (constraint.isSatisfied(clockValues)) {
                return true; // One satisfied term is enough
            }
        }
        return false; // No term satisfied
    }

    /**
     * 计算当前析取约束与另一个析取约束的逻辑或。
     * (A ∨ B) ∨ (C ∨ D) = A ∨ B ∨ C ∨ D
     * @param other 另一个析取约束。
     * @return 合并后的新 DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint or(DisjunctiveConstraint other) {
        Objects.requireNonNull(other, "DisjunctiveConstraint-or: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("DisjunctiveConstraint-or: 操作数具有不兼容的时钟集合");
        }

        // Use cached status for short-circuiting
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        ValidityStatus otherStatus = other.getOrComputeValidityStatus();

        if (thisStatus == ValidityStatus.TRUE || otherStatus == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (thisStatus == ValidityStatus.FALSE) {
            return other;
        }
        if (otherStatus == ValidityStatus.FALSE) {
            return this;
        }

        // Combine non-FALSE, non-TRUE terms
        Set<Constraint> combined = new HashSet<>(this.constraints);
        combined.addAll(other.constraints);
        // Factory method handles normalization (e.g., if combined contains TRUE)
        return DisjunctiveConstraint.of(combined, this.clocks);
    }

    /**
     * 计算多个析取约束的逻辑或。
     * @param disjunctions 要进行析取的约束。必须具有相同的关联时钟集。
     * @return 析取结果。
     * @throws IllegalArgumentException 如果约束列表为空或时钟集不兼容。
     */
    public static DisjunctiveConstraint or(Collection<DisjunctiveConstraint> disjunctions) {
        if (disjunctions == null || disjunctions.isEmpty()) {
            throw new IllegalArgumentException("DisjunctiveConstraint-or(Collection): 输入集合不能为空");
        }
        Set<Clock> commonClocks = null;
        DisjunctiveConstraint first = null;
        for (DisjunctiveConstraint dc : disjunctions) {
            Objects.requireNonNull(dc, "DisjunctiveConstraint-or(Collection): 输入集合不能包含 null 约束");
            if (commonClocks == null) {
                commonClocks = dc.getClocks();
                first = dc;
            } else if (!commonClocks.equals(dc.getClocks())) {
                throw new IllegalArgumentException("DisjunctiveConstraint-or(Collection): 约束具有不兼容的时钟集合");
            }
        }

        if (disjunctions.size() == 1) {
            return first; // Only one element
        }

        DisjunctiveConstraint result = DisjunctiveConstraint.falseConstraint(commonClocks);
        for(DisjunctiveConstraint dc : disjunctions) {
            result = result.or(dc); // 'or' method handles short-circuiting
            if (result.isTrue()) { // Check using cached/Z3 result
                return result; // Early exit if result becomes TRUE
            }
        }
        return result;
    }
    public static DisjunctiveConstraint or(DisjunctiveConstraint... disjunctions) {
        if (disjunctions == null || disjunctions.length == 0) {
            throw new IllegalArgumentException("DisjunctiveConstraint-or(varargs): 输入数组不能为空");
        }
        return or(Arrays.asList(disjunctions));
    }

    // 和一个合取约束的析取
    public DisjunctiveConstraint or(Constraint conjunction) {
        Objects.requireNonNull(conjunction, "DisjunctiveConstraint-or(Constraint): 输入的约束不能为 null");
        if (!this.clocks.equals(conjunction.getClocks())) {
            throw new IllegalArgumentException("DisjunctiveConstraint-or(Constraint): 操作数具有不兼容的时钟集合");
        }
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        ValidityStatus conjunctionStatus = conjunction.getOrComputeValidityStatus();
        if (thisStatus == ValidityStatus.TRUE || conjunctionStatus == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (thisStatus == ValidityStatus.FALSE) {
            return DisjunctiveConstraint.of(Set.of(conjunction), this.clocks);
        }
        if (conjunctionStatus == ValidityStatus.FALSE) {
            return this;
        }
        Set<Constraint> combined = new HashSet<>(this.constraints);
        combined.add(conjunction);
        return DisjunctiveConstraint.of(combined, this.clocks);
    }


    /**
     * 计算当前析取约束与另一个析取约束的逻辑与 (分配律)。
     * (A ∨ B) ∧ (C ∨ D) = (A ∧ C) ∨ (A ∧ D) ∨ (B ∧ C) ∨ (B ∧ D)
     * @param other 另一个析取约束。
     * @return 应用分配律后的新 DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint and(DisjunctiveConstraint other) {
        Objects.requireNonNull(other, "DisjunctiveConstraint-and: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("DisjunctiveConstraint-and: 操作数具有不兼容的时钟集合");
        }

        // Use cached status for short-circuiting
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        ValidityStatus otherStatus = other.getOrComputeValidityStatus();

        if (thisStatus == ValidityStatus.FALSE || otherStatus == ValidityStatus.FALSE) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (thisStatus == ValidityStatus.TRUE) {
            return other;
        }
        if (otherStatus == ValidityStatus.TRUE) {
            return this;
        }

        // Apply distributive law: (A v B) ^ (C v D) = (A^C) v (A^D) v (B^C) v (B^D)
        Set<Constraint> resultingConstraints = new HashSet<>();
        for (Constraint c1 : this.constraints) {
            for (Constraint c2 : other.constraints) {
                // Constraint.and handles its own short-circuiting
                Constraint intersection = c1.and(c2);
                // Add the result unless it's trivially FALSE
                if (!intersection.isFalse()) { // Use isFalse() to check cache/Z3
                    resultingConstraints.add(intersection);
                }
            }
        }
        // Factory method handles normalization (e.g., if resulting set is empty or contains TRUE)
        return DisjunctiveConstraint.of(resultingConstraints, this.clocks);
    }

    /**
     * 计算多个析取约束的逻辑与。
     * @param disjunctions 要进行合取的约束。必须具有相同的关联时钟集。
     * @return 合取结果。
     * @throws IllegalArgumentException 如果约束列表为空或时钟集不兼容。
     */
    public static DisjunctiveConstraint and(Collection<DisjunctiveConstraint> disjunctions) {
        if (disjunctions == null || disjunctions.isEmpty()) {
            throw new IllegalArgumentException("DisjunctiveConstraint-and(Collection): 输入集合不能为空");
        }
        Set<Clock> commonClocks = null;
        DisjunctiveConstraint first = null;
        for (DisjunctiveConstraint dc : disjunctions) {
            Objects.requireNonNull(dc, "DisjunctiveConstraint-and(Collection): 输入集合不能包含 null 约束");
            if (commonClocks == null) {
                commonClocks = dc.getClocks();
                first = dc;
            } else if (!commonClocks.equals(dc.getClocks())) {
                throw new IllegalArgumentException("DisjunctiveConstraint-and(Collection): 约束具有不兼容的时钟集合");
            }
        }

        if (disjunctions.size() == 1) {
            return first; // Only one element
        }

        DisjunctiveConstraint result = DisjunctiveConstraint.trueConstraint(commonClocks);
        for(DisjunctiveConstraint dc : disjunctions) {
            result = result.and(dc); // 'and' method handles short-circuiting
            if (result.isFalse()) { // Check using cached/Z3 result
                return result; // Early exit if result becomes FALSE
            }
        }
        return result;
    }
    public static DisjunctiveConstraint and(DisjunctiveConstraint... disjunctions) {
        if (disjunctions == null || disjunctions.length == 0) {
            throw new IllegalArgumentException("DisjunctiveConstraint-and(varargs): 输入数组不能为空");
        }
        return and(Arrays.asList(disjunctions));
    }

    // 和一个合取约束的合取。
    // (A ∨ B) ∧ C = (A ∧ C) ∨ (B ∧ C) 根据这个公式来重构出来一个析取约束：
    // 1. 遍历当前析取约束中的每个合取项 Ci
    // 2. 对每个 Ci 应用 Constraint.and(C)，得到新的合取项 (Ci ∧ C)
    // 3. 将所有新的合取项 (Ci ∧ C) 组合成一个新的析取约束
    // 4. 返回这个新的析取约束
    public DisjunctiveConstraint and(Constraint conjunction) {
        Objects.requireNonNull(conjunction, "DisjunctiveConstraint-and(Constraint): 输入的约束不能为 null");
        if (!this.clocks.equals(conjunction.getClocks())) {
            throw new IllegalArgumentException("DisjunctiveConstraint-and(Constraint): 操作数具有不兼容的时钟集合");
        }
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        ValidityStatus conjunctionStatus = conjunction.getOrComputeValidityStatus();
        if (thisStatus == ValidityStatus.FALSE || conjunctionStatus == ValidityStatus.FALSE) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (thisStatus == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.of(Set.of(conjunction), this.clocks);
        }
        if (conjunctionStatus == ValidityStatus.TRUE) {
            return this;
        }
        Set<Constraint> resultingConstraints = new HashSet<>();
        for (Constraint c1 : this.constraints) {
            Constraint intersection = c1.and(conjunction);
            if (!intersection.isFalse()) {
                resultingConstraints.add(intersection);
            }
        }
        return DisjunctiveConstraint.of(resultingConstraints, this.clocks);
    }


    /**
     * 计算当前析取约束的逻辑非 (德摩根定律)。
     * ¬(C1 ∨ C2 ∨ ... ∨ Cn) = ¬C1 ∧ ¬C2 ∧ ... ∧ ¬Cn
     * 结果仍然是一个析取约束 (DNF)，通过应用分配律得到。
     * @return 代表否定的新 DisjunctiveConstraint。
     */
    public DisjunctiveConstraint negate() {

        if (constraints.isEmpty()) { // Should correspond to FALSE status, but handle defensively
            return DisjunctiveConstraint.trueConstraint(this.clocks); // ¬⊥ = ⊤
        }

        // 1. 对每个合取项 Ci 取反，得到 ¬Ci (结果是 DisjunctiveConstraint)
        List<DisjunctiveConstraint> negatedConstraints = new ArrayList<>();
        for (Constraint c : this.constraints) {
            // Constraint.negate() returns a DisjunctiveConstraint
            DisjunctiveConstraint negatedC = c.negate();
            // Check the status of the negation result
            if (negatedC.isFalse()) {
                // If any ¬Ci is FALSE, the whole conjunction is FALSE
                return DisjunctiveConstraint.falseConstraint(this.clocks); // ... ∧ ⊥ ∧ ... = ⊥
            }
            if (!negatedC.isTrue()) { // Ignore TRUE terms in the conjunction (¬Ci=⊤ means Ci=⊥)
                negatedConstraints.add(negatedC);
            }
        }

        // 2. 将所有非平凡的 ¬Ci AND 起来
        if (negatedConstraints.isEmpty()) {
            // If all ¬Ci were TRUE (meaning all original Ci were FALSE), the conjunction is TRUE
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }

        // Use DisjunctiveConstraint.and(Collection) to perform the conjunction
        // This handles the distribution needed to return a DNF result.
        return DisjunctiveConstraint.and(negatedConstraints);
    }


    /**
     * 计算当前析取约束在关联时钟集下的否定，并确保结果析取范式中的合取项互不相交。
     * 使用迭代减法 (Iterative Subtraction) 方法。
     * 逻辑: Target = Universe ∧ ¬(C1 ∨ ... ∨ Cn)
     * 注意：此方法可能计算成本较高。
     *
     * @return 代表否定的新的 DisjunctiveConstraint，其合取项互不相交。
     */
    public DisjunctiveConstraint negateDisjoint() {
        // --- 步骤 0: 处理特殊常量情况 ---
        ValidityStatus status = this.getOrComputeValidityStatus();
        if (status == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (status == ValidityStatus.FALSE) {
            // Negation of FALSE is TRUE, which is represented by a single TRUE constraint term.
            // This is already disjoint.
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }

        // --- 步骤 1: 计算 SimpleNegation = ¬(C1 v ... v Cn) using standard negate ---
        DisjunctiveConstraint simpleNegation = this.negate();

        // Handle simple cases for the negation result
        if (simpleNegation.isTrue()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (simpleNegation.isFalse()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }

        // --- Steps 2 & 3 are conceptually about intersecting with Universe (TRUE) ---
        // The result of negate() is already the region we want to make disjoint.
        DisjunctiveConstraint overlappingRegion = simpleNegation;

        // Get the terms (Constraints) from the simple negation result
        List<Constraint> overlappingTerms = new ArrayList<>(overlappingRegion.getConstraints());
        // Optional: Sort for deterministic behavior, though set operations don't guarantee order later
        overlappingTerms.sort(Comparator.comparing(Constraint::toString));

        // --- 步骤 4: 迭代生成不相交项 ---
        Set<Constraint> finalDisjointTerms = new HashSet<>();
        // Start with an empty accumulated region (FALSE)
        DisjunctiveConstraint accumulatedDisjointRegion = DisjunctiveConstraint.falseConstraint(this.clocks);

        for (Constraint currentOriginalTerm : overlappingTerms) {
            // Calculate Ti' = Ti - Accumulated = Ti ∧ ¬Accumulated
            // Constraint.minus returns a DisjunctiveConstraint representing the difference
            DisjunctiveConstraint disjointPortionOfTi = currentOriginalTerm.toDisjunctiveConstraint().minus(accumulatedDisjointRegion);

            // Add the (potentially multiple) disjoint terms resulting from the subtraction
            if (!disjointPortionOfTi.isFalse()) { // Only add if the difference is not empty
                finalDisjointTerms.addAll(disjointPortionOfTi.getConstraints());
                // Update the accumulated region by ORing the newly added disjoint portion
                accumulatedDisjointRegion = accumulatedDisjointRegion.or(disjointPortionOfTi);
            }
            // Optional optimization: If accumulated region becomes TRUE, no need to process further.
            // However, checking isTrue() might involve Z3 call, potentially costly.
            // if (accumulatedDisjointRegion.isTrue()) break;
        }

        // --- 步骤 5: 返回结果 ---
        // Create a new DisjunctiveConstraint from the collected disjoint terms
        return DisjunctiveConstraint.of(finalDisjointTerms, this.clocks);
    }


    /**
     * 计算当前析取约束与另一个析取约束的差集。
     * this - other = this ∧ ¬other
     * @param other 要减去的析取约束。
     * @return 代表差集的新 DisjunctiveConstraint。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint minus(DisjunctiveConstraint other) {
        Objects.requireNonNull(other, "DisjunctiveConstraint-minus: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("DisjunctiveConstraint-minus: 操作数具有不兼容的时钟集合");
        }

        // Use cached status for short-circuiting
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        ValidityStatus otherStatus = other.getOrComputeValidityStatus();

        if (thisStatus == ValidityStatus.FALSE || otherStatus == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.falseConstraint(this.clocks); // ⊥ - B = ⊥, A - ⊤ = ⊥
        }
        if (otherStatus == ValidityStatus.FALSE) {
            return this; // A - ⊥ = A
        }
        // if (thisStatus == ValidityStatus.TRUE) { // Don't check this status here, negate might be complex
        //     return other.negate(); // ⊤ - B = ¬B
        // }

        // Calculate ¬other
        DisjunctiveConstraint negatedOther = other.negate(); // negate handles other's status

        // Calculate this ∧ ¬other using the existing 'and' method
        return this.and(negatedOther);
    }

    public DisjunctiveConstraint simplify() {
        ValidityStatus knownStatus = this.getKnownStatus();
        if (knownStatus == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (knownStatus == ValidityStatus.FALSE) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }

        if (this.constraints.isEmpty()) {
            if (this.cachedStatus == ValidityStatus.NOT_YET_CHECKED) {
                this.cachedStatus = ValidityStatus.FALSE;
            }
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }


        // 步骤 1: 简化每个内部的 Constraint 项
        Set<Constraint> simplifiedTerms = new HashSet<>();
        boolean becomesTrue = false;
        for (Constraint term : this.constraints) {
            Constraint simplifiedTerm = term.simplify();
            ValidityStatus termStatus = simplifiedTerm.getOrComputeValidityStatus(); // 获取项的状态

            if (termStatus == ValidityStatus.TRUE) {
                becomesTrue = true;
                break;
            }
            if (termStatus != ValidityStatus.FALSE) {
                simplifiedTerms.add(simplifiedTerm);
            }
        }

        if (becomesTrue) {
            if (this.cachedStatus == ValidityStatus.NOT_YET_CHECKED) {
                this.cachedStatus = ValidityStatus.TRUE;
            }
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (simplifiedTerms.isEmpty()) {
            if (this.cachedStatus == ValidityStatus.NOT_YET_CHECKED) {
                this.cachedStatus = ValidityStatus.FALSE;
            }
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }

        // 如果简化后只剩一项，直接返回包含该项的 DNF
        if (simplifiedTerms.size() == 1) {
            if (this.constraints.size() == 1 && this.constraints.containsAll(simplifiedTerms)) {
                return this;
            }
            return DisjunctiveConstraint.of(simplifiedTerms, this.clocks);
        }


        // 步骤 2: 移除冗余项
        List<Constraint> termList = new ArrayList<>(simplifiedTerms);
        int n = termList.size();
        boolean[] removed = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (removed[i]) {
                continue;
            }

            for (int j = 0; j < n; j++) {
                if (i == j || removed[j]) {
                    continue;
                }

                Constraint ci = termList.get(i);
                Constraint cj = termList.get(j);

                if (ci.implies(cj)) {
                    removed[i] = true;
                    break; // ci 被移除，处理下一个 i
                }
            }
        }

        // 步骤 3: 构建最终结果
        Set<Constraint> finalTerms = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (!removed[i]) {
                finalTerms.add(termList.get(i));
            }
        }

        if (finalTerms.equals(simplifiedTerms)) {
            if (this.constraints.equals(simplifiedTerms)) {
                return this;
            } else {
                return DisjunctiveConstraint.of(finalTerms, this.clocks);
            }
        }

        return DisjunctiveConstraint.of(finalTerms, this.clocks);
    }


    // --- Status Check Methods ---

    /** 检查此约束是否逻辑恒为真 (tautology)。 */
    public boolean isTrue() {
        return getOrComputeValidityStatus() == ValidityStatus.TRUE;
    }

    /** 检查此约束是否逻辑恒为假 (unsatisfiable/contradiction)。 */
    public boolean isFalse() {
        return getOrComputeValidityStatus() == ValidityStatus.FALSE;
    }

    /**
     * 获取或计算此约束的逻辑有效性状态。
     * 使用 Z3Solver 进行检查，并缓存结果。线程安全。
     * @return ValidityStatus (TRUE, FALSE, or SATISFIABLE_UNKNOWN).
     */
    private ValidityStatus getOrComputeValidityStatus() {
        ValidityStatus localStatus = cachedStatus; // Read volatile field once
        if (localStatus == ValidityStatus.NOT_YET_CHECKED) {
            synchronized (statusLock) { // Synchronize block for computation
                localStatus = cachedStatus; // Double-check inside lock
                if (localStatus == ValidityStatus.NOT_YET_CHECKED) {
                    try {
                        boolean isTrue = z3Solver.isTrue(this);
                        if (isTrue) {
                            cachedStatus = ValidityStatus.TRUE;
                        } else {
                            // If not TRUE, check if it's FALSE (unsatisfiable)
                            // Z3Solver.isSatisfiable checks if it's NOT FALSE
                            boolean isSat = z3Solver.isSatisfiable(this);
                            if (!isSat) {
                                cachedStatus = ValidityStatus.FALSE;
                            } else {
                                // Satisfiable but not TRUE
                                cachedStatus = ValidityStatus.SATISFIABLE_UNKNOWN;
                            }
                        }
                    } catch (Exception e) { // Catch potential Z3SolverException or others
                        System.err.println("Error checking disjunctive constraint validity: " + e.getMessage());
                        cachedStatus = ValidityStatus.SATISFIABLE_UNKNOWN; // Avoid re-checking constantly on error
                    }
                    localStatus = cachedStatus;
                }
            }
        }
        return localStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DisjunctiveConstraint that = (DisjunctiveConstraint) o;
        // Equality depends on clocks and the set of contained Constraint terms.
        // Cached status is derived.
        return Objects.equals(clocks, that.clocks) &&
                Objects.equals(constraints, that.constraints); // Relies on Constraint.equals
    }

    @Override
    public int hashCode() {
        // Hash code depends on clocks and the set of contained Constraint terms.
        return Objects.hash(clocks, constraints); // Relies on Constraint.hashCode
    }

    @Override
    public String toString() {
        // For NOT_YET_CHECKED or SATISFIABLE_UNKNOWN, display the constraints
        List<String> constraintStrings = constraints.stream()
                .map(Constraint::toString) // Uses Constraint's toString (which checks its cache)
                .sorted()                  // Sort for consistent output
                .toList();

        // Wrap individual constraint terms in parentheses if they contain '∧'
        List<String> formattedStrings = constraintStrings.stream()
                .map(s -> (s.contains("∧") && !s.equals("⊤") && !s.equals("⊥")) ? "(" + s + ")" : s)
                .toList();

        return String.join(" ∨ ", formattedStrings);
    }
}